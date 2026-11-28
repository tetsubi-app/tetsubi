package gg.dekiru.musicplayer.playback

import gg.dekiru.musicplayer.data.api.FakeServer
import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import gg.dekiru.musicplayer.data.api.bodyText
import gg.dekiru.musicplayer.data.api.path
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinPerson
import gg.dekiru.musicplayer.data.models.JellyfinTrack
import gg.dekiru.musicplayer.data.models.MediaSource
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A player that records what it's told. `cast`: as if Google Cast were there. */
class FakeEngine(
    override val isSupported: Boolean = true,
    override val cast: CastRoute? = null,
    directPlay: Set<AudioFormat> = setOf(AudioFormat.FLAC, AudioFormat.MP3, AudioFormat.AAC),
) : PlayerEngine {
    override val directPlayFormats = directPlay
    override var listener: EngineListener? = null
    var items: List<EngineItem> = emptyList()
    var index = 0
    var playing = false
    var repeat = RepeatMode.OFF
    var currentVolume = 1f
    var skipEnabled = true
    var authHeader: String? = null
    override var positionMillis = 0L
    override var durationMillis = 0L
    val seeks = mutableListOf<Long>()

    override fun setAuthorization(header: String?) { authHeader = header }
    /** How many times a whole new queue was loaded (an edit that interrupts playback would be one). */
    var loads = 0
    override fun setQueue(items: List<EngineItem>, startIndex: Int) { this.items = items; index = startIndex; loads++; opened = true }

    /** Whether the current item's stream was opened (Media3 prepared): a cued queue isn't until Play. */
    var opened = false

    /** How many times a queue was cued (held, nothing opened), and the point it was cued at. */
    var cues = 0
    var cuedAt = -1L

    /** Where the stream opened on the first Play after a cue (-1: not yet). */
    var openedAt = -1L

    override fun cue(items: List<EngineItem>, startIndex: Int, startMillis: Long) {
        this.items = items
        index = startIndex
        cues++
        cuedAt = startMillis
        positionMillis = startMillis
        opened = false
        if (playing) pause()
    }

    /**
     * False: Play doesn't make a sound until [soundStarts] (a slow stream):
     * the engine says it's starting meanwhile, as Media3 does while buffering.
     */
    var startsInstantly = true
    var starting = false
        private set

    /** A dropped stream (that had played) waits to reconnect, as Media3's reconnect does; still "playing". */
    var reconnecting = false
        private set

    fun streamDrops() {
        reconnecting = true
        starting = true
        listener?.onStartingChanged(true, reconnecting = true)
    }

    /** The slow stream's sound came. */
    fun soundStarts() {
        starting = false
        reconnecting = false
        listener?.onStartingChanged(false)
        playing = true
        listener?.onIsPlayingChanged(true)
    }
    override fun replaceAfterCurrent(items: List<EngineItem>) { this.items = this.items.take(index + 1) + items }
    override fun replaceAroundCurrent(before: List<EngineItem>, after: List<EngineItem>) {
        items = before + items[index] + after
        index = before.size
    }
    override fun play() {
        if (!opened) {
            opened = true
            openedAt = positionMillis
        }
        if (startsInstantly) {
            playing = true
            listener?.onIsPlayingChanged(true)
        } else if (!playing && !starting) {
            starting = true
            listener?.onStartingChanged(true)
        }
    }
    override fun pause() {
        if (starting) {
            // Pausing ends a reconnect's tries.
            starting = false
            reconnecting = false
            listener?.onStartingChanged(false)
        }
        playing = false
        listener?.onIsPlayingChanged(false)
    }
    override fun seekTo(millis: Long) { seeks += millis; positionMillis = millis }
    override fun skipTo(index: Int) { this.index = index; listener?.onIndexChanged(index, automatic = false) }
    override fun setRepeatMode(mode: RepeatMode) { repeat = mode }
    override fun setVolume(volume: Float) { currentVolume = volume }
    override fun setSkipControlsEnabled(enabled: Boolean) { skipEnabled = enabled }
    override fun stop() { items = emptyList(); playing = false }

    /** What [updateCastUrls] was given. */
    val castUpdates = mutableListOf<Map<String, String>>()

    override fun updateCastUrls(urls: Map<String, String>) {
        castUpdates += urls
        items = items.map { item -> urls[item.id]?.let { item.copy(castUrl = it) } ?: item }
    }

    /** The track played to its end and the next began. */
    fun finishTrack() {
        index += 1
        listener?.onIndexChanged(index, automatic = true)
    }
}

