package gg.dekiru.musicplayer.playback

import gg.dekiru.musicplayer.data.api.FakeServer
import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import gg.dekiru.musicplayer.data.api.path
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinPerson
import gg.dekiru.musicplayer.data.models.JellyfinTrack
import gg.dekiru.musicplayer.data.models.MediaSource
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ALAC direct play where the device decodes it, its fallback, and the stall watchdog's decision. */
class DirectPlayAndStallTest {

    private val withAlac = DirectPlayCapabilities.forDevice(hasAlacDecoder = true)
    private val withoutAlac = DirectPlayCapabilities.forDevice(hasAlacDecoder = false)

    private fun jt(container: String, codec: String?) =
        JellyfinTrack(id = "t", mediaSource = MediaSource(container = container, codecs = codec))

    private val api = FakeServer { json("") }.api(token = "secret", userId = "u1")

    // ALAC capability

    @Test
    fun alacIsDirectOnlyWhereTheDeviceHasADecoder() {
        assertTrue(AudioFormat.ALAC in withAlac)
        assertFalse(AudioFormat.ALAC in withoutAlac)
        assertEquals(DirectPlayCapabilities.baseline, withoutAlac)
        assertEquals(DirectPlayCapabilities.baseline + AudioFormat.ALAC, withAlac)
        assertTrue(setOf(AudioFormat.FLAC, AudioFormat.MP3, AudioFormat.AAC, AudioFormat.OPUS, AudioFormat.WAV) == DirectPlayCapabilities.baseline)
    }

    @Test
    fun theProbeLooksForADecoderNotAnEncoder() {
        val mediaTek = listOf(
            DirectPlayCapabilities.Codec(false, listOf("audio/flac", "audio/mp4a-latm")),
            DirectPlayCapabilities.Codec(true, listOf("audio/mp4a-latm")),
        )
        val qualcomm = mediaTek + DirectPlayCapabilities.Codec(false, listOf("audio/ALAC"))
        assertFalse(DirectPlayCapabilities.hasDecoder("audio/alac", mediaTek))
        assertTrue(DirectPlayCapabilities.hasDecoder("audio/alac", qualcomm))
        assertFalse(DirectPlayCapabilities.hasDecoder("audio/alac", listOf(DirectPlayCapabilities.Codec(true, listOf("audio/alac")))))
        assertFalse(DirectPlayCapabilities.hasDecoder("audio/alac", emptyList()))
    }

    @Test
    fun oneFailedDirectAlacDecodeIsRememberedForThisBuildOnly() {
        val prefs = InMemoryPreferences()
        val first = AlacDirectFailure(prefs, build = 7)
        assertEquals(withAlac, DirectPlayCapabilities.forDevice(hasAlacDecoder = true, failure = first))
        first.record()
        assertEquals("7", prefs.getString("playback.alacDirectFailed"))
        // A later launch (a new object over the same settings) reads it at once: no ALAC direct play.
        val later = AlacDirectFailure(prefs, build = 7)
        assertTrue(later.failed)
        assertEquals(withoutAlac, DirectPlayCapabilities.forDevice(hasAlacDecoder = true, failure = later))
        // An updated app tries again.
        assertFalse(AlacDirectFailure(prefs, build = 8).failed)
        assertEquals(withAlac, DirectPlayCapabilities.forDevice(hasAlacDecoder = true, failure = AlacDirectFailure(prefs, build = 8)))
        // No decoder at all stays without ALAC either way.
        assertEquals(withoutAlac, DirectPlayCapabilities.forDevice(hasAlacDecoder = false, failure = AlacDirectFailure(InMemoryPreferences(), 7)))
    }

    @Test
    fun theDecodeFailureLineNamesTheDecoderAndTheCauses() {
        val line = DirectPlayFallback.describeFailure(
            listOf("Decoding failed", "Decoder failed: c2.qti.alac.sw.decoder", null),
            listOf("androidx.media3.exoplayer.ExoPlaybackException", "android.media.MediaCodec\$CodecException", "android.media.MediaCodec\$CodecException"),
        )
        assertEquals("decoder=c2.qti.alac.sw.decoder causes=androidx.media3.exoplayer.ExoPlaybackException>android.media.MediaCodec\$CodecException", line)
        assertEquals("decoder=other causes=none", DirectPlayFallback.describeFailure(emptyList(), emptyList(), codecName = "other"))
        assertEquals("decoder=unknown causes=X", DirectPlayFallback.describeFailure(listOf("nothing"), listOf("X")))
    }

