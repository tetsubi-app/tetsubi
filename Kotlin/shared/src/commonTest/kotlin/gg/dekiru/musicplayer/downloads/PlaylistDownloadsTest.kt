package gg.dekiru.musicplayer.downloads

import gg.dekiru.musicplayer.data.api.FakeServer
import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.api.path
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinPerson
import gg.dekiru.musicplayer.data.models.JellyfinPlaylist
import gg.dekiru.musicplayer.data.models.PlaylistEntry
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import gg.dekiru.musicplayer.playback.FakeEngine
import gg.dekiru.musicplayer.playback.PlaybackSettings
import gg.dekiru.musicplayer.playback.PlayerController
import gg.dekiru.musicplayer.playback.settle
import gg.dekiru.musicplayer.ui.playlist.PlaylistModel
import gg.dekiru.musicplayer.ui.playlist.PlaylistsModel
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Downloaded playlists: the saved list, songs stored once, songs a
 * downloaded album already has, keeping in step with the server, and
 * where a song is played from.
 */
class PlaylistDownloadsTest {

    private val playlist = JellyfinPlaylist(id = "pl", name = "Mix", dateCreated = "2024-01-02T00:00:00Z")

    private fun album(id: String) = JellyfinAlbum(id = id, name = "Album $id", albumArtists = listOf(JellyfinPerson(name = "Band $id")))

    /** Song IDs are "<album>-t<number>"; `numbers` false leaves the track numbers out. */
    private fun trackJson(id: String, numbers: Boolean = true): String {
        val album = id.substringBefore("-t")
        val i = id.substringAfter("-t").toInt()
        return """
            {"Id":"$id","Name":"Song $id",${if (numbers) """"IndexNumber":$i,""" else ""}"AlbumId":"$album","Album":"Album $album",
             "RunTimeTicks":${i * 600_000_000L},"AlbumArtist":"Band $album","AlbumArtists":[{"Name":"Band $album"}],"NormalizationGain":-3.5,
             "PlaylistItemId":"$id","MediaSources":[{"Container":"flac","Codecs":"flac","Bitrate":900000}]}
        """
    }

    private class Setup(val server: FakeServer, val dir: String, val api: JellyfinApi, val prefs: InMemoryPreferences, val make: () -> DownloadManager) {
        /** The playlist as the server has it. */
        var list = listOf<String>()
        var numbers = true
        var metered = false
        var online = true
        val failing = mutableSetOf<String>()

        /** A song's answer waits for its gate. */
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        fun gate(id: String) = CompletableDeferred<Unit>().also { gates[id] = it }
        var manager = make()
        val playlists get() = manager.playlists
        val folder get() = "$dir/playlists/pl"

        /** Song IDs fetched, in order. */
        fun audio() = server.requests.filter { it.path.startsWith("/Audio/") }.map { it.path.split('/')[2] }
        fun files() = LocalFiles.list(folder).toSet()
        fun manifest() = PlaylistManifest.decode(LocalFiles.readText("$folder/playlist.json")!!)!!
    }