/**
 * Lets queued work finish: the pretend server answers on real threads, so
 * advancing the test clock alone returns before its answers arrive.
 */
suspend fun TestScope.settle() {
    repeat(20) {
        advanceUntilIdle()
        withContext(Dispatchers.Default) { delay(5) }
    }
    advanceUntilIdle()
}

class PlaybackTest {

    private fun track(i: Int, album: String = "al", container: String = "flac") = """
        {"Id":"t$i","Name":"Song $i","IndexNumber":$i,"AlbumId":"$album","RunTimeTicks":${i * 600_000_000L},
         "AlbumArtists":[{"Name":"Band"}],"NormalizationGain":-6.0,
         "MediaSources":[{"Container":"$container","Codecs":"$container"}]}
    """

    private val album = JellyfinAlbum(id = "al", name = "Album", albumArtists = listOf(JellyfinPerson(name = "Band")))

    private class Setup(val server: FakeServer, val engine: FakeEngine, val player: PlayerController, val prefs: InMemoryPreferences)

    private fun TestScope.setup(
        tracks: Int = 4,
        container: String = "flac",
        metered: Boolean = false,
        maniac: Boolean = false,
    ): Setup {
        val server = FakeServer { request ->
            when (request.path) {
                "/Items" -> json("""{"Items":[${(1..tracks).joinToString(",") { track(it, request.url.parameters["ParentId"]!!, container) }}]}""")
                else -> json("")
            }
        }
        val engine = FakeEngine()
        val prefs = InMemoryPreferences()
        val settings = PlaybackSettings(prefs).also { it.maniacMode = maniac }
        val player = PlayerController(
            server.api(token = "tok", userId = "u1"), engine, settings, backgroundScope,
            isExpensiveNetwork = { metered }, random = Random(1),
        )
        return Setup(server, engine, player, prefs)
    }

    private fun Setup.reports() = server.requests.filter { it.path.startsWith("/Sessions/Playing") }
        .map { it.path.removePrefix("/Sessions/Playing").ifEmpty { "start" } to it.bodyText }

    @Test
    fun playsAnAlbumFromTheStart() = runTest {
        val s = setup()
        s.player.play(album)
        settle()
        assertEquals(listOf("t1", "t2", "t3", "t4"), s.engine.items.map { it.id })
        assertEquals(0, s.engine.index)
        assertTrue(s.engine.playing)
        assertEquals("Song 1", s.player.currentTrack?.title)
        assertEquals("Band", s.player.currentTrack?.artist)
        assertEquals(60.0, s.player.duration)
        assertTrue(s.player.isNowPlayingOpen)
        assertTrue(s.engine.authHeader!!.contains("Token=\"tok\""))
        assertEquals(listOf("start"), s.reports().map { it.first })
    }

    @Test
    fun tappedTrackPlaysFirst() = runTest {
        val s = setup()
        s.player.play(album, startTrackId = "t3")
        settle()
        assertEquals(2, s.engine.index)
        assertEquals("t3", s.player.currentTrack?.id)
    }

    @Test
    fun shuffleKeepsTheTappedTrackFirst() = runTest {
        val s = setup(tracks = 10)
        s.player.toggleShuffle()
        s.player.play(album, startTrackId = "t7")
        settle()
        assertEquals("t7", s.engine.items.first().id)
        assertEquals(0, s.engine.index)
        assertEquals((1..10).map { "t$it" }.toSet(), s.engine.items.map { it.id }.toSet())
    }

    @Test
    fun multiDiscAlbumsPlayEveryDiscInOrder() = runTest {
        val s = setup(tracks = 2)
        val d1 = JellyfinAlbum(id = "d1", name = "Set (Disc 1)")
        val d2 = JellyfinAlbum(id = "d2", name = "Set (Disc 2)")
        val merged = d1.copy(name = "Set", discAlbums = listOf(d1, d2))
        s.player.play(merged)
        settle()
        assertEquals(4, s.engine.items.size)
        assertTrue(s.player.isPlaying(merged))
        assertTrue(s.player.isPlaying(d2), "a lone disc's page knows its set is playing")
        assertFalse(s.player.isPlaying(album))
    }

