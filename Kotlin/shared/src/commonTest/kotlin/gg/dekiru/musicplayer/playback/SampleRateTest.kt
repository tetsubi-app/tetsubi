package gg.dekiru.musicplayer.playback

import gg.dekiru.musicplayer.data.api.FakeServer
import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import gg.dekiru.musicplayer.data.api.TetsubiJson
import gg.dekiru.musicplayer.data.api.path
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinPerson
import gg.dekiru.musicplayer.data.models.JellyfinTrack
import gg.dekiru.musicplayer.data.models.MediaSource
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import gg.dekiru.musicplayer.ui.nowplaying.QualityDetailSetting
import gg.dekiru.musicplayer.ui.nowplaying.QualityText
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The sample rate and bit depth on Now Playing ("44.1kHz/16-bit"), and the tap that switches to format and bitrate. */
class SampleRateTest {

    private val album = JellyfinAlbum(id = "al", name = "Album", albumArtists = listOf(JellyfinPerson(name = "Band")))

    private fun decode(mediaSource: String) =
        TetsubiJson.decodeFromString(JellyfinTrack.serializer(), """{"Id":"t","MediaSources":[$mediaSource]}""")

    @Test
    fun decodesTheAudioStreamsSampleRateAndBitDepth() {
        val t = decode(
            """{"Container":"flac","MediaStreams":[
                {"Type":"Video","Codec":"mjpeg","BitDepth":8},
                {"Type":"Audio","Codec":"flac","BitRate":1411200,"SampleRate":44100,"BitDepth":16}]}""",
        )
        assertEquals(44_100, t.mediaSource?.sampleRate)
        assertEquals(16, t.mediaSource?.bitDepth)
        assertEquals("44.1kHz/16-bit", PlaybackTrack.from(t, album, 0).sampleRateLabel)
    }

    @Test
    fun lossyFilesHaveNoBitDepthAndMissingValuesStayNull() {
        val mp3 = decode("""{"Container":"mp3","MediaStreams":[{"Type":"Audio","Codec":"mp3","SampleRate":48000}]}""")
        assertEquals(48_000, mp3.mediaSource?.sampleRate)
        assertNull(mp3.mediaSource?.bitDepth)
        assertEquals("48kHz", PlaybackTrack.from(mp3, album, 0).sampleRateLabel)

        val bare = decode("""{"Container":"flac","MediaStreams":[{"Type":"Audio","Codec":"flac","SampleRate":0,"BitDepth":0}]}""")
        assertNull(bare.mediaSource?.sampleRate)
        assertNull(bare.mediaSource?.bitDepth)
        assertNull(PlaybackTrack.from(bare, album, 0).sampleRateLabel)
        assertNull(decode("""{"Container":"flac"}""").mediaSource?.sampleRate)
    }

    @Test
    fun sampleRateAndBitDepthSurviveARoundTrip() {
        val source = MediaSource(container = "flac", codecs = "flac", bitrate = 900_000, sampleRate = 96_000, bitDepth = 24)
        val text = TetsubiJson.encodeToString(MediaSource.serializer(), source)
        assertEquals(source, TetsubiJson.decodeFromString(MediaSource.serializer(), text))
    }

    @Test
    fun labelFormatting() {
        assertEquals("44.1kHz/16-bit", PlaybackTrack.sampleRateLabel(44_100, 16))
        assertEquals("48kHz/24-bit", PlaybackTrack.sampleRateLabel(48_000, 24))
        assertEquals("88.2kHz/24-bit", PlaybackTrack.sampleRateLabel(88_200, 24))
        assertEquals("96kHz/24-bit", PlaybackTrack.sampleRateLabel(96_000, 24))
        assertEquals("192kHz/24-bit", PlaybackTrack.sampleRateLabel(192_000, 24))
        assertEquals("22.05kHz/16-bit", PlaybackTrack.sampleRateLabel(22_050, 16))
        assertEquals("11.03kHz", PlaybackTrack.sampleRateLabel(11_025, null))
        assertEquals("8kHz", PlaybackTrack.sampleRateLabel(8_000, null))
        assertEquals("44.1kHz", PlaybackTrack.sampleRateLabel(44_100, null))
        assertEquals("44.1kHz", PlaybackTrack.sampleRateLabel(44_100, 0))
        assertNull(PlaybackTrack.sampleRateLabel(null, 16))
        assertNull(PlaybackTrack.sampleRateLabel(0, 16))
    }

