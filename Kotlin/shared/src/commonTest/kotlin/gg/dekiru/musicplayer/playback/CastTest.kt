package gg.dekiru.musicplayer.playback

import gg.dekiru.musicplayer.data.api.FakeServer
import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import gg.dekiru.musicplayer.data.api.path
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinPerson
import gg.dekiru.musicplayer.data.models.JellyfinTrack
import gg.dekiru.musicplayer.data.models.MediaSource
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.downloads.DownloadSettings
import gg.dekiru.musicplayer.downloads.tempDir
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Google Cast standing in: devices on the network, and casting to `name` when it's set. */
class FakeCast(name: String? = null, devices: Boolean = true) : CastRoute {
    override val devicesAvailable = MutableStateFlow(devices)
    override val deviceName = MutableStateFlow(name)
}

/** Google Cast: the streams a Cast device plays, moving playback over and back, and the button. */
class CastTest {

    private val api = FakeServer { json("") }.api(token = "secret", userId = "u1")

    private fun jt(container: String, codec: String?, id: String = "t") =
        JellyfinTrack(id = id, mediaSource = MediaSource(container = container, codecs = codec))

    // Cast streams

    @Test
    fun formatsTheReceiverPlaysAreStreamedAsTheyAreWithTheToken() {
        assertEquals("http://jellyfin-box:8096/Audio/t/stream.flac?static=true&ApiKey=secret", StreamUrls.castUrl(api, jt("flac", "flac")))
        assertEquals("http://jellyfin-box:8096/Audio/t/stream.mp3?static=true&ApiKey=secret", StreamUrls.castUrl(api, jt("mp3", "mp3")))
        assertEquals("http://jellyfin-box:8096/Audio/t/stream.m4a?static=true&ApiKey=secret", StreamUrls.castUrl(api, jt("m4a", "aac")))
        assertEquals("http://jellyfin-box:8096/Audio/t/stream.wav?static=true&ApiKey=secret", StreamUrls.castUrl(api, jt("wav", "pcm_s16le")))
    }

    @Test
    fun otherFormatsAreConvertedToFlac() {
        for (source in listOf(jt("m4a", "alac"), jt("ogg", "opus"), jt("ape", "ape"))) {
            val url = StreamUrls.castUrl(api, source)
            assertTrue(url.startsWith("http://jellyfin-box:8096/Audio/t/universal?UserId=u1&DeviceId=device-1&Container=flac"), url)
            assertTrue("AudioCodec=flac" in url, url)
            assertTrue(url.endsWith("&ApiKey=secret"), url)
            assertTrue(StreamUrls.isConverted(url))
            assertEquals("audio/flac", StreamUrls.mimeType(url))
        }
    }

    @Test
    fun aTrackOfUnknownFormatIsConvertedToFlac() {
        val url = StreamUrls.castFallbackUrl(api, "al-t1")
        assertTrue(url.startsWith("http://jellyfin-box:8096/Audio/al-t1/universal?UserId=u1&DeviceId=device-1&Container=flac"), url)
        assertTrue("ApiKey=secret" in url)
        assertTrue(StreamUrls.isConverted(url))
    }

    @Test
    fun theTvsCoverCarriesTheToken() {
        assertEquals(
            "http://jellyfin-box:8096/Items/al/Images/Primary?maxWidth=600&quality=90&ApiKey=secret",
            StreamUrls.castArtworkUrl(api, "al"),
        )
        // The app's own picture URLs still don't.
        assertFalse("secret" in api.imageUrl("al", 600))
    }

    @Test
    fun mimeTypes() {
        assertEquals("audio/flac", StreamUrls.mimeType("http://s/Audio/t/stream.flac?static=true&ApiKey=x"))
        assertEquals("audio/mpeg", StreamUrls.mimeType("http://s/Audio/t/stream.mp3?static=true"))
        assertEquals("audio/mp4", StreamUrls.mimeType("http://s/Audio/t/stream.m4a?static=true"))
        assertEquals("audio/wav", StreamUrls.mimeType("http://s/Audio/t/stream.wav?static=true"))
        assertEquals("audio/mp4", StreamUrls.mimeType("http://s/Audio/t/universal?UserId=u&Container=m4a&TranscodingContainer=m4a"))
        assertEquals("audio/flac", StreamUrls.mimeType(StreamUrls.startingAt(StreamUrls.castFallbackUrl(api, "t"), 30_000)))
        assertNull(StreamUrls.mimeType("http://s/Audio/t/stream?static=true"))
    }

    // Moving playback over and back

    private fun item(i: Int, local: String, cast: String, seconds: Long = 200) = EngineItem(
        id = "t$i", url = local, title = "Song $i", artist = "Band", album = "Album",
        artworkUrl = "file:///art.jpg", durationMillis = seconds * 1000, castUrl = cast,
    )

    private val converted = StreamUrls.castFallbackUrl(api, "t2")

