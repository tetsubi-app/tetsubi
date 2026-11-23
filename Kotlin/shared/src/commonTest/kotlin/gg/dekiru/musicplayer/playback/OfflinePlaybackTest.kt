package gg.dekiru.musicplayer.playback

import gg.dekiru.musicplayer.data.api.FakeServer
import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import gg.dekiru.musicplayer.data.api.path
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinPerson
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.downloads.DownloadManifest
import gg.dekiru.musicplayer.downloads.DownloadPaths
import gg.dekiru.musicplayer.downloads.DownloadSettings
import gg.dekiru.musicplayer.downloads.LocalFiles
import gg.dekiru.musicplayer.downloads.ManifestStatus
import gg.dekiru.musicplayer.downloads.ManifestTrack
import gg.dekiru.musicplayer.downloads.tempDir
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Puts a complete download on disk, as `DownloadManager` leaves it. Track 2 has its own picture. */
internal fun writeDownload(
    root: String,
    albumId: String,
    title: String,
    tracks: Int = 3,
    cover: Boolean = true,
    downloadedAt: Long = 1,
): List<ManifestTrack> {
    val paths = DownloadPaths(root)
    val dir = paths.albumDir(albumId)
    LocalFiles.createDirectories(dir)
    val entries = (1..tracks).map { i ->
        ManifestTrack(
            number = i, trackId = "$albumId-t$i", title = "Song $i (Live?)", file = "${i.toString().padStart(2, '0')} - Song $i Live.flac",
            durationSeconds = i * 60.0, normalizationGain = -2.0 * i, qualityLabel = "FLAC ${900 + i} kbps", hasOwnArt = i == 2,
        )
    }
    for (t in entries) {
        LocalFiles.writeText("$dir/${t.file}", "audio")
        if (t.hasOwnArt) {
            LocalFiles.createDirectories(paths.trackCoversDir(albumId))
            LocalFiles.writeText(paths.trackCoverPath(albumId, t.file), "jpg")
        }
    }
    if (cover) LocalFiles.writeText(paths.coverPath(albumId), "jpg")
    val manifest = DownloadManifest(
        albumId = albumId, title = title, artist = "Band", year = 1999, format = "original",
        downloadedAt = downloadedAt, status = ManifestStatus.COMPLETE, expectedTracks = tracks, tracks = entries,
    )
    LocalFiles.writeText(paths.manifestPath(albumId), manifest.encode())
    return entries
}

/** Downloaded albums play from their files, with the manifest's titles and pictures. */
class OfflinePlaybackTest {

    private fun album(id: String, name: String = "Album") =
        JellyfinAlbum(id = id, name = name, albumArtists = listOf(JellyfinPerson(name = "Band")))

    private class Setup(
        val server: FakeServer,
        val engine: FakeEngine,
        val player: PlayerController,
        val dir: String,
        val manager: () -> DownloadManager,
    ) {
        var online = true
        fun reports() = server.requests.filter { it.path.startsWith("/Sessions/Playing") }
        fun trackFetches() = server.requests.filter { it.path == "/Items" }
    }

    /** Albums are written to disk before the manager is made (it reads them on start). */
    private fun TestScope.setup(dir: String = tempDir(), maniac: Boolean = false): Setup {
        val server = FakeServer { request ->
            when (request.path) {
                "/Items" -> {
                    val parent = request.url.parameters["ParentId"]!!
                    json(
                        """{"Items":[{"Id":"$parent-s1","Name":"Streamed","IndexNumber":1,"AlbumId":"$parent","RunTimeTicks":600000000,
                           "MediaSources":[{"Container":"flac","Codecs":"flac"}]}]}""",
                    )
                }
                else -> json("")
            }
        }
        val api = server.api(token = "tok", userId = "u1")
        val prefs = InMemoryPreferences()
        val manager by lazy { DownloadManager(api, dir, DownloadSettings(prefs), backgroundScope) }
        lateinit var s: Setup
        val engine = FakeEngine()
        val player = PlayerController(
            api, engine, PlaybackSettings(prefs).also { it.maniacMode = maniac }, backgroundScope,
            isOnline = { s.online }, downloads = { manager }, random = Random(1),
        )
        s = Setup(server, engine, player, dir) { manager }
        return s
    }

