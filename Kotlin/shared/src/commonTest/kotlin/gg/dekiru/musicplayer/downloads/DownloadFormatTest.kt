package gg.dekiru.musicplayer.downloads

import gg.dekiru.musicplayer.data.api.FakeServer
import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import gg.dekiru.musicplayer.data.models.JellyfinTrack
import gg.dekiru.musicplayer.data.models.MediaSource
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import gg.dekiru.musicplayer.playback.AlacDirectFailure
import gg.dekiru.musicplayer.playback.AudioFormat
import gg.dekiru.musicplayer.playback.DirectPlayCapabilities
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a track is downloaded as on Android: source format x preference x capability (and the ALAC failure flag). */
class DownloadFormatTest {

    private val api = FakeServer { json("") }.api(token = "tok", userId = "u1")

    private fun track(container: String, codec: String?) =
        JellyfinTrack(id = "t", mediaSource = MediaSource(container = container, codecs = codec))

    private val sources = mapOf(
        "flac" to track("flac", "flac"),
        "alac" to track("m4a", "alac"),
        "alacList" to track("mov,mp4,m4a,3gp,3g2,mj2", "alac"),
        "aac" to track("m4a", "aac"),
        "mp3" to track("mp3", "mp3"),
        "wav" to track("wav", "pcm_s16le"),
    )

    private val withAlac = DirectPlayCapabilities.android
    private val afterFailure = DirectPlayCapabilities.forDevice(AlacDirectFailure(InMemoryPreferences(), 7).also { it.record() })

    private fun TestScopeManager(caps: Set<AudioFormat>, scope: kotlinx.coroutines.CoroutineScope) =
        DownloadManager(api, tempDir(), DownloadSettings(InMemoryPreferences()), scope, playable = DirectPlayCapabilities.android, downloadable = { caps })

    private fun DownloadManager.stored(source: String, preferred: AudioFormat?): AudioFormat {
        val t = sources.getValue(source)
        return storedFormat(AudioFormat.downloadFormat(t, preferred), t)
    }

    @Test
    fun theOriginalFileIsKeptWheneverThePlayerPlaysIt() = runTest {
        val m = TestScopeManager(withAlac, backgroundScope)
        assertEquals(AudioFormat.FLAC, m.stored("flac", null))
        assertEquals(AudioFormat.ALAC, m.stored("alac", null))
        assertEquals(AudioFormat.ALAC, m.stored("alacList", null))
        assertEquals(AudioFormat.AAC, m.stored("aac", null))
        assertEquals(AudioFormat.MP3, m.stored("mp3", null))
        assertEquals(AudioFormat.WAV, m.stored("wav", null))
        // "ALAC" chosen: a real ALAC source is the original; a FLAC source would need a live conversion to MP4: FLAC instead.
        assertEquals(AudioFormat.ALAC, m.stored("alac", AudioFormat.ALAC))
        assertEquals(AudioFormat.FLAC, m.stored("flac", AudioFormat.ALAC))
        assertEquals(AudioFormat.FLAC, m.stored("wav", AudioFormat.ALAC))
        // "FLAC" chosen from an ALAC source is still a conversion; lossy sources are never converted up.
        assertEquals(AudioFormat.FLAC, m.stored("alac", AudioFormat.FLAC))
        assertEquals(AudioFormat.MP3, m.stored("mp3", AudioFormat.ALAC))
        assertEquals(AudioFormat.AAC, m.stored("aac", AudioFormat.FLAC))
    }

    @Test
    fun afterADirectAlacFailureOnThisBuildAlacIsConvertedToFlacAsBefore() = runTest {
        assertFalse(AudioFormat.ALAC in afterFailure)
        val m = TestScopeManager(afterFailure, backgroundScope)
        assertEquals(AudioFormat.FLAC, m.stored("alac", null))
        assertEquals(AudioFormat.FLAC, m.stored("alacList", null))
        assertEquals(AudioFormat.FLAC, m.stored("alac", AudioFormat.ALAC))
        // Everything else is unchanged.
        assertEquals(AudioFormat.FLAC, m.stored("flac", null))
        assertEquals(AudioFormat.AAC, m.stored("aac", null))
        assertEquals(AudioFormat.MP3, m.stored("mp3", null))
        assertEquals(AudioFormat.WAV, m.stored("wav", null))
    }

    @Test
    fun anOriginalAlacIsFetchedAsTheStaticFileAndAConversionIsNot() {
        for (key in listOf("alac", "alacList")) {
            val t = sources.getValue(key)
            assertTrue(DownloadUrls.isOriginal(t, AudioFormat.ALAC))
            assertEquals("http://jellyfin-box:8096/Audio/t/stream.m4a?static=true", DownloadUrls.url(api, t, AudioFormat.ALAC))
            // The file is not AAC, even though both live in m4a.
            assertFalse(DownloadUrls.isOriginal(t, AudioFormat.AAC))
        }
        assertFalse(DownloadUrls.isOriginal(sources.getValue("aac"), AudioFormat.ALAC))
        assertTrue(DownloadUrls.isOriginal(sources.getValue("aac"), AudioFormat.AAC))
        // MP4 containers (mp4, m4b) are the same family.
        assertTrue(DownloadUrls.isOriginal(track("mp4", "alac"), AudioFormat.ALAC))
        assertTrue("/universal?" in DownloadUrls.url(api, sources.getValue("alac"), AudioFormat.FLAC))
    }

    @Test
    fun theSavedExtensionFollowsTheFormatAndAFileOnDiskIsNamedByWhatItIs() {
        assertEquals("01 - A.m4a", DownloadPaths.trackFileName(1, "A", AudioFormat.ALAC))
        assertEquals("01 - A.m4a", DownloadPaths.trackFileName(1, "A", AudioFormat.AAC))
        assertEquals("01 - A.flac", DownloadPaths.trackFileName(1, "A", AudioFormat.FLAC))
        assertEquals(AudioFormat.FLAC, DownloadPaths.formatOfFile("01 - A.flac", AudioFormat.ALAC))
        assertEquals(AudioFormat.ALAC, DownloadPaths.formatOfFile("01 - A.m4a", AudioFormat.FLAC))
        // m4a is shared by ALAC and AAC: the planned one stands.
        assertEquals(AudioFormat.AAC, DownloadPaths.formatOfFile("01 - A.m4a", AudioFormat.AAC))
        assertEquals(AudioFormat.ALAC, DownloadPaths.formatOfFile("01 - A.m4a", AudioFormat.ALAC))
        assertEquals(AudioFormat.FLAC, DownloadPaths.formatOfFile("01 - A.xyz", AudioFormat.FLAC))
    }
}