    private fun TestScope.player(metered: Boolean): PlayerController {
        val server = FakeServer { request ->
            when (request.path) {
                "/Items" -> json(
                    """{"Items":[{"Id":"t1","AlbumId":"al","RunTimeTicks":600000000,"MediaSources":[{"Container":"flac","Bitrate":900000,
                    "MediaStreams":[{"Type":"Audio","Codec":"flac","SampleRate":96000,"BitDepth":24}]}]}]}""",
                )
                else -> json("")
            }
        }
        return PlayerController(
            server.api(token = "t", userId = "u"), FakeEngine(), PlaybackSettings(InMemoryPreferences()), backgroundScope,
            isExpensiveNetwork = { metered },
        )
    }

    @Test
    fun streamedTracksCarryTheFilesValues() = runTest {
        val p = player(metered = false)
        p.play(album)
        settle()
        assertEquals("96kHz/24-bit", p.currentTrack?.sampleRateLabel)
        assertEquals("FLAC 900 kbps", p.currentTrack?.qualityLabel)
    }

    @Test
    fun mobileDataCapKeepsTheRateButNotTheBitDepth() = runTest {
        val p = player(metered = true)
        p.play(album)
        settle()
        assertEquals("AAC 256 kbps", p.currentTrack?.qualityLabel)
        assertEquals("96kHz", p.currentTrack?.sampleRateLabel)
    }

    private fun track(rate: Int?, depth: Int?, quality: String?) = PlaybackTrack(
        id = "t", trackNumber = 1, title = "T", artist = "A", album = "Al", albumId = "al", durationSeconds = 60.0,
        qualityLabel = quality, sampleRate = rate, bitDepth = depth,
    )

    @Test
    fun shownTextAndFallback() {
        val both = track(44_100, 16, "FLAC 905 kbps")
        assertEquals(QualityText("44.1kHz/16-bit", switches = true), QualityText.of(both, showsDetail = false))
        assertEquals(QualityText("FLAC 905 kbps", switches = true), QualityText.of(both, showsDetail = true))
        // A download (no sample rate saved): the quality label as before, not switchable.
        val download = track(null, null, "FLAC 905 kbps")
        assertEquals(QualityText("FLAC 905 kbps", switches = false), QualityText.of(download, showsDetail = false))
        assertEquals(QualityText("FLAC 905 kbps", switches = false), QualityText.of(download, showsDetail = true))
        assertEquals(QualityText("48kHz", switches = false), QualityText.of(track(48_000, null, null), showsDetail = true))
        assertNull(QualityText.of(track(null, null, null), showsDetail = false))
        assertEquals("Shows the format and bitrate", QualityText.hint(showsDetail = false))
        assertEquals("Shows the sample rate and bit depth", QualityText.hint(showsDetail = true))
    }

    @Test
    fun theChoiceIsRemembered() {
        val prefs = InMemoryPreferences()
        val first = QualityDetailSetting(prefs)
        assertFalse(first.showsDetail, "the sample rate by default")
        first.toggle()
        assertTrue(first.showsDetail)
        assertEquals("true", prefs.getString(QualityDetailSetting.KEY))
        assertEquals("nowPlaying.qualityDetail", QualityDetailSetting.KEY)
        // The next launch.
        val next = QualityDetailSetting(prefs)
        assertTrue(next.showsDetail)
        next.toggle()
        assertFalse(QualityDetailSetting(prefs).showsDetail)
        // Without preferences it still switches.
        val unsaved = QualityDetailSetting(null)
        unsaved.toggle()
        assertTrue(unsaved.showsDetail)
    }
}