    @Test
    fun aDownloadedAlbumPlaysFromItsFiles() = runTest {
        val dir = tempDir()
        writeDownload(dir, "al", "Album")
        val s = setup(dir)
        s.player.play(album("al"))
        settle()

        assertTrue(s.trackFetches().isEmpty(), "no track list from the server")
        assertEquals(listOf("al-t1", "al-t2", "al-t3"), s.engine.items.map { it.id })
        assertEquals("file://$dir/al/01 - Song 1 Live.flac", s.engine.items[0].url)
        assertFalse(s.engine.items[0].seeksByRestart)
        assertEquals(120_000L, s.engine.items[1].durationMillis)
        assertEquals(listOf("Song 1 (Live?)", "Song 2 (Live?)", "Song 3 (Live?)"), s.player.queue.map { it.title })
        assertEquals(listOf(1, 2, 3), s.player.queue.map { it.trackNumber })
        val t2 = s.player.queue[1]
        assertEquals("FLAC 902 kbps", t2.qualityLabel)
        assertNull(t2.sampleRateLabel, "read from the file; a test file isn't real audio")
        assertEquals(-4.0, t2.normalizationGain)
        assertEquals("Band", t2.artist)
        assertEquals("Album", t2.album)
        assertEquals("al", t2.albumId)
        assertEquals(60.0, s.player.duration)
        assertTrue(s.player.isPlaying(album("al")))
        assertFalse(s.player.isLoading)

        // Pictures: track 2's own, the others the album cover; the notification gets them as files.
        assertEquals("$dir/al/cover.jpg", s.player.queue[0].localArtPath)
        assertEquals("$dir/al/covers/02 - Song 2 Live.jpg", t2.localArtPath)
        assertEquals("al-t2", t2.artItemId)
        assertEquals("al", s.player.queue[0].artItemId)
        assertEquals("file://$dir/al/covers/02 - Song 2 Live.jpg", s.engine.items[1].artworkUrl)
        assertEquals("file://$dir/al/cover.jpg", s.engine.items[2].artworkUrl)

        // Online: the play is reported as when streaming.
        assertEquals(1, s.reports().size)
    }

    @Test
    fun withoutASavedCoverTheServersPictureIsUsed() = runTest {
        val dir = tempDir()
        writeDownload(dir, "al", "Album", cover = false)
        val s = setup(dir)
        s.player.play(album("al"))
        settle()
        assertNull(s.player.queue[0].localArtPath)
        assertTrue(s.engine.items[0].artworkUrl.startsWith("http://jellyfin-box:8096/Items/al/Images/Primary"))
        assertEquals("$dir/al/covers/02 - Song 2 Live.jpg", s.player.queue[1].localArtPath)
    }

    @Test
    fun aTappedTrackStillStartsAtTrackOne() = runTest {
        val dir = tempDir()
        writeDownload(dir, "al", "Album")
        val s = setup(dir)
        s.player.play(album("al"), startTrackId = "al-t3")
        settle()
        // Downloads always play in order from track 1, as iOS.
        assertEquals(0, s.engine.index)
        assertEquals(0, s.player.currentIndex)
        assertEquals("al-t1", s.player.currentTrack?.id)
        assertEquals((1..3).map { "al-t$it" }, s.engine.items.map { it.id })
    }

    @Test
    fun aDownloadedAlbumShufflesAcrossTheQueueToo() = runTest {
        // Changed with the listening queue: downloads used to ignore shuffle and always play in order.
        val dir = tempDir()
        writeDownload(dir, "al", "Album", tracks = 8)
        val s = setup(dir)
        s.player.play(album("al"), startTrackId = "al-t5")
        settle()
        val inOrder = (1..8).map { "al-t$it" }
        assertEquals(inOrder, s.engine.items.map { it.id })
        assertEquals(0, s.engine.index, "a download plays from track 1")
        // Shuffle reorders it now, the playing track first; off puts it back in order.
        s.player.changeShuffle(true)
        assertEquals("al-t1", s.engine.items.first().id)
        assertEquals(inOrder.toSet(), s.engine.items.map { it.id }.toSet())
        assertEquals(1, s.engine.loads, "the playing track carries on")
        s.player.changeShuffle(false)
        assertEquals(inOrder, s.engine.items.map { it.id })
        s.player.changeShuffle(true)
        s.player.changeManiacMode(true)
        assertEquals(inOrder, s.engine.items.map { it.id })
        assertTrue(s.engine.items.all { it.url.startsWith("file://") })
    }