    /** Track 1 a file here, FLAC on the TV; track 2 converted both ways; track 3 a file here, MP3 on the TV. */
    private val queue = listOf(
        item(1, "file:///music/01.flac", "http://jellyfin-box:8096/Audio/t1/stream.flac?static=true&ApiKey=secret"),
        item(2, converted, converted),
        item(3, "file:///music/03.mp3", "http://jellyfin-box:8096/Audio/t3/stream.mp3?static=true&ApiKey=secret"),
    )

    @Test
    fun toTheCastDeviceTheQueueTrackAndPointCarryOver() {
        val plan = CastHandover.plan(queue, PlaybackSnapshot(index = 0, positionMillis = 83_000, playWhenReady = true, repeat = RepeatMode.ALL), toCast = true)!!
        assertEquals(queue.map { it.castUrl }, plan.urls, "every item from the server, downloads too")
        assertEquals(0, plan.startIndex)
        assertEquals(83_000, plan.startPositionMillis)
        assertEquals(0, plan.startOffsetMillis)
        assertTrue(plan.playWhenReady)
        assertEquals(RepeatMode.ALL, plan.repeat)
    }

    @Test
    fun backFromTheCastDeviceTheFilesPlayAgain() {
        val plan = CastHandover.plan(queue, PlaybackSnapshot(index = 2, positionMillis = 12_500, playWhenReady = false, repeat = RepeatMode.ONE), toCast = false)!!
        assertEquals(queue.map { it.url }, plan.urls)
        assertEquals(2, plan.startIndex)
        assertEquals(12_500, plan.startPositionMillis)
        assertFalse(plan.playWhenReady)
        assertEquals(RepeatMode.ONE, plan.repeat)
    }

    @Test
    fun aConvertedTrackRestartsAtThePointEitherWay() {
        for (toCast in listOf(true, false)) {
            val plan = CastHandover.plan(queue, PlaybackSnapshot(1, 95_000, true, RepeatMode.OFF), toCast)!!
            assertEquals(StreamUrls.startingAt(converted, 95_000), plan.urls[1])
            assertTrue("StartTimeTicks=950000000" in plan.urls[1])
            assertEquals(0, plan.startPositionMillis, "the restarted stream begins at its own 0:00")
            assertEquals(95_000, plan.startOffsetMillis)
            // The other tracks keep their own streams.
            assertEquals(queue[0].url(toCast), plan.urls[0])
            assertEquals(queue[2].url(toCast), plan.urls[2])
        }
    }

    @Test
    fun aConvertedTrackAtItsStartIsntRestarted() {
        val plan = CastHandover.plan(queue, PlaybackSnapshot(1, 0, true, RepeatMode.OFF), toCast = true)!!
        assertEquals(converted, plan.urls[1])
        assertEquals(0, plan.startOffsetMillis)
    }

    @Test
    fun theIndexAndPointAreKeptInRange() {
        val late = CastHandover.plan(queue, PlaybackSnapshot(7, 999_000, true, RepeatMode.OFF), toCast = true)!!
        assertEquals(2, late.startIndex)
        assertEquals(199_000, late.startPositionMillis, "a moment before the end, so the track plays out")
        val early = CastHandover.plan(queue, PlaybackSnapshot(-1, -50, true, RepeatMode.OFF), toCast = true)!!
        assertEquals(0, early.startIndex)
        assertEquals(0, early.startPositionMillis)
        assertNull(CastHandover.plan(emptyList(), PlaybackSnapshot(0, 0, true, RepeatMode.OFF), toCast = true))
    }

    @Test
    fun withoutACastStreamAnItemKeepsItsOwn() {
        val plain = queue[0].copy(castUrl = null)
        assertEquals(plain.url, plain.url(casting = true))
    }

    // The button

    @Test
    fun theButtonShowsOnlineWithADeviceOrWhileCasting() {
        assertTrue(CastRoute.showsButton(online = true, devicesAvailable = true, casting = false))
        assertTrue(CastRoute.showsButton(online = true, devicesAvailable = false, casting = true))
        assertFalse(CastRoute.showsButton(online = true, devicesAvailable = false, casting = false), "no Cast devices on the network")
        assertFalse(CastRoute.showsButton(online = false, devicesAvailable = true, casting = false), "offline")
        assertFalse(CastRoute.showsButton(online = false, devicesAvailable = true, casting = true), "offline")
        assertEquals("Playing on Living Room TV", CastRoute.playingOn("Living Room TV"))
    }

    // The controller fills the Cast streams in

    private fun track(i: Int, album: String, container: String, codec: String) = """
        {"Id":"$album-t$i","Name":"Song $i","IndexNumber":$i,"AlbumId":"$album","RunTimeTicks":${i * 600_000_000L},
         "AlbumArtists":[{"Name":"Band"}],"MediaSources":[{"Container":"$container","Codecs":"$codec"}]}
    """