    @Test
    fun skipsAndReports() = runTest {
        val s = setup()
        s.player.play(album)
        settle()
        s.player.skipToNext()
        settle()
        assertEquals(1, s.player.currentIndex)
        // Played through: the stop reports the full length (counts as a play).
        s.engine.finishTrack()
        settle()
        assertEquals(2, s.player.currentIndex)
        val reports = s.reports()
        assertEquals(listOf("start", "/Stopped", "start", "/Stopped", "start"), reports.map { it.first })
        assertTrue(reports[3].second.contains("\"PositionTicks\":1200000000"), reports[3].second) // track 2 = 120 s
    }

    @Test
    fun previousRestartsAfterThreeSeconds() = runTest {
        val s = setup()
        s.player.play(album, startTrackId = "t2")
        settle()
        s.engine.positionMillis = 10_000
        settle()
        s.player.seek(10.0)
        s.player.skipToPrevious()
        assertEquals(1, s.player.currentIndex, "same track, from the start")
        assertEquals(0L, s.engine.seeks.last())
        s.player.skipToPrevious()
        assertEquals(0, s.player.currentIndex)
    }

    @Test
    fun repeatAllWrapsAndUpNextFollows() = runTest {
        val s = setup(tracks = 3)
        s.player.play(album, startTrackId = "t2")
        settle()
        assertEquals(listOf("t3"), s.player.upNext.map { it.second.id })
        s.player.cycleRepeatMode()
        assertEquals(RepeatMode.ALL, s.engine.repeat)
        assertEquals(listOf("t3", "t1"), s.player.upNext.map { it.second.id })
        s.player.skipTo(2)
        s.player.skipToNext()
        assertEquals(0, s.player.currentIndex)
        s.player.cycleRepeatMode()
        assertEquals(RepeatMode.ONE, s.player.repeatMode)
        s.player.cycleRepeatMode()
        assertEquals(RepeatMode.OFF, s.player.repeatMode)
    }

    @Test
    fun albumLengthAndTimeLeft() = runTest {
        val s = setup(tracks = 3) // 60 + 120 + 180 s
        s.player.play(album, startTrackId = "t2")
        settle()
        assertEquals(360.0, s.player.queueLength)
        assertEquals(300.0, s.player.queueRemaining)
        assertEquals("6m 00s", formatAlbumDuration(s.player.queueLength))
    }

    @Test
    fun convertedTracksSayWhatsStreamed() = runTest {
        // FakeEngine plays FLAC/MP3/AAC as they are; ALAC is converted by the server.
        val server = FakeServer { request ->
            json("""{"Items":[{"Id":"t1","AlbumId":"al","RunTimeTicks":600000000,"MediaSources":[{"Container":"m4a","Bitrate":1057000,
                "MediaStreams":[{"Type":"Audio","Codec":"alac"}]}]}]}""")
        }
        val player = PlayerController(server.api(token = "t", userId = "u"), FakeEngine(), PlaybackSettings(InMemoryPreferences()), backgroundScope)
        player.play(album)
        settle()
        assertEquals("FLAC (from ALAC 1057 kbps)", player.currentTrack?.qualityLabel)
    }

    @Test
    fun qualityLabelShowsFormatAndBitrate() = runTest {
        val s = setup(container = "m4a")
        s.player.play(album)
        settle()
        // The test tracks say codec "m4a": an m4a with a codec named is AAC; no bitrate given.
        assertEquals("AAC", s.player.currentTrack?.qualityLabel)
        assertEquals("FLAC (from ALAC)", PlaybackTrack.convertedLabel(AudioFormat.ALAC, null))
        assertEquals("FLAC (from original)", PlaybackTrack.convertedLabel(null, null))
        assertEquals("ALAC 905 kbps", PlaybackTrack.qualityLabel(AudioFormat.ALAC, 905_123))
        assertNull(PlaybackTrack.qualityLabel(null, 320_000))
        val decoded = gg.dekiru.musicplayer.data.api.TetsubiJson.decodeFromString(
            JellyfinTrack.serializer(),
            """{"Id":"t","MediaSources":[{"Container":"flac","MediaStreams":[{"Type":"Audio","Codec":"flac","BitRate":1411200}]}]}""",
        )
        assertEquals(1_411_200, decoded.mediaSource?.bitrate)
        assertEquals("FLAC 1411 kbps", PlaybackTrack.from(decoded, album, 0).qualityLabel)
    }

    @Test
    fun meteredNetworkStreamsCappedAac() = runTest {
        val s = setup(metered = true)
        s.player.play(album)
        settle()
        assertEquals("AAC 256 kbps", s.player.currentTrack?.qualityLabel)
        val url = s.engine.items.first().url
        assertTrue("/Audio/t1/universal" in url && "AudioCodec=aac" in url && "MaxStreamingBitrate=256000" in url, url)
    }