    @Test
    fun maniacModeStartsAtTrackOne() = runTest {
        val dir = tempDir()
        writeDownload(dir, "al", "Album")
        val s = setup(dir, maniac = true)
        s.player.play(album("al"), startTrackId = "al-t3")
        settle()
        assertEquals(0, s.engine.index)
    }

    @Test
    fun theEndGoesBackToTrackOne() = runTest {
        val dir = tempDir()
        writeDownload(dir, "al", "Album", tracks = 2)
        val s = setup(dir)
        s.player.play(album("al"))
        settle()
        s.engine.finishTrack()
        s.player.onEnded()
        assertEquals(0, s.player.currentIndex)
        assertFalse(s.player.isNowPlayingOpen)
    }

    @Test
    fun offlineNothingIsReported() = runTest {
        val dir = tempDir()
        writeDownload(dir, "al", "Album")
        val s = setup(dir)
        s.online = false
        s.player.play(album("al"))
        settle()
        s.engine.finishTrack()
        s.player.skipToNext()
        settle()
        assertTrue(s.reports().isEmpty())
        assertTrue(s.server.requests.isEmpty(), "nothing asked of the server")
    }

    @Test
    fun aMergedMultiDiscAlbumPlaysEveryDiscFromFiles() = runTest {
        val dir = tempDir()
        writeDownload(dir, "d1", "Set (Disc 1)", tracks = 2)
        writeDownload(dir, "d2", "Set (Disc 2)", tracks = 3)
        val s = setup(dir)
        val merged = album("d1", "Set").copy(discAlbums = listOf(album("d1", "Set (Disc 1)"), album("d2", "Set (Disc 2)")))
        s.player.play(merged, startTrackId = "d2-t2")
        settle()

        assertTrue(s.trackFetches().isEmpty())
        assertEquals(listOf("d1-t1", "d1-t2", "d2-t1", "d2-t2", "d2-t3"), s.engine.items.map { it.id })
        assertEquals(0, s.engine.index)
        assertEquals(listOf(1, 2, 1, 2, 3), s.player.queue.map { it.trackNumber })
        assertTrue(s.player.queue.all { it.album == "Set" && it.albumId == "d1" })
        assertEquals("file://$dir/d2/02 - Song 2 Live.flac", s.engine.items[3].url)
        assertEquals("$dir/d2/covers/02 - Song 2 Live.jpg", s.player.queue[3].localArtPath)
        assertEquals("$dir/d2/cover.jpg", s.player.queue[2].localArtPath)
        assertTrue(s.player.isPlaying(album("d2")))
    }

    @Test
    fun aMergedAlbumWithADiscMissingStreams() = runTest {
        val dir = tempDir()
        writeDownload(dir, "d1", "Set (Disc 1)", tracks = 2)
        val s = setup(dir)
        val merged = album("d1", "Set").copy(discAlbums = listOf(album("d1", "Set (Disc 1)"), album("d2", "Set (Disc 2)")))
        s.player.play(merged)
        settle()
        assertEquals(2, s.trackFetches().size)
        assertEquals(listOf("d1-s1", "d2-s1"), s.engine.items.map { it.id })
        assertTrue(s.engine.items.none { it.url.startsWith("file://") })
        assertTrue(s.player.queue.all { it.localArtPath == null })
    }

    @Test
    fun anAlbumThatIsntDownloadedStreams() = runTest {
        val s = setup()
        s.player.play(album("al"))
        settle()
        assertEquals(listOf("al-s1"), s.engine.items.map { it.id })
        assertTrue(s.engine.items.single().url.startsWith("http://"))
    }
}
