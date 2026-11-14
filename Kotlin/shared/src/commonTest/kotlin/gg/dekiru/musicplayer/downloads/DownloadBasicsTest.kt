package gg.dekiru.musicplayer.downloads

import gg.dekiru.musicplayer.data.api.FakeServer
import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import gg.dekiru.musicplayer.data.models.JellyfinTrack
import gg.dekiru.musicplayer.data.models.MediaSource
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import gg.dekiru.musicplayer.playback.AudioFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadBasicsTest {

    private fun track(id: String, index: Int?, disc: Int? = null, name: String = "Song", container: String? = "flac", codec: String? = container) =
        JellyfinTrack(id = id, name = name, indexNumber = index, discNumber = disc, mediaSource = container?.let { MediaSource(container = it, codecs = codec) })

    // Numbering

    @Test
    fun taggedTracksKeepTheirNumbers() {
        val tracks = listOf(track("a", 1), track("b", 2), track("c", 5))
        assertEquals(listOf(1, 2, 5), JellyfinTrack.fileNumbers(tracks))
    }

    @Test
    fun discsAfterTheFirstCountInHundreds() {
        val tracks = listOf(track("a", 1, disc = 1), track("b", 2, disc = 1), track("c", 1, disc = 2), track("d", 2, disc = 2))
        assertEquals(listOf(1, 2, 201, 202), JellyfinTrack.fileNumbers(tracks))
    }

    @Test
    fun untaggedOrClashingTracksAreNumberedInPlayOrder() {
        val untagged = listOf(track("a", null, name = "x"), track("b", null, name = "y"), track("c", null, name = "z"))
        assertEquals(listOf(1, 2, 3), JellyfinTrack.fileNumbers(untagged))
        val clash = listOf(track("a", 1), track("b", 1))
        assertEquals(listOf(1, 2), JellyfinTrack.fileNumbers(clash))
        assertEquals(emptyList(), JellyfinTrack.fileNumbers(emptyList()))
    }

    // Names

    @Test
    fun sanitizeKeepsLettersDigitsAndSafePunctuation() {
        assertEquals("Im In Love", DownloadPaths.sanitize("I'm In Love"))
        assertEquals("Song Live 2001", DownloadPaths.sanitize("Song (Live, 2001)"))
        assertEquals("a-b_c d.e", DownloadPaths.sanitize("a-b_c d.e/\\:*?\"<>|"))
        assertEquals("Café Ñandú 東京", DownloadPaths.sanitize("Café: Ñandú 東京!"))
    }

    @Test
    fun trackFileNamesArePaddedAndSanitized() {
        assertEquals("01 - Whats Up.flac", DownloadPaths.trackFileName(1, "What's Up?", AudioFormat.FLAC))
        assertEquals("201 - B.m4a", DownloadPaths.trackFileName(201, "B", AudioFormat.ALAC))
        val paths = DownloadPaths("/data/Downloads/")
        assertEquals("/data/Downloads/al1/01 - A.flac.part", paths.partPath("al1", "01 - A.flac"))
        assertEquals("/data/Downloads/al1/covers/01 - A.B.jpg", paths.trackCoverPath("al1", "01 - A.B.flac"))
        assertEquals("/data/Downloads/al1/album.json", paths.manifestPath("al1"))
        assertEquals("/data/Downloads/al1/cover.jpg", paths.coverPath("al1"))
    }

    // URLs

    private val api = FakeServer { json("") }.api(token = "tok", userId = "u1")

    @Test
    fun sameContainerDownloadsTheOriginalFile() {
        assertEquals(
            "http://jellyfin-box:8096/Audio/t1/stream.flac?static=true",
            DownloadUrls.url(api, track("t1", 1, container = "flac"), AudioFormat.FLAC),
        )
        assertEquals(
            "http://jellyfin-box:8096/Audio/t1/stream.m4a?static=true",
            DownloadUrls.url(api, track("t1", 1, container = "m4a", codec = "alac"), AudioFormat.ALAC),
        )
    }

    @Test
    fun otherContainersAreConvertedWithoutATokenInTheUrl() {
        val url = DownloadUrls.url(api, track("t1", 1, container = "flac"), AudioFormat.AAC)
        assertEquals(
            "http://jellyfin-box:8096/Audio/t1/universal?UserId=u1&DeviceId=device-1&Container=m4a&TranscodingContainer=m4a" +
                "&TranscodingProtocol=http&AudioCodec=aac",
            url,
        )
        val wav = DownloadUrls.url(api, track("t1", 1, container = "flac"), AudioFormat.WAV)
        assertFalse("AudioCodec" in wav)
        assertTrue("Container=wav" in wav)
        assertFalse("ApiKey" in wav || "tok" in wav)
        assertTrue("/universal?" in DownloadUrls.url(api, track("t1", 1, container = null), AudioFormat.FLAC))
    }

    // Manifest

    private val manifest = DownloadManifest(
        albumId = "al1", title = "OK Computer", artist = "Radiohead", year = 1997, format = "flac",
        downloadedAt = 1_700_000_000_000, status = ManifestStatus.COMPLETE, expectedTracks = 2,
        tracks = listOf(
            ManifestTrack(1, "t1", "Airbag", "01 - Airbag.flac", 284.5, -6.2, "FLAC 905 kbps", false),
            ManifestTrack(2, "t2", "Paranoid Android", "02 - Paranoid Android.flac", 383.0, null, null, true),
        ),
    )

    @Test
    fun manifestRoundTrips() {
        val text = manifest.encode()
        assertTrue("\"status\": \"complete\"" in text)
        assertEquals(manifest, DownloadManifest.decode(text))
        assertEquals(AudioFormat.FLAC, manifest.preferredFormat)
        assertNull(manifest.copy(format = "original").preferredFormat)
        assertNull(DownloadManifest.decode("not json"))
        // Missing fields take defaults; unknown ones are ignored.
        val small = DownloadManifest.decode("""{"albumId":"a","title":"T","artist":"A","extra":1}""")!!
        assertEquals(ManifestStatus.DOWNLOADING, small.status)
        assertEquals("original", small.format)
    }

    @Test
    fun downloadedMeansCompleteWithEveryFile() {
        val all = setOf("01 - Airbag.flac", "02 - Paranoid Android.flac")
        assertTrue(DownloadManifest.isDownloaded(manifest) { it in all })
        assertFalse(DownloadManifest.isDownloaded(manifest) { it == "01 - Airbag.flac" })
        assertFalse(DownloadManifest.isDownloaded(manifest.copy(status = ManifestStatus.PAUSED)) { it in all })
        assertFalse(DownloadManifest.isDownloaded(manifest.copy(tracks = emptyList())) { true })
        assertFalse(DownloadManifest.isDownloaded(null) { true })
    }

    // Settings

    @Test
    fun settingsUseTheIosKeysAndDefaults() {
        val prefs = InMemoryPreferences()
        val settings = DownloadSettings(prefs)
        assertNull(settings.defaultFormat)
        assertTrue(settings.allowCellular)
        settings.defaultFormat = AudioFormat.ALAC
        assertEquals("alac", prefs.getString("downloads.defaultFormat"))
        assertEquals(AudioFormat.ALAC, settings.defaultFormat)
        settings.defaultFormat = null
        assertEquals("original", prefs.getString("downloads.defaultFormat"))
        assertNull(settings.defaultFormat)
        settings.allowCellular = false
        assertEquals("false", prefs.getString("downloads.allowCellular"))
        assertFalse(settings.allowCellular)
    }

    // Local search (as iOS testLocalSearchMatchesAllWordsIgnoringAccents)

    @Test
    fun localSearchMatchesAllWordsIgnoringAccents() {
        val albums = listOf(
            LocalAlbum("1", "Mellon Collie", "Smashing Pumpkins"),
            LocalAlbum("2", "Café Tacvba", "Café Tacvba"),
            LocalAlbum("3", "Gish", "Smashing Pumpkins"),
        )
        assertEquals(listOf("1", "3"), LocalSearch.matches("pumpkins", albums).map { it.id })
        assertEquals(listOf("1"), LocalSearch.matches("collie smashing", albums).map { it.id })
        assertEquals(listOf("2"), LocalSearch.matches("cafe", albums).map { it.id })
        assertEquals(listOf("2"), LocalSearch.matches("CAFÉ", albums).map { it.id })
        assertEquals(emptyList(), LocalSearch.matches("   ", albums))
    }

    @Test
    fun localSearchAlsoMatchesTrackTitles() {
        val albums = listOf(
            LocalAlbum("1", "OK Computer", "Radiohead", listOf("Airbag", "Paranoid Android")),
            LocalAlbum("2", "Kid A", "Radiohead", listOf("Idioteque")),
        )
        assertEquals(listOf("1"), LocalSearch.matches("paranoid", albums).map { it.id })
        assertEquals(listOf("2"), LocalSearch.matches("radiohead idioteque", albums).map { it.id })
        assertEquals(emptyList(), LocalSearch.matches("radiohead nothing", albums))
    }
}