    private fun TestScope.setup(dir: String = tempDir()): Setup {
        lateinit var s: Setup
        val server = FakeServer { request ->
            when {
                request.path == "/Items" -> {
                    val id = request.url.parameters["ParentId"]!!
                    json("""{"Items":[${(1..3).joinToString(",") { trackJson("$id-t$it") }}]}""")
                }
                request.path == "/Playlists/pl/Items" -> json("""{"Items":[${s.list.joinToString(",") { trackJson(it, s.numbers) }}]}""")
                request.path == "/Playlists/pl/Users/u1" -> json("""{"UserId":"u1","CanEdit":true}""")
                request.path == "/Users/u1/Items" -> json("""{"Items":[],"TotalRecordCount":0}""")
                request.path.startsWith("/Audio/") -> {
                    val id = request.path.split('/')[2]
                    s.gates[id]?.await()
                    if (id in s.failing) respond("broken", HttpStatusCode.InternalServerError)
                    else {
                        val bytes = "audio of $id".encodeToByteArray()
                        respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "${bytes.size}"))
                    }
                }
                request.path.endsWith("/Images/Primary") -> respond("jpg ${request.path}".encodeToByteArray(), HttpStatusCode.OK)
                else -> json("")
            }
        }
        val prefs = InMemoryPreferences()
        val runner = InProcessDownloadRunner(backgroundScope)
        val api = server.api(token = "tok", userId = "u1")
        s = Setup(server, dir, api, prefs) {
            DownloadManager(api, dir, DownloadSettings(prefs), backgroundScope, { s.metered }, runner, { 1_000L }, isOnline = { s.online })
        }
        return s
    }

    /** Sets the server's list and downloads the playlist with it, as its page would. */
    private suspend fun TestScope.download(s: Setup, vararg ids: String) {
        s.list = ids.toList()
        s.playlists.download(playlist, s.api.playlistTracks("pl"))
        settle()
    }

    private suspend fun TestScope.downloadAlbum(s: Setup, id: String) {
        s.manager.download(album(id))
        settle()
        assertTrue(s.manager.isDownloaded(id))
    }

    @Test
    fun theManifestRoundTrips() {
        val manifest = PlaylistManifest(
            playlistId = "pl", name = "Mix", format = "flac", downloadedAt = 5, status = ManifestStatus.COMPLETE, hasArt = true,
            dateCreated = "2024-01-02T00:00:00Z",
            tracks = listOf(
                PlaylistManifestTrack("a", "Song a", "Band", "Album", "A", 61.5, 3, "a.flac", "A", -2.0, "FLAC 900 kbps", "FLAC"),
                PlaylistManifestTrack("b", "Song b", file = null),
                PlaylistManifestTrack("a", "Song a", "Band", "Album", "A", 61.5, 3, "a.flac", "A", -2.0, "FLAC 900 kbps", "FLAC"),
            ),
        )
        assertEquals(manifest, PlaylistManifest.decode(manifest.encode()))
        assertNull(PlaylistManifest.decode("not json"))
        assertEquals(listOf("a", "b"), manifest.uniqueTracks.map { it.trackId })

        // The page's rows and the lists' playlist come back from it.
        val entries = manifest.toEntries()
        assertEquals(listOf(0, 1, 2), entries.map { it.position })
        assertEquals(listOf("a", "b", "a"), entries.map { it.track.id })
        assertEquals("Band", entries[0].albumArtist)
        assertEquals("Album", entries[0].albumName)
        assertEquals("A", entries[0].albumId)
        assertEquals("A", entries[0].artItemId)
        assertEquals(3, entries[0].track.indexNumber)
        assertEquals(61L, entries[0].track.durationSeconds)
        assertNull(entries[1].albumId)
        val listed = manifest.toPlaylist()
        assertEquals("Mix", listed.name)
        assertEquals(3, listed.songCount)
        assertTrue(listed.hasArt)
        assertEquals("2024-01-02T00:00:00Z", listed.dateCreated)
    }

    @Test
    fun downloadsAPlaylistWithItsListAndPictures() = runTest {
        val s = setup()
        s.list = listOf("A-t1", "B-t2")
        s.playlists.download(playlist, s.api.playlistTracks("pl"))
        val queued = s.manager.state(PlaylistDownloads.key("pl"))
        assertEquals(DownloadStatus.QUEUED, queued?.status)
        assertEquals("pl", queued?.playlistId)
        assertEquals("Mix", queued?.title)
        assertFalse(s.playlists.isDownloaded("pl"))
        assertTrue(s.playlists.availableOffline.isEmpty())
        settle()

        assertEquals(setOf("A-t1.flac", "B-t2.flac", "playlist.json", "covers"), s.files())
        assertEquals("audio of B-t2", LocalFiles.readText("${s.folder}/B-t2.flac"))
        assertEquals(setOf("A.jpg", "B.jpg"), LocalFiles.list("${s.folder}/covers").toSet())
        val m = s.manifest()
        assertEquals(ManifestStatus.COMPLETE, m.status)
        assertEquals(1_000L, m.downloadedAt)
        assertEquals("Mix", m.name)
        assertEquals("original", m.format)
        assertEquals("2024-01-02T00:00:00Z", m.dateCreated)
        assertEquals(
            PlaylistManifestTrack("B-t2", "Song B-t2", "Band B", "Album B", "B", 120.0, 2, "B-t2.flac", "B", -3.5, "FLAC 900 kbps", "FLAC"),
            m.tracks[1],
        )

        assertNull(s.manager.state(PlaylistDownloads.key("pl")))
        assertTrue(s.playlists.isDownloaded("pl"))
        val done = s.playlists.downloaded.single()
        assertEquals(2, done.songCount)
        assertEquals(180.0, done.durationSeconds)
        assertEquals(LocalFiles.totalSize(s.folder), done.sizeBytes)
        assertEquals("${s.folder}/covers/A.jpg", done.coverPath)
        assertTrue(s.manager.downloaded.value.isEmpty(), "a playlist isn't an album download")

        // A new start reads it back from disk.
        s.manager = s.make()
        assertTrue(s.playlists.isDownloaded("pl"))
        assertNull(s.manager.state(PlaylistDownloads.key("pl")))
        assertTrue(s.manager.downloaded.value.isEmpty())
    }

    @Test
    fun aSongInThePlaylistTwiceIsStoredOnce() = runTest {
        val s = setup()
        download(s, "A-t1", "B-t1", "A-t1")
        assertEquals(listOf("A-t1", "B-t1"), s.audio().sorted())
        assertEquals(setOf("A-t1.flac", "B-t1.flac", "playlist.json", "covers"), s.files())
        val m = s.manifest()
        assertEquals(listOf("A-t1", "B-t1", "A-t1"), m.tracks.map { it.trackId })
        assertEquals(listOf("A-t1.flac", "B-t1.flac", "A-t1.flac"), m.tracks.map { it.file })
        assertTrue(s.playlists.isDownloaded("pl"))
        assertEquals(3, s.playlists.saved("pl")?.songCount)
        assertEquals(listOf(0, 1, 2), m.toEntries().map { it.position })
    }

    @Test
    fun bothRowsOfASongPlayFromTheOneFileOffline() = runTest {
        val s = setup()
        download(s, "A-t1", "B-t1", "A-t1")
        s.online = false
        val engine = FakeEngine()
        val player = PlayerController(
            s.api, engine, PlaybackSettings(s.prefs), backgroundScope,
            isOnline = { s.online }, downloads = { s.manager }, random = Random(1),
        )
        val saved = s.playlists.saved("pl")!!
        player.play(saved.manifest.toPlaylist(), saved.manifest.toEntries(), startingAt = 2)
        settle()
        assertEquals(listOf("A-t1", "B-t1", "A-t1"), engine.items.map { it.id })
        assertEquals("file://${s.folder}/A-t1.flac", engine.items[0].url)
        assertEquals(engine.items[0].url, engine.items[2].url)
        assertEquals(2, engine.index)
        assertEquals(2, player.currentPlace)
        assertEquals("${s.folder}/covers/A.jpg", player.queue[2].localArtPath)
        assertEquals("FLAC 900 kbps \u00b7 downloaded", player.queue[2].qualityLabel)
        assertNull(player.queue[2].sampleRateLabel)
        assertTrue(s.server.requests.none { it.path.startsWith("/Playlists/") && it.path != "/Playlists/pl/Items" })
    }

    @Test
    fun offlineOnlyTheSongsOnTheDevicePlay() = runTest {
        val s = setup()
        download(s, "A-t1", "B-t1")
        s.online = false
        val engine = FakeEngine()
        val player = PlayerController(
            s.api, engine, PlaybackSettings(s.prefs), backgroundScope,
            isOnline = { s.online }, downloads = { s.manager }, random = Random(1),
        )
        // The page still shows a song that isn't on the device (added since).
        s.list = listOf("A-t1", "C-t1", "B-t1")
        s.online = true
        val entries = s.api.playlistTracks("pl")
        s.online = false
        assertEquals(null, player.play(playlist, entries, startingAt = 2))
        settle()
        assertEquals(listOf("A-t1", "B-t1"), engine.items.map { it.id })
        assertEquals(1, engine.index)
        assertEquals(2, player.currentPlace, "the row's place on the page")
    }

    @Test
    fun aRefusedPlayLeavesWhatIsPlayingAlone() = runTest {
        val s = setup()
        download(s, "A-t1", "B-t1")
        val engine = FakeEngine()
        val player = PlayerController(
            s.api, engine, PlaybackSettings(s.prefs), backgroundScope,
            isOnline = { s.online }, downloads = { s.manager }, random = Random(1),
        )
        s.list = listOf("A-t1", "C-t1", "B-t1")
        val entries = s.api.playlistTracks("pl")
        s.online = false
        player.play(playlist, entries, startingAt = 2, openNowPlaying = false)
        settle()
        val queue = player.queue.map { it.id }
        val loaded = engine.items.map { it.id }
        suspend fun assertUntouched() {
            settle()
            assertEquals(queue, player.queue.map { it.id })
            assertEquals(loaded, engine.items.map { it.id })
            assertEquals(1, engine.index)
            assertEquals(2, player.currentPlace)
            assertTrue(player.isPlaying(playlist))
            assertFalse(player.shuffle)
            assertFalse(player.isNowPlayingOpen)
            assertEquals(null, player.lastError)
        }

        // A tapped song that isn't on the device.
        assertEquals(PlayerController.SONG_NOT_DOWNLOADED, player.play(playlist, entries, startingAt = 1))
        assertEquals("This song isn't downloaded.", PlayerController.SONG_NOT_DOWNLOADED)
        assertUntouched()

        // Play and Shuffle on a playlist with nothing on the device: shuffle isn't turned on either.
        val other = JellyfinPlaylist(id = "other", name = "Other")
        val none = entries.filter { it.track.id == "C-t1" }
        assertEquals(PlayerController.NOTHING_DOWNLOADED, player.play(other, none))
        assertEquals(PlayerController.NOTHING_DOWNLOADED, player.play(other, none, shuffled = true))
        assertEquals("None of this playlist's songs are downloaded.", PlayerController.NOTHING_DOWNLOADED)
        assertUntouched()

        // Shuffle that can play does turn it on.
        assertEquals(null, player.play(playlist, entries, shuffled = true, openNowPlaying = false))
        assertTrue(player.shuffle)
    }

    @Test
    fun thePageShowsARefusedPlayAsANotice() = runTest {
        val s = setup()
        download(s, "A-t1", "B-t1")
        val engine = FakeEngine()
        val player = PlayerController(
            s.api, engine, PlaybackSettings(s.prefs), backgroundScope,
            isOnline = { s.online }, downloads = { s.manager }, random = Random(1),
        )
        val list = PlaylistsModel(s.api, s.prefs, backgroundScope) { s.manager }
        s.online = false
        val model = PlaylistModel(playlist, s.api, backgroundScope, list) { s.manager }
        model.loadOffline()
        // One song's file has gone.
        LocalFiles.deleteRecursively("${s.folder}/B-t1.flac")

        assertEquals(null, model.playNotice)
        model.play(player, startingAt = 1, openNowPlaying = false)
        assertEquals("This song isn't downloaded.", model.playNotice)
        assertEquals(null, player.lastError)
        assertFalse(player.isNowPlayingOpen)
        assertTrue(engine.items.isEmpty())

        // One that plays takes the notice away.
        model.play(player, startingAt = 0, openNowPlaying = false)
        settle()
        assertEquals(null, model.playNotice)
        assertEquals(listOf("A-t1"), engine.items.map { it.id })

        LocalFiles.deleteRecursively("${s.folder}/A-t1.flac")
        model.play(player, shuffled = true)
        assertEquals("None of this playlist's songs are downloaded.", model.playNotice)
        assertFalse(player.shuffle)
        assertFalse(player.isNowPlayingOpen)
        assertEquals(listOf("A-t1"), player.queue.map { it.id })
        model.clearPlayNotice()
        assertEquals(null, model.playNotice)

        // Left alone, the notice goes after six seconds.
        model.play(player)
        assertEquals("None of this playlist's songs are downloaded.", model.playNotice)
        testScheduler.advanceTimeBy(5_999)
        assertEquals("None of this playlist's songs are downloaded.", model.playNotice)
        testScheduler.advanceTimeBy(2)
        assertEquals(null, model.playNotice)
    }

    @Test
    fun aSongADownloadedAlbumHasIsNotDownloadedAgain() = runTest {
        val s = setup()
        downloadAlbum(s, "A")
        val before = s.audio().size
        download(s, "A-t2", "B-t1")

        assertEquals(listOf("B-t1"), s.audio().drop(before))
        assertEquals(setOf("B-t1.flac", "playlist.json", "covers"), s.files())
        val m = s.manifest()
        assertEquals(listOf(null, "B-t1.flac"), m.tracks.map { it.file })
        assertEquals(ManifestStatus.COMPLETE, m.status)
        assertTrue(s.playlists.isDownloaded("pl"))
        // Its picture is kept with the playlist all the same.
        assertEquals(setOf("A.jpg", "B.jpg"), LocalFiles.list("${s.folder}/covers").toSet())

        val find = s.playlists.resolver("pl")
        assertEquals("${s.dir}/A/02 - Song A-t2.flac", find("A-t2")?.path)
        assertEquals("${s.folder}/covers/A.jpg", find("A-t2")?.artPath)
        assertEquals("${s.folder}/B-t1.flac", find("B-t1")?.path)
    }

    @Test
    fun deletingTheAlbumDownloadsItsSongsIntoThePlaylist() = runTest {
        val s = setup()
        downloadAlbum(s, "A")
        download(s, "A-t2", "B-t1", "A-t3")
        val before = s.audio().size

        // No connection: it waits, and shows as downloading until it's whole again.
        s.online = false
        s.manager.delete(listOf("A"))
        settle()
        val waiting = s.manager.state(PlaylistDownloads.key("pl"))
        assertNotNull(waiting)
        assertEquals(1, waiting.completedTracks)
        assertEquals(3, waiting.expectedTracks)
        assertFalse(s.playlists.isDownloaded("pl"))
        assertTrue(s.playlists.downloaded.isEmpty())
        assertEquals(listOf("pl"), s.playlists.availableOffline.map { it.playlistId }, "still listed offline")
        assertEquals(before, s.audio().size)
        val find = s.playlists.resolver("pl")
        assertNull(find("A-t2"))
        assertNotNull(find("B-t1"))

        s.online = true
        s.manager.networkChanged()
        settle()
        assertEquals(listOf("A-t2", "A-t3"), s.audio().drop(before).sorted())
        assertEquals(setOf("A-t2.flac", "A-t3.flac", "B-t1.flac", "playlist.json", "covers"), s.files())
        assertEquals(listOf("A-t2.flac", "B-t1.flac", "A-t3.flac"), s.manifest().tracks.map { it.file })
        assertTrue(s.playlists.isDownloaded("pl"))
        assertNull(s.manager.state(PlaylistDownloads.key("pl")))
        assertEquals(1_000L, s.manifest().downloadedAt)
        assertEquals("${s.folder}/A-t2.flac", s.playlists.resolver("pl")("A-t2")?.path)
    }

    @Test
    fun theReDownloadWaitsForWiFi() = runTest {
        val s = setup()
        downloadAlbum(s, "A")
        download(s, "A-t1")
        s.manager.setAllowCellular(false)
        s.metered = true
        s.manager.delete(listOf("A"))
        settle()
        assertEquals(DownloadStatus.WAITING_FOR_WIFI, s.manager.state(PlaylistDownloads.key("pl"))?.status)
        assertFalse(s.playlists.isDownloaded("pl"))
        s.metered = false
        s.manager.networkChanged()
        settle()
        assertTrue(s.playlists.isDownloaded("pl"))
        assertEquals(setOf("A-t1.flac", "playlist.json", "covers"), s.files())
    }

    @Test
    fun aChangedPlaylistAddsAndRemovesTheRightFiles() = runTest {
        val s = setup()
        download(s, "A-t1", "B-t1", "B-t2")
        val before = s.audio().size

        // On the server: A-t1 gone, C-t1 added, the rest reordered, a new name.
        s.list = listOf("B-t2", "C-t1", "B-t1")
        s.playlists.sync("pl", s.api.playlistTracks("pl"), playlist.copy(name = "Renamed"))
        assertFalse(LocalFiles.exists("${s.folder}/A-t1.flac"), "removed at once")
        assertFalse(s.playlists.isDownloaded("pl"))
        assertEquals("Renamed", s.manager.state(PlaylistDownloads.key("pl"))?.title)
        settle()

        assertEquals(listOf("C-t1"), s.audio().drop(before))
        assertEquals(setOf("B-t1.flac", "B-t2.flac", "C-t1.flac", "playlist.json", "covers"), s.files())
        assertEquals(setOf("B.jpg", "C.jpg"), LocalFiles.list("${s.folder}/covers").toSet())
        val m = s.manifest()
        assertEquals("Renamed", m.name)
        assertEquals(listOf("B-t2", "C-t1", "B-t1"), m.tracks.map { it.trackId })
        assertEquals(ManifestStatus.COMPLETE, m.status)
        assertTrue(s.playlists.isDownloaded("pl"))
        assertEquals("Renamed", s.playlists.saved("pl")?.name)

        // Only a reorder: nothing is fetched and it stays downloaded.
        val fetched = s.audio().size
        s.list = listOf("B-t1", "B-t2", "C-t1")
        s.playlists.sync("pl", s.api.playlistTracks("pl"))
        assertTrue(s.playlists.isDownloaded("pl"))
        settle()
        assertEquals(fetched, s.audio().size)
        assertEquals(listOf("B-t1", "B-t2", "C-t1"), s.manifest().tracks.map { it.trackId })
        assertEquals("Renamed", s.manifest().name, "no playlist given: the name stays")

        // One of two rows of a song removed: the file stays.
        s.list = listOf("B-t1", "B-t2")
        s.playlists.sync("pl", s.api.playlistTracks("pl"))
        settle()
        assertEquals(setOf("B-t1.flac", "B-t2.flac", "playlist.json", "covers"), s.files())
        assertEquals(setOf("B.jpg"), LocalFiles.list("${s.folder}/covers").toSet())

        // A playlist that isn't downloaded is left alone.
        s.playlists.sync("nope", s.api.playlistTracks("pl"))
        assertFalse(LocalFiles.exists("${s.dir}/playlists/nope"))
    }

    @Test
    fun thePageKeepsTheDownloadInStep() = runTest {
        val s = setup()
        download(s, "A-t1", "B-t1")
        val list = PlaylistsModel(s.api, s.prefs, backgroundScope) { s.manager }
        val model = PlaylistModel(playlist, s.api, backgroundScope, list) { s.manager }

        // Seen changed on load.
        s.list = listOf("B-t1", "C-t1")
        model.load()
        settle()
        assertEquals(setOf("B-t1.flac", "C-t1.flac", "playlist.json", "covers"), s.files())
        assertTrue(s.playlists.isDownloaded("pl"))

        // Offline, a new page is filled from the saved list.
        s.online = false
        val offline = PlaylistModel(JellyfinPlaylist(id = "pl", name = "Mix"), s.api, backgroundScope, list) { s.manager }
        offline.loadOffline()
        assertTrue(offline.loaded)
        assertEquals(listOf("B-t1", "C-t1"), offline.entries.map { it.track.id })
        assertEquals(2, offline.playlist.songCount)

        // Edited in the app: a song removed.
        s.online = true
        model.checkCanEdit()
        settle()
        model.startEditing()
        model.remove(0)
        s.list = listOf("C-t1")
        model.doneEditing()
        settle()
        assertNull(model.saveError)
        assertEquals(setOf("C-t1.flac", "playlist.json", "covers"), s.files())
        assertEquals(listOf("C-t1"), s.manifest().tracks.map { it.trackId })
        assertTrue(s.playlists.isDownloaded("pl"))

        // Deleting the playlist removes its download.
        model.delete {}
        settle()
        assertFalse(LocalFiles.exists(s.folder))
        assertNull(s.playlists.saved("pl"))
    }

    @Test
    fun offlineTheListsShowDownloadedPlaylistsOnly() = runTest {
        val s = setup()
        download(s, "A-t1", "B-t2")
        val list = PlaylistsModel(s.api, s.prefs, backgroundScope) { s.manager }
        list.downloadsChanged(s.playlists.all.value)
        list.networkChanged(false)
        val shown = list.playlists.single()
        assertEquals("pl", shown.id)
        assertEquals("Mix", shown.name)
        assertEquals(2, shown.songCount)
        assertEquals("${s.folder}/covers/A.jpg", list.artPath(shown))
        assertTrue(s.server.requests.none { it.path == "/Users/u1/Items" }, "nothing asked of the server")

        // One that never finished downloading isn't listed.
        s.playlists.delete("pl")
        s.failing += "A-t1"
        download(s, "A-t1")
        list.downloadsChanged(s.playlists.all.value)
        assertTrue(list.playlists.isEmpty())

        // The server's list differing from what's saved is what sets a download right.
        val saved = PlaylistManifest("pl", "Mix", tracks = listOf(PlaylistManifestTrack("a", "Song", durationSeconds = 100.0)))
            .let { DownloadedPlaylist(it, "", 0, isComplete = true) }
        val same = JellyfinPlaylist(id = "pl", name = "Mix", songCount = 1, runTimeTicks = 1_000_000_000L)
        assertFalse(PlaylistsModel.differs(same, saved))
        assertTrue(PlaylistsModel.differs(same.copy(name = "Other"), saved))
        assertTrue(PlaylistsModel.differs(same.copy(songCount = 2), saved))
        assertTrue(PlaylistsModel.differs(same.copy(runTimeTicks = 1_500_000_000L), saved))
    }

    @Test
    fun songsWithNoTrackNumbersDownload() = runTest {
        val s = setup()
        s.numbers = false
        download(s, "A-t1", "A-t2", "B-t1")
        assertEquals(setOf("A-t1.flac", "A-t2.flac", "B-t1.flac", "playlist.json", "covers"), s.files())
        val m = s.manifest()
        assertEquals(listOf(0, 0, 0), m.tracks.map { it.trackNumber })
        assertEquals(ManifestStatus.COMPLETE, m.status)
        assertTrue(s.playlists.isDownloaded("pl"))
        assertEquals(listOf(null, null, null), m.toEntries().map { it.track.indexNumber })
        val find = s.playlists.resolver("pl")
        assertEquals("${s.folder}/A-t2.flac", find("A-t2")?.path)
        assertEquals("${s.folder}/B-t1.flac", find("B-t1")?.path)
    }

    @Test
    fun aSongIsFoundInThePlaylistsFolderThenADownloadedAlbumThenNowhere() = runTest {
        val s = setup()
        download(s, "A-t1", "B-t1")
        // The album is downloaded afterwards: the playlist keeps playing its own file.
        downloadAlbum(s, "A")
        val find = s.playlists.resolver("pl")
        assertEquals("${s.folder}/A-t1.flac", find("A-t1")?.path)
        assertEquals("${s.folder}/B-t1.flac", find("B-t1")?.path)
        assertNull(find("C-t1"), "not on the device: streamed")
        // Its own file gone: the album's.
        LocalFiles.deleteRecursively("${s.folder}/A-t1.flac")
        assertEquals("${s.dir}/A/01 - Song A-t1.flac", s.playlists.resolver("pl")("A-t1")?.path)

        // A playlist that isn't downloaded still plays a downloaded album's songs from the device.
        val other = s.playlists.resolver("other")
        assertEquals("${s.dir}/A/02 - Song A-t2.flac", other("A-t2")?.path)
        assertEquals("${s.dir}/A/cover.jpg", other("A-t2")?.artPath)
        assertNull(other("B-t1"), "another playlist's file isn't used")
    }

    @Test
    fun aFailedSongKeepsWhatCameDownAndRetryFinishesIt() = runTest {
        val s = setup()
        // Two songs come down at a time: the failing one answers once the other is on the device.
        s.failing += "B-t1"
        val failure = s.gate("B-t1")
        download(s, "A-t1", "B-t1")
        assertTrue(LocalFiles.exists("${s.folder}/A-t1.flac"))
        assertEquals(DownloadStatus.DOWNLOADING, s.manager.state(PlaylistDownloads.key("pl"))?.status)
        failure.complete(Unit)
        settle()
        val state = s.manager.state(PlaylistDownloads.key("pl"))
        assertEquals(DownloadStatus.FAILED, state?.status)
        assertTrue(state?.error.orEmpty().startsWith("Track download failed"), state?.error)
        assertEquals(ManifestStatus.FAILED, s.manifest().status)
        assertTrue(LocalFiles.exists("${s.folder}/A-t1.flac"))
        assertFalse(s.playlists.isDownloaded("pl"))
        assertTrue(s.playlists.availableOffline.isEmpty())

        s.failing.clear()
        val before = s.audio().size
        s.manager.retry(PlaylistDownloads.key("pl"))
        settle()
        assertEquals(listOf("B-t1"), s.audio().drop(before))
        assertTrue(s.playlists.isDownloaded("pl"))
    }

    @Test
    fun aFailureStopsTheSongStillComingDownAndRetryFetchesBoth() = runTest {
        val s = setup()
        // The other way round: one song fails while the other is still coming down.
        s.failing += "B-t1"
        val slow = s.gate("A-t1")
        download(s, "A-t1", "B-t1")
        assertEquals(DownloadStatus.FAILED, s.manager.state(PlaylistDownloads.key("pl"))?.status)
        assertFalse(LocalFiles.exists("${s.folder}/A-t1.flac"), "stopped with the failure, as an album's tracks are")
        assertFalse(LocalFiles.exists("${s.folder}/B-t1.flac"))
        assertEquals(ManifestStatus.FAILED, s.manifest().status)
        assertFalse(s.playlists.isDownloaded("pl"))

        s.failing.clear()
        slow.complete(Unit)
        s.gates.clear()
        val before = s.audio().size
        s.manager.retry(PlaylistDownloads.key("pl"))
        settle()
        assertEquals(listOf("A-t1", "B-t1"), s.audio().drop(before).sorted())
        assertEquals(setOf("A-t1.flac", "B-t1.flac", "playlist.json", "covers"), s.files())
        assertTrue(s.playlists.isDownloaded("pl"))
    }

    @Test
    fun removingADownloadDeletesItsFolderOnly() = runTest {
        val s = setup()
        downloadAlbum(s, "A")
        download(s, "A-t1", "B-t1")
        s.playlists.delete("pl")
        settle()
        assertFalse(LocalFiles.exists(s.folder))
        assertNull(s.playlists.saved("pl"))
        assertNull(s.manager.state(PlaylistDownloads.key("pl")))
        assertTrue(s.manager.isDownloaded("A"))
        assertTrue(LocalFiles.exists("${s.dir}/A/01 - Song A-t1.flac"))
    }
}
