package gg.dekiru.musicplayer.ui

import gg.dekiru.musicplayer.data.api.FakeServer
import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import gg.dekiru.musicplayer.data.api.path
import gg.dekiru.musicplayer.data.artist.ArtistInfoCache
import gg.dekiru.musicplayer.data.artist.ArtistInfoClient
import gg.dekiru.musicplayer.data.models.DiscGrouping
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinPerson
import gg.dekiru.musicplayer.data.settings.FavoritesStore
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.downloads.DownloadSettings
import gg.dekiru.musicplayer.downloads.DownloadedAlbum
import gg.dekiru.musicplayer.downloads.ManifestTrack
import gg.dekiru.musicplayer.downloads.tempDir
import gg.dekiru.musicplayer.playback.settle
import gg.dekiru.musicplayer.playback.writeDownload
import gg.dekiru.musicplayer.ui.album.AlbumModel
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A downloaded album's page opens offline (or with the server down) from its manifest. */
class AlbumOfflineTest {

    private object NoCache : ArtistInfoCache {
        override fun load(key: String): String? = null
        override fun save(key: String, text: String) {}
    }

    private class Setup(val server: FakeServer, val manager: DownloadManager) {
        var online = true
        var serverDown = false
    }

    private fun TestScope.setup(dir: String): Setup {
        lateinit var s: Setup
        val server = FakeServer { request ->
            if (s.serverDown) return@FakeServer json("down", HttpStatusCode.ServiceUnavailable)
            when (request.path) {
                "/Items" -> json("""{"Items":[{"Id":"srv-t1","Name":"From server","IndexNumber":1,"AlbumId":"al"}]}""")
                else -> json("""{"Items":[]}""")
            }
        }
        val manager = DownloadManager(server.api(token = "t", userId = "u"), dir, DownloadSettings(InMemoryPreferences()), backgroundScope)
        s = Setup(server, manager)
        return s
    }

    private fun TestScope.model(s: Setup, album: JellyfinAlbum) = AlbumModel(
        album, s.server.api(token = "t", userId = "u"), FavoritesStore(InMemoryPreferences()) { 0 },
        ArtistInfoClient(NoCache, { 0L }), backgroundScope, downloads = { s.manager }, isOnline = { s.online },
    )

    private val album = JellyfinAlbum(id = "al", name = "Album", albumArtists = listOf(JellyfinPerson(id = "ar", name = "Band")))

    @Test
    fun offlineTheTracksComeFromTheManifest() = runTest {
        val dir = tempDir()
        writeDownload(dir, "al", "Album")
        val s = setup(dir)
        s.online = false
        val model = model(s, album)
        model.load()
        settle()

        assertTrue(s.server.requests.isEmpty(), "nothing asked of the server offline")
        assertEquals(listOf("al-t1", "al-t2", "al-t3"), model.tracks.map { it.id })
        assertEquals(listOf("Song 1 (Live?)", "Song 2 (Live?)", "Song 3 (Live?)"), model.tracks.map { it.name })
        assertEquals(listOf(1, 2, 3), model.tracks.map { it.trackNumber })
        assertEquals(listOf(60L, 120L, 180L), model.tracks.map { it.durationSeconds })
        assertNull(model.errorMessage)
        assertTrue(model.moreByArtist.isEmpty())
        assertNull(model.artistInfo)
        assertTrue(model.allTracksFromThisAlbum)
    }

    @Test
    fun theServerFailingFallsBackToTheDownload() = runTest {
        val dir = tempDir()
        writeDownload(dir, "al", "Album")
        val s = setup(dir)
        s.serverDown = true
        val model = model(s, album)
        model.load()
        settle()
        assertEquals(listOf("al-t1", "al-t2", "al-t3"), model.tracks.map { it.id })
        assertNull(model.errorMessage)
    }

    @Test
    fun onlineTheServersListIsUsed() = runTest {
        val dir = tempDir()
        writeDownload(dir, "al", "Album")
        val s = setup(dir)
        val model = model(s, album)
        model.load()
        settle()
        assertEquals(listOf("srv-t1"), model.tracks.map { it.id })
    }

    @Test
    fun anOfflineSetShowsEachDownloadedDisc() = runTest {
        val dir = tempDir()
        writeDownload(dir, "d1", "Set (Disc 1)", tracks = 2)
        writeDownload(dir, "d2", "Set (Disc 2)", tracks = 1)
        val s = setup(dir)
        s.online = false
        // As offline Home makes it: the downloaded discs merged.
        val set = DiscGrouping.merge(
            listOf("d1" to "Set (Disc 1)", "d2" to "Set (Disc 2)").map { (id, name) ->
                JellyfinAlbum(id = id, name = name, albumArtists = listOf(JellyfinPerson(name = "Band")))
            },
        ).single()
        val model = model(s, set)
        model.load()
        settle()
        assertTrue(s.server.requests.isEmpty())
        assertEquals(listOf("d1", "d2"), model.tracksByDisc.map { it.first.id })
        assertEquals(listOf("d1-t1", "d1-t2", "d2-t1"), model.tracks.map { it.id })
    }

    @Test
    fun discNumbersOfOneDownloadedAlbum() {
        fun t(n: Int) = ManifestTrack(number = n, trackId = "t$n", title = "S$n", file = "$n.flac", durationSeconds = 1.0)
        val album = DownloadedAlbum("al", "Album", "Band", null, "original", 0, 0, 0.0, listOf(t(1), t(2), t(201), t(202)), "/x")
        val tracks = AlbumModel.downloadedTracks(album)
        assertEquals(listOf(1, 2, 1, 2), tracks.map { it.trackNumber })
        assertEquals(listOf(1, 1, 2, 2), tracks.map { it.discNumber })
        assertEquals(listOf(1 to 2, 2 to 2), DiscGrouping.byDiscNumber(tracks)!!.map { it.first to it.second.size })

        val plain = AlbumModel.downloadedTracks(album.copy(tracks = listOf(t(1), t(12))))
        assertEquals(listOf(1, 12), plain.map { it.trackNumber })
        assertEquals(listOf(null, null), plain.map { it.discNumber })
    }
}