    @Test
    fun alacInM4aStreamsTheOriginalWhenTheDeviceDecodesIt() {
        assertEquals("http://jellyfin-box:8096/Audio/t/stream.m4a?static=true", StreamUrls.url(api, jt("m4a", "alac"), withAlac))
        // ffprobe's MP4-family list is no file name: m4a, as Swift's plan treats "ALAC in the MP4 family".
        assertEquals(
            "http://jellyfin-box:8096/Audio/t/stream.m4a?static=true",
            StreamUrls.url(api, jt("mov,mp4,m4a,3gp,3g2,mj2", "alac"), withAlac),
        )
        // The same track on a device without a decoder: converted to FLAC, as before.
        val converted = StreamUrls.url(api, jt("m4a", "alac"), withoutAlac)
        assertTrue(StreamUrls.isConverted(converted) && "Container=flac" in converted, converted)
    }

    @Test
    fun aMobileCapStillConvertsAlac() {
        val url = StreamUrls.url(api, jt("m4a", "alac"), withAlac, maxBitrate = 256_000)
        assertTrue(StreamUrls.isConverted(url) && "AudioCodec=aac" in url && "MaxStreamingBitrate=256000" in url, url)
    }

    @Test
    fun castStillGetsFlacForAlac() {
        val url = StreamUrls.castUrl(api, jt("m4a", "alac"))
        assertTrue(StreamUrls.isConverted(url) && "Container=flac" in url && "ApiKey=secret" in url, url)
    }

    @Test
    fun fileExtensions() {
        assertEquals("flac", StreamUrls.fileExtension("flac"))
        assertEquals("m4a", StreamUrls.fileExtension("m4a"))
        assertEquals("m4a", StreamUrls.fileExtension("mov,mp4,m4a,3gp,3g2,mj2"))
        assertEquals("ogg", StreamUrls.fileExtension("ogg,opus"))
    }

    // Fallback

    private val alacDirect = EngineItem(
        "t", "http://server/Audio/t/stream.m4a?static=true", "", "", "", "", 240_000,
        directFallbackUrl = "http://server/Audio/t/universal?UserId=u&Container=flac&AudioCodec=flac",
    )

    @Test
    fun aDirectStreamThatCantBeDecodedFallsBackOnce() {
        for (code in listOf(3001, 3003, 4001, 4002, 4003, 4004, 4005)) {
            assertTrue(DirectPlayFallback.shouldFallBack(code, alacDirect, casting = false), "$code")
        }
        // A dropped connection or a refused request isn't a decoding problem.
        for (code in listOf(1000, 2000, 2001, 2002, 2004, 2005)) {
            assertFalse(DirectPlayFallback.shouldFallBack(code, alacDirect, casting = false), "$code")
        }
        assertFalse(DirectPlayFallback.shouldFallBack(4001, alacDirect, casting = true))
        assertFalse(DirectPlayFallback.shouldFallBack(4001, null, casting = false))
        // Nothing to fall back to: a direct stream of another format.
        assertFalse(DirectPlayFallback.shouldFallBack(4001, alacDirect.copy(directFallbackUrl = null), casting = false))
    }

    @Test
    fun theFallbackItemHasNoFallbackLeftSoItCannotLoop() {
        val fallback = DirectPlayFallback.converted(alacDirect)
        assertTrue(fallback.seeksByRestart)
        assertNull(fallback.directFallbackUrl)
        assertEquals(alacDirect.entryId, fallback.entryId)
        assertFalse(DirectPlayFallback.shouldFallBack(4001, fallback, casting = false))
        // A converted stream's own decode error is reported, not retried.
        assertFalse(DirectPlayFallback.shouldFallBack(4003, alacDirect.copy(url = alacDirect.directFallbackUrl!!), casting = false))
    }

    private fun alacServer() = FakeServer { request ->
        when (request.path) {
            "/Items" -> json(
                """{"Items":[{"Id":"t1","Name":"Song","IndexNumber":1,"AlbumId":"al","RunTimeTicks":600000000,
                    "AlbumArtists":[{"Name":"Band"}],"MediaSources":[{"Container":"m4a","Codecs":"alac"}]},
                    {"Id":"t2","Name":"Other","IndexNumber":2,"AlbumId":"al","RunTimeTicks":600000000,
                    "AlbumArtists":[{"Name":"Band"}],"MediaSources":[{"Container":"flac","Codecs":"flac"}]}]}""",
            )
            else -> json("")
        }
    }

    private val album = JellyfinAlbum(id = "al", name = "Album", albumArtists = listOf(JellyfinPerson(name = "Band")))