    @Test
    fun normalisationOnlyWhenSwitchedOn() = runTest {
        val s = setup()
        s.player.play(album)
        settle()
        assertEquals(1f, s.engine.currentVolume)
        PlaybackSettings(s.prefs).normalizeVolume = true
        s.player.applyNormalization()
        assertEquals(0.501f, s.engine.currentVolume, 0.001f)
    }

    @Test
    fun failedLoadShowsAnError() = runTest {
        val server = FakeServer { json("", HttpStatusCode.InternalServerError) }
        val engine = FakeEngine()
        val player = PlayerController(server.api(token = "t", userId = "u"), engine, PlaybackSettings(InMemoryPreferences()), backgroundScope)
        player.play(album)
        settle()
        assertTrue(player.lastError!!.startsWith("HTTP 500"))
        assertFalse(player.isLoading)
        assertNull(player.currentTrack)
    }

    @Test
    fun unsupportedEngineDoesNothing() = runTest {
        val server = FakeServer { json("{}") }
        val player = PlayerController(server.api(token = "t", userId = "u"), FakeEngine(isSupported = false), PlaybackSettings(InMemoryPreferences()), backgroundScope)
        player.play(album)
        settle()
        assertTrue(server.requests.isEmpty())
        assertFalse(player.isNowPlayingOpen)
    }

    @Test
    fun finishingTheAlbumClosesNowPlaying() = runTest {
        val s = setup(tracks = 2)
        s.player.play(album)
        settle()
        assertTrue(s.player.isNowPlayingOpen)
        s.engine.finishTrack()
        s.engine.listener?.onEnded()
        settle()
        assertFalse(s.player.isNowPlayingOpen)
        assertFalse(s.player.isPlaying)
        // Back at track 1, 0:00, paused; the bar keeps the album.
        assertEquals("t1", s.player.currentTrack?.id)
        assertEquals(0, s.engine.index)
        assertEquals(0.0, s.player.position)
        assertFalse(s.engine.playing)
        assertEquals(listOf("start", "/Stopped", "start", "/Stopped"), s.reports().map { it.first }, "rewinding isn't a play")
        // Play starts the album again, and that is reported.
        s.player.resume()
        settle()
        assertTrue(s.engine.playing)
        assertEquals("start", s.reports().last().first)
        assertEquals(5, s.reports().size)
    }

    @Test
    fun finishingAOneTrackAlbumStaysOnIt() = runTest {
        val s = setup(tracks = 1)
        s.player.play(album)
        settle()
        s.engine.listener?.onEnded()
        settle()
        assertEquals("t1", s.player.currentTrack?.id)
        s.engine.skipTo(0) // a later, real move to the track is handled normally
        settle()
        assertEquals("start", s.reports().last().first)
    }

    @Test
    fun aFailedTrackStopsWithAMessageAndPlayRetries() = runTest {
        val s = setup()
        s.player.play(album)
        settle()
        s.engine.listener?.onError("Playback failed (ERROR_CODE_IO_NETWORK_CONNECTION_FAILED).")
        settle()
        assertFalse(s.player.isPlaying)
        assertFalse(s.engine.playing)
        assertTrue(s.player.trackFailed)
        assertEquals("Couldn't play \u201CSong 1\u201D. Check the connection to your server, then press Play to try again.", s.player.lastError)
        assertEquals(listOf("start", "/Stopped"), s.reports().map { it.first })
        s.player.resume()
        settle()
        assertTrue(s.engine.playing)
        assertFalse(s.player.trackFailed)
        assertNull(s.player.lastError)
        assertEquals("start", s.reports().last().first, "the retry is reported as a new play")
    }

    @Test
    fun stopClearsAndReports() = runTest {
        val s = setup()
        s.player.play(album)
        settle()
        s.player.stop()
        settle()
        assertNull(s.player.currentTrack)
        assertEquals("/Stopped", s.reports().last().first)
    }

    // Maniac Mode (ported from ManiacModeTests.swift)

    @Test
    fun maniacModeIsSavedAndOffByDefault() = runTest {
        val s = setup()
        assertFalse(s.player.maniacMode)
        s.player.changeManiacMode(true)
        assertTrue(PlaybackSettings(s.prefs).maniacMode)
        assertFalse(s.engine.skipEnabled, "lock screen and headset can't skip")
    }