    private val formats = listOf("flac" to "flac", "m4a" to "alac", "mp3" to "mp3")

    private class Setup(val server: FakeServer, val engine: FakeEngine, val player: PlayerController)

    private fun TestScope.setup(cast: CastRoute?, metered: Boolean = false, downloadsDir: String? = null): Setup {
        val server = FakeServer { request ->
            when (request.path) {
                "/Items" -> {
                    val parent = request.url.parameters["ParentId"]!!
                    json("""{"Items":[${formats.withIndex().joinToString(",") { (i, f) -> track(i + 1, parent, f.first, f.second) }}]}""")
                }
                else -> json("")
            }
        }
        val api = server.api(token = "tok", userId = "u1")
        val prefs = InMemoryPreferences()
        val manager by lazy { DownloadManager(api, downloadsDir!!, DownloadSettings(prefs), backgroundScope) }
        val engine = FakeEngine(cast = cast)
        val player = PlayerController(
            api, engine, PlaybackSettings(prefs), backgroundScope,
            isExpensiveNetwork = { metered },
            downloads = { if (downloadsDir != null) manager else null },
            random = Random(1),
        )
        return Setup(server, engine, player)
    }

    private val album = JellyfinAlbum(id = "al", name = "Album", albumArtists = listOf(JellyfinPerson(name = "Band")))

    @Test
    fun streamedTracksGetCastStreams() = runTest {
        val s = setup(FakeCast())
        s.player.play(album)
        settle()
        val (flac, alac, mp3) = s.engine.items
        assertEquals("http://jellyfin-box:8096/Audio/al-t1/stream.flac?static=true&ApiKey=tok", flac.castUrl)
        assertTrue(alac.castUrl!!.contains("/Audio/al-t2/universal?") && "ApiKey=tok" in alac.castUrl!!)
        assertEquals("http://jellyfin-box:8096/Audio/al-t3/stream.mp3?static=true&ApiKey=tok", mp3.castUrl)
        assertEquals("http://jellyfin-box:8096/Items/al/Images/Primary?maxWidth=600&quality=90&ApiKey=tok", flac.castArtworkUrl)
        // This device's own streams still carry no token.
        assertFalse("tok" in flac.url)
    }

    @Test
    fun theMobileDataCapIsntPutOnTheTv() = runTest {
        val s = setup(FakeCast(), metered = true)
        s.player.play(album)
        settle()
        val first = s.engine.items[0]
        assertTrue("MaxStreamingBitrate" in first.url, "capped here")
        assertEquals("http://jellyfin-box:8096/Audio/al-t1/stream.flac?static=true&ApiKey=tok", first.castUrl)
    }

    @Test
    fun withoutCastThereAreNoCastStreams() = runTest {
        val s = setup(cast = null)
        s.player.play(album)
        settle()
        assertTrue(s.engine.items.all { it.castUrl == null && it.castArtworkUrl == null })
    }

    @Test
    fun aDownloadedAlbumAsksTheServerForItsCastStreamsAlongside() = runTest {
        val dir = tempDir()
        writeDownload(dir, "al", "Album")
        val s = setup(FakeCast(), downloadsDir = dir)
        s.player.play(album)
        // Plays from the files straight away, with streams the TV can always play.
        assertEquals("file://$dir/al/01 - Song 1 Live.flac", s.engine.items[0].url)
        assertTrue(s.engine.items.all { StreamUrls.isConverted(it.castUrl!!) })
        settle()
        // Then the server's formats: FLAC and MP3 as they are, ALAC converted.
        assertEquals(1, s.engine.castUpdates.size)
        assertEquals("http://jellyfin-box:8096/Audio/al-t1/stream.flac?static=true&ApiKey=tok", s.engine.items[0].castUrl)
        assertTrue(StreamUrls.isConverted(s.engine.items[1].castUrl!!))
        assertEquals("http://jellyfin-box:8096/Audio/al-t3/stream.mp3?static=true&ApiKey=tok", s.engine.items[2].castUrl)
        assertEquals("file://$dir/al/01 - Song 1 Live.flac", s.engine.items[0].url, "still playing the files here")
    }

    @Test
    fun whileCastingADownloadedAlbumWaitsForItsCastStreams() = runTest {
        val dir = tempDir()
        writeDownload(dir, "al", "Album")
        val s = setup(FakeCast(name = "Living Room TV"), downloadsDir = dir)
        s.player.play(album)
        settle()
        assertTrue(s.engine.castUpdates.isEmpty())
        assertEquals("http://jellyfin-box:8096/Audio/al-t1/stream.flac?static=true&ApiKey=tok", s.engine.items[0].castUrl)
        assertEquals("http://jellyfin-box:8096/Audio/al-t3/stream.mp3?static=true&ApiKey=tok", s.engine.items[2].castUrl)
        assertTrue(s.engine.playing)
        assertFalse(s.player.isLoading)
    }
}