    @Test
    fun theControllerGivesAnAlacStreamItsConvertedFallback() = runTest {
        val engine = FakeEngine(directPlay = withAlac)
        val player = PlayerController(alacServer().api(token = "tok", userId = "u1"), engine, PlaybackSettings(InMemoryPreferences()), backgroundScope)
        player.play(album)
        settle()
        val alac = engine.items[0]
        assertTrue("stream.m4a" in alac.url && !alac.seeksByRestart, alac.url)
        val fallback = assertNotNull(alac.directFallbackUrl)
        assertTrue(StreamUrls.isConverted(fallback) && "Container=flac" in fallback, fallback)
        assertNull(engine.items[1].directFallbackUrl, "a FLAC file has nothing to fall back to")
    }

    @Test
    fun noFallbackWhereAlacIsAlreadyConverted() = runTest {
        val engine = FakeEngine()
        val player = PlayerController(alacServer().api(token = "tok", userId = "u1"), engine, PlaybackSettings(InMemoryPreferences()), backgroundScope)
        player.play(album)
        settle()
        assertTrue(engine.items[0].seeksByRestart)
        assertNull(engine.items[0].directFallbackUrl)
    }

    @Test
    fun noFallbackUnderAMobileCap() = runTest {
        val engine = FakeEngine(directPlay = withAlac)
        val player = PlayerController(
            alacServer().api(token = "tok", userId = "u1"), engine, PlaybackSettings(InMemoryPreferences()), backgroundScope,
            isExpensiveNetwork = { true },
        )
        player.play(album)
        settle()
        assertTrue(engine.items[0].seeksByRestart)
        assertNull(engine.items[0].directFallbackUrl)
    }

    // Stall watchdog

    private fun decide(
        playing: Boolean = true,
        loading: Boolean = true,
        converted: Boolean = true,
        ahead: Long = 30_000,
        quiet: Long = 15_000,
        reachedEnd: Boolean = false,
        busy: Boolean = false,
        restarts: Int = 0,
    ) = StreamStall.decide(playing, loading, converted, ahead, quiet, reachedEnd, busy, restarts)

    @Test
    fun aQuietStreamWithAFallingBufferIsRestarted() {
        assertEquals(StreamStall.Decision.RESTART, decide())
        // The logged case: 33 s ahead, nothing for 19 s.
        assertEquals(StreamStall.Decision.RESTART, decide(ahead = 33_000, quiet = 19_000))
        // Exactly at the thresholds.
        assertEquals(StreamStall.Decision.RESTART, decide(ahead = StreamStall.BUFFER_LOW_MILLIS - 1, quiet = StreamStall.STALL_AFTER_MILLIS))
        // Already underrunning after the buffer emptied: still stalled.
        assertEquals(StreamStall.Decision.RESTART, decide(ahead = 0, quiet = 20_000))
    }

    @Test
    fun aHealthyStreamIsLeftAlone() {
        // A full buffer that stopped loading, or one that grew a moment ago.
        assertEquals(StreamStall.Decision.WAIT, decide(ahead = 50_000, quiet = 30_000))
        assertEquals(StreamStall.Decision.WAIT, decide(ahead = StreamStall.BUFFER_LOW_MILLIS, quiet = 30_000))
        assertEquals(StreamStall.Decision.WAIT, decide(ahead = 20_000, quiet = StreamStall.STALL_AFTER_MILLIS - 1))
        assertEquals(StreamStall.Decision.WAIT, decide(ahead = 20_000, quiet = 0))
    }

    @Test
    fun neverDuringPausesStartsOrTheEndOfTheTrack() {
        assertEquals(StreamStall.Decision.WAIT, decide(playing = false), "paused, or a track that hasn't sounded yet")
        assertEquals(StreamStall.Decision.WAIT, decide(converted = false), "a direct stream resumes by byte range")
        assertEquals(StreamStall.Decision.WAIT, decide(loading = false), "the stream was read to its end")
        assertEquals(StreamStall.Decision.WAIT, decide(reachedEnd = true))
    }

    @Test
    fun neverWhileAReconnectIsBusyAndNeverInALoop() {
        assertEquals(StreamStall.Decision.WAIT, decide(busy = true))
        assertEquals(StreamStall.Decision.RESTART, decide(restarts = StreamStall.MAX_RESTARTS - 1))
        assertEquals(StreamStall.Decision.WAIT, decide(restarts = StreamStall.MAX_RESTARTS))
    }

    @Test
    fun reachingTheEndIsMeasuredAgainstTheTracksLength() {
        assertFalse(StreamStall.reachedEnd(100_000, 240_000))
        assertTrue(StreamStall.reachedEnd(237_000, 240_000))
        assertTrue(StreamStall.reachedEnd(240_000, 240_000))
        assertFalse(StreamStall.reachedEnd(500_000, 0), "no known length")
    }