    @Test
    fun maniacModeSwitchesShuffleAndRepeatOffAndBlocksThem() = runTest {
        val s = setup()
        s.player.changeShuffle(true)
        s.player.changeRepeatMode(RepeatMode.ONE)
        s.player.changeManiacMode(true)
        assertFalse(s.player.shuffle)
        assertEquals(RepeatMode.OFF, s.player.repeatMode)
        s.player.toggleShuffle()
        s.player.cycleRepeatMode()
        assertFalse(s.player.shuffle)
        assertEquals(RepeatMode.OFF, s.player.repeatMode)
        s.player.changeManiacMode(false)
        assertFalse(s.player.shuffle, "stays off after Maniac Mode ends")
        s.player.toggleShuffle()
        assertTrue(s.player.shuffle)
    }

    @Test
    fun maniacModeAlwaysStartsAtTrackOneAndCantSkipOrSeek() = runTest {
        val s = setup(maniac = true)
        s.player.play(album, startTrackId = "t3")
        settle()
        assertEquals(0, s.player.currentIndex)
        s.player.skipToNext()
        s.player.skipTo(3)
        s.player.seek(30.0)
        assertEquals(0, s.player.currentIndex)
        assertTrue(s.engine.seeks.isEmpty())
    }

    @Test
    fun maniacModeUnshufflesTheRest() = runTest {
        val s = setup(tracks = 6)
        s.player.toggleShuffle()
        s.player.play(album, startTrackId = "t4")
        settle()
        s.engine.finishTrack()
        val playing = s.player.currentTrack?.id
        s.player.changeManiacMode(true)
        // Changed with the listening queue: the whole queue goes back in album order (played ones too).
        assertEquals((1..6).map { "t$it" }, s.player.queue.map { it.id })
        assertEquals(s.player.queue.map { it.id }, s.engine.items.map { it.id })
        assertEquals(playing, s.player.currentTrack?.id)
        assertEquals(1, s.engine.loads, "the playing track carries on")
    }

    @Test
    fun albumOrderAfterCurrentKeepsWhatsPlayed() {
        // Each queue entry's place in the album: tracks 4, 2, 6, 1, 5, 3.
        val places = listOf(3, 1, 5, 0, 4, 2)
        assertEquals(listOf(3, 1, 0, 2, 4, 5), albumOrderAfterCurrent(places, 1).map { places[it] })
        assertEquals(listOf(0, 1, 2), albumOrderAfterCurrent(listOf(0, 1, 2), 2))
        assertEquals(listOf(0, 1, 3, 2), albumOrderAfterCurrent(listOf(4, 2, 2, 0), 1), "the same place twice keeps queue order")
    }

    // Queue planning (ported from PlayerManagerShuffleTests)

    @Test
    fun planQueueShuffleOffKeepsOrderAndStart() {
        val tracks = (0 until 10).toList()
        assertEquals(tracks to 4, planQueue(tracks, 4, shuffle = false))
        assertEquals(tracks to 0, planQueue(tracks, null, shuffle = false))
    }

    @Test
    fun planQueueShuffleOn() {
        val tracks = (0 until 20).toList()
        repeat(10) {
            val (out, start) = planQueue(tracks, 3, shuffle = true)
            assertEquals(0, start)
            assertEquals(3, out.first())
            assertEquals(tracks.toSet(), out.toSet())
        }
        val (all, _) = planQueue(tracks, 99, shuffle = true, random = Random(3))
        assertEquals(tracks.toSet(), all.toSet())
        assertTrue(all != tracks)
        assertEquals(emptyList<Int>() to 0, planQueue(emptyList<Int>(), null, shuffle = true))
    }

    @Test
    fun upNextIndicesEdges() {
        assertEquals(emptyList(), upNextIndices(0, 0, RepeatMode.ALL))
        assertEquals(listOf(1, 2), upNextIndices(3, 0, RepeatMode.ONE))
        assertEquals(emptyList(), upNextIndices(3, 2, RepeatMode.OFF))
    }

    // Formats, settings, URLs

    private fun jt(container: String, codec: String?) =
        JellyfinTrack(id = "t", mediaSource = MediaSource(container = container, codecs = codec))

