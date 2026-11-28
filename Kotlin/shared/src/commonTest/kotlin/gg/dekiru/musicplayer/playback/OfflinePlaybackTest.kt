package gg.dekiru.musicplayer.playback

import gg.dekiru.musicplayer.data.api.FakeServer
import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import gg.dekiru.musicplayer.data.api.path
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinPerson
import gg.dekiru.musicplayer.data.models.JellyfinTrack
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.downloads.DownloadManifest
import gg.dekiru.musicplayer.downloads.DownloadPaths
import gg.dekiru.musicplayer.downloads.DownloadSettings
import gg.dekiru.musicplayer.downloads.LocalFiles
import gg.dekiru.musicplayer.downloads.ManifestStatus
import gg.dekiru.musicplayer.downloads.ManifestTrack
import gg.dekiru.musicplayer.downloads.PlaylistManifest
import gg.dekiru.musicplayer.downloads.PlaylistManifestTrack
import gg.dekiru.musicplayer.downloads.PlaylistPaths
import gg.dekiru.musicplayer.downloads.tempDir
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    private fun TestScope.setup(dir: String = tempDir(), maniac: Boolean = false, streamedId: (String) -> String = { "$it-s1" }): Setup {
        val server = FakeServer { request ->
            when (request.path) {
                "/Items" -> {
                    val parent = request.url.parameters["ParentId"]!!
                    json(
                        """{"Items":[{"Id":"${streamedId(parent)}","Name":"Streamed","IndexNumber":1,"AlbumId":"$parent","RunTimeTicks":600000000,
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
    fun aDownloadedDiscOfAnAlbumNotFullyDownloadedPlaysFromItsFile() = runTest {
        // Disc 1 is on the device (the page shows it Downloaded), disc 2 isn't: the album isn't "downloaded", but disc 1 must not stream.
        val dir = tempDir()
        writeDownload(dir, "d1", "Disc 1", tracks = 2)
        val s = setup(dir, streamedId = { if (it == "d1") "d1-t2" else "$it-s1" })
        val set = album("set").copy(discAlbums = listOf(album("d1"), album("d2")))
        s.player.play(set)
        settle()

        val urls = s.engine.items.map { it.url }
        assertEquals("file://$dir/d1/02 - Song 2 Live.flac", urls[0], "the downloaded track plays from its file")
        assertTrue(s.engine.items[0].planNote!!.startsWith("play plan: item=d1-t2 plan=file reasons=downloaded format=flac"), s.engine.items[0].planNote)
        assertFalse(s.engine.items[0].seeksByRestart)
        assertTrue(urls[1].contains("/Audio/d2-s1/"), "the other disc streams: ${urls[1]}")
        assertEquals("FLAC 902 kbps · downloaded", s.player.queue[0].qualityLabel)
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
        assertEquals("FLAC 902 kbps · downloaded", t2.qualityLabel)
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

    // The player decides "play from the file" with the same lists the pages use to say Downloaded
    // (albums, in progress or not; playlists), whichever page started it and however the ids are written.

    /** A complete playlist download on disk: each song as `<id>.<ext>` in the playlist's folder. */
    private fun writePlaylistDownload(root: String, playlistId: String, ids: List<String>, ext: String = "flac") {
        val paths = PlaylistPaths(root)
        LocalFiles.createDirectories(paths.dir(playlistId))
        val rows = ids.map { id ->
            LocalFiles.writeText(paths.trackPath(playlistId, "$id.$ext"), "audio")
            PlaylistManifestTrack(trackId = id, title = "Song $id", file = "$id.$ext", durationSeconds = 60.0, qualityLabel = "FLAC 900 kbps", format = "FLAC")
        }
        val manifest = PlaylistManifest(playlistId = playlistId, name = "Mix", downloadedAt = 1, status = ManifestStatus.COMPLETE, tracks = rows)
        LocalFiles.writeText(paths.manifestPath(playlistId), manifest.encode())
    }

    private fun assertPlaysFromFile(s: Setup, id: String, path: String) {
        val item = s.engine.items.first { it.id == id }
        assertEquals("file://$path", item.url)
        assertTrue(item.planNote!!.startsWith("play plan: item=$id plan=file reasons=downloaded"), item.planNote)
    }

    @Test
    fun aSongOfADownloadedPlaylistPlaysFromItsFileFromAnAlbumPage() = runTest {
        val dir = tempDir()
        writePlaylistDownload(dir, "pl", listOf("p-t1"))
        val s = setup(dir, streamedId = { "p-t1" })
        s.player.play(album("elsewhere"))
        settle()
        assertPlaysFromFile(s, "p-t1", "$dir/playlists/pl/p-t1.flac")
    }

    @Test
    fun aTrackOfAnAlbumWhoseDownloadIsInProgressPlaysFromItsFileOnTheDevice() = runTest {
        val dir = tempDir()
        writeDownload(dir, "al", "Album", tracks = 3)
        // The album is being downloaded again (paused here): the manager lists it as active, not downloaded.
        val manifestPath = DownloadPaths(dir).manifestPath("al")
        val manifest = DownloadManifest.decode(LocalFiles.readText(manifestPath)!!)!!
        LocalFiles.writeText(manifestPath, manifest.copy(status = ManifestStatus.PAUSED).encode())
        LocalFiles.deleteRecursively("$dir/al/03 - Song 3 Live.flac")
        val s = setup(dir, streamedId = { "al-t2" })
        assertFalse(s.manager().isDownloaded("al"))
        assertNotNull(s.manager().state("al"))
        s.player.play(album("al"))
        settle()
        assertPlaysFromFile(s, "al-t2", "$dir/al/02 - Song 2 Live.flac")
    }

    @Test
    fun aTrackWhoseFileIsMissingStillStreamsWhileItsAlbumIsInProgress() = runTest {
        val dir = tempDir()
        writeDownload(dir, "al", "Album", tracks = 3)
        val manifestPath = DownloadPaths(dir).manifestPath("al")
        val manifest = DownloadManifest.decode(LocalFiles.readText(manifestPath)!!)!!
        LocalFiles.writeText(manifestPath, manifest.copy(status = ManifestStatus.PAUSED).encode())
        LocalFiles.deleteRecursively("$dir/al/03 - Song 3 Live.flac")
        val s = setup(dir, streamedId = { "al-t3" })
        s.player.play(album("al"))
        settle()
        assertTrue(s.engine.items.single().url.startsWith("http://"), s.engine.items.single().url)
    }

    @Test
    fun aTrackIdWrittenWithDashesOrCapitalsFindsItsFile() = runTest {
        val dir = tempDir()
        writePlaylistDownload(dir, "pl", listOf("0123456789abcdef0123456789abcdef"))
        val s = setup(dir, streamedId = { "01234567-89AB-CDEF-0123-456789ABCDEF" })
        s.player.play(album("elsewhere"))
        settle()
        val item = s.engine.items.single()
        assertEquals("file://$dir/playlists/pl/0123456789abcdef0123456789abcdef.flac", item.url)
        assertTrue(item.planNote!!.contains("plan=file"), item.planNote)
    }

    @Test
    fun aDownloadKeptWithAnotherExtensionIsFoundByItsName() = runTest {
        // ALAC saved as FLAC, a manifest still naming the .m4a: the file on disk is the .flac.
        val dir = tempDir()
        writeDownload(dir, "al", "Album", tracks = 3)
        val manifestPath = DownloadPaths(dir).manifestPath("al")
        val manifest = DownloadManifest.decode(LocalFiles.readText(manifestPath)!!)!!
        LocalFiles.writeText(manifestPath, manifest.copy(status = ManifestStatus.PAUSED, tracks = manifest.tracks.map { it.copy(file = it.file.replace(".flac", ".m4a")) }).encode())
        val s = setup(dir, streamedId = { "al-t1" })
        s.player.play(album("al"))
        settle()
        assertPlaysFromFile(s, "al-t1", "$dir/al/01 - Song 1 Live.flac")
    }

    @Test
    fun songsAddedFromASearchPlayFromTheDeviceToo() = runTest {
        val dir = tempDir()
        writePlaylistDownload(dir, "pl", listOf("p-t1"))
        val s = setup(dir)
        val track = JellyfinTrack(id = "p-t1", name = "Song", albumId = "x", album = "X", runTimeTicks = 600_000_000)
        s.player.addToQueue(album("x"), listOf(track))
        settle()
        assertPlaysFromFile(s, "p-t1", "$dir/playlists/pl/p-t1.flac")
    }

    @Test
    fun aRestoredQueueEntryPlaysFromTheDevice() = runTest {
        val first = setup(streamedId = { "p-t1" })
        first.player.play(album("elsewhere"))
        settle()
        val snapshot = first.player.playQueue.snapshot(5.0, "http://jellyfin-box:8096", "u1")
        val dir = tempDir()
        writePlaylistDownload(dir, "pl", listOf("p-t1"))
        val r = setup(dir)
        r.player.restore(snapshot)
        assertPlaysFromFile(r, "p-t1", "$dir/playlists/pl/p-t1.flac")
    }
}