    @Test
    fun growthIsTimedFromTheLastIncrease() {
        val growth = BufferGrowth()
        assertEquals(0, growth.sinceGrowth("a@0", 10_000, now = 1_000))
        assertEquals(2_000, growth.sinceGrowth("a@0", 10_000, now = 3_000))
        assertEquals(0, growth.sinceGrowth("a@0", 14_000, now = 4_000), "grew")
        assertEquals(6_000, growth.sinceGrowth("a@0", 14_000, now = 10_000))
        // A seek or restart (another key) and a flushed buffer (the position falls) start again.
        assertEquals(0, growth.sinceGrowth("a@60000", 14_000, now = 11_000))
        assertEquals(0, growth.sinceGrowth("a@60000", 2_000, now = 12_000))
        assertEquals(5_000, growth.sinceGrowth("a@60000", 2_000, now = 17_000))
        growth.reset()
        assertEquals(0, growth.sinceGrowth("a@60000", 2_000, now = 30_000))
    }

    @Test
    fun restartsAreCountedPerItemWithinAWindow() {
        val restarts = StallRestarts()
        restarts.record("e1", 0)
        restarts.record("e1", 60_000)
        restarts.record("e2", 70_000)
        assertEquals(2, restarts.recent("e1", 100_000))
        assertEquals(1, restarts.recent("e2", 100_000))
        // The first falls out of the window.
        assertEquals(1, restarts.recent("e1", StreamStall.RESTART_WINDOW_MILLIS + 1_000))
        restarts.clear()
        assertEquals(0, restarts.recent("e1", 100_000))
    }

    @Test
    fun theWatchdogLooksBeforeTheBufferRunsDry() {
        // The stall at ~50 s ahead reaches the threshold well before the buffer is empty.
        val stalledAt = 50_000L
        val firesAfter = StreamStall.STALL_AFTER_MILLIS
        assertTrue(stalledAt - firesAfter - StreamStall.POLL_MILLIS > 15_000)
        assertTrue(StreamStall.BUFFER_LOW_MILLIS < 44_000, "above what a healthy stream dips to between loads")
    }

    @Test
    fun thePlanSaysWhyAStreamIsDirectOrConverted() {
        val alac = jt("m4a", "alac")
        assertEquals(StreamUrls.Plan(StreamUrls.Plan.Kind.DIRECT, listOf("original")), StreamUrls.plan(alac, withAlac, null))
        assertEquals(StreamUrls.Plan(StreamUrls.Plan.Kind.CONVERTED, listOf("notDirect:ALAC")), StreamUrls.plan(alac, withoutAlac, null))
        assertEquals(StreamUrls.Plan(StreamUrls.Plan.Kind.CAPPED, listOf("cap")), StreamUrls.plan(alac, withAlac, 128_000))
        assertEquals(StreamUrls.Plan(StreamUrls.Plan.Kind.CONVERTED, listOf("noContainer")), StreamUrls.plan(jt("", "alac"), withAlac, null))
        assertEquals(StreamUrls.Plan(StreamUrls.Plan.Kind.CONVERTED, listOf("noSourceInfo")), StreamUrls.plan(JellyfinTrack(id = "t"), withAlac, null))
        // ffprobe's MP4-family container list.
        assertEquals(StreamUrls.Plan.Kind.DIRECT, StreamUrls.plan(jt("mov,mp4,m4a,3gp,3g2,mj2", "alac"), withAlac, null).kind)
    }

    @Test
    fun thePlanLineHasIdentifiersAndFormatsOnly() {
        val track = jt("m4a", "alac")
        val line = StreamUrls.planLine("t", track, StreamUrls.plan(track, withoutAlac, null), "alacDecoder=false alacFailed=false")
        assertEquals("play plan: item=t codec=alac container=m4a plan=converted reasons=notDirect:ALAC alacDecoder=false alacFailed=false", line)
        assertEquals("play plan: item=t plan=file reasons=downloaded format=flac", StreamUrls.fileLine("t", "/data/x/01 - A.FLAC"))
    }

    @Test
    fun downloadedAndConvertedLabelsDiffer() {
        assertEquals("FLAC · downloaded", PlaybackTrack.downloadedLabel("FLAC"))
        assertEquals("Downloaded", PlaybackTrack.downloadedLabel(null))
        assertEquals("FLAC (from ALAC)", PlaybackTrack.convertedLabel(AudioFormat.ALAC, null))
    }
}