    @Test
    fun sourceFormats() {
        assertEquals(AudioFormat.FLAC, AudioFormat.source(jt("flac", "flac")))
        assertEquals(AudioFormat.ALAC, AudioFormat.source(jt("m4a", "alac")))
        assertEquals(AudioFormat.AAC, AudioFormat.source(jt("m4a", "aac")))
        assertEquals(AudioFormat.OPUS, AudioFormat.source(jt("ogg", null)))
        assertEquals(AudioFormat.MP3, AudioFormat.source(jt("mp3", "mp3")))
        assertNull(AudioFormat.source(JellyfinTrack(id = "x")))
    }

    @Test
    fun downloadFormatNeverUpgrades() {
        assertEquals(AudioFormat.MP3, AudioFormat.downloadFormat(jt("mp3", "mp3"), preferred = null))
        assertEquals(AudioFormat.MP3, AudioFormat.downloadFormat(jt("mp3", "mp3"), preferred = AudioFormat.FLAC))
        assertEquals(AudioFormat.AAC, AudioFormat.downloadFormat(jt("flac", "flac"), preferred = AudioFormat.AAC))
        assertEquals(AudioFormat.ALAC, AudioFormat.downloadFormat(jt("flac", "flac"), preferred = AudioFormat.ALAC))
    }

    @Test
    fun streamUrls() {
        val api = FakeServer { json("") }.api(token = "secret", userId = "u1")
        val direct = setOf(AudioFormat.FLAC, AudioFormat.MP3)
        assertEquals(
            "http://jellyfin-box:8096/Audio/t/stream.flac?static=true",
            StreamUrls.url(api, jt("flac", "flac").copy(), direct),
        )
        val transcoded = StreamUrls.url(api, jt("m4a", "alac"), direct)
        assertTrue(transcoded.startsWith("http://jellyfin-box:8096/Audio/t/universal?UserId=u1&DeviceId=device-1&Container=flac"), transcoded)
        assertTrue("AudioCodec=flac" in transcoded)
        assertFalse("secret" in transcoded, "the token travels in the header")
        assertTrue("ApiKey=secret" in StreamUrls.url(api, jt("flac", "flac"), direct, withToken = true))
    }

    @Test
    fun convertedStreamsSeekByRestarting() {
        val api = FakeServer { json("") }.api(token = "t", userId = "u1")
        val converted = StreamUrls.url(api, jt("m4a", "alac"), setOf(AudioFormat.FLAC))
        val original = StreamUrls.url(api, jt("flac", "flac"), setOf(AudioFormat.FLAC))
        assertTrue(StreamUrls.isConverted(converted))
        assertFalse(StreamUrls.isConverted(original))
        val at90 = StreamUrls.startingAt(converted, 90_000)
        assertTrue(at90.endsWith("&StartTimeTicks=900000000"), at90)
        assertEquals(converted.length + "&StartTimeTicks=900000000".length, at90.length)
        assertEquals(StreamUrls.startingAt(converted, 30_000), StreamUrls.startingAt(at90, 30_000), "replaces, doesn't stack")
        assertEquals(converted, StreamUrls.startingAt(at90, 0))
        assertTrue(EngineItem("t", converted, "", "", "", "", 1).seeksByRestart)
    }

    @Test
    fun settingsDefaultsAndVolume() {
        val settings = PlaybackSettings(InMemoryPreferences())
        assertFalse(settings.maniacMode)
        assertEquals(256_000, settings.mobileDataBitrate)
        settings.cellularQuality = StreamingQuality.ORIGINAL
        assertNull(settings.mobileDataBitrate)
        assertEquals(1f, PlaybackSettings.volumeForGain(null))
        assertEquals(0.1f, PlaybackSettings.volumeForGain(-20.0), 0.0001f)
        assertEquals(1f, PlaybackSettings.volumeForGain(8.3), "no louder than full")
        assertEquals(1f, PlaybackSettings.volumeForGain(Double.NaN))
    }

    @Test
    fun theOldSwitchOffReadsAsOriginal() {
        val prefs = InMemoryPreferences()
        prefs.putString("playback.reduceOnMobileData", "false")
        val settings = PlaybackSettings(prefs)
        assertEquals(StreamingQuality.ORIGINAL, settings.cellularQuality)
        settings.cellularQuality = StreamingQuality.KBPS320
        assertEquals(StreamingQuality.KBPS320, settings.cellularQuality)
    }

    @Test
    fun durations() {
        assertEquals("47m 12s", formatAlbumDuration(47 * 60 + 12.0))
        assertEquals("1h 45m 05s", formatAlbumDuration(6305.0))
        assertEquals("4:17", formatTrackTime(257.0))
    }
}
