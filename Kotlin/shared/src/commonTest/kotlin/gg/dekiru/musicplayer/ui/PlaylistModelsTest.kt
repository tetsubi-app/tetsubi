package gg.dekiru.musicplayer.ui

import gg.dekiru.musicplayer.data.api.FakeServer
import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import gg.dekiru.musicplayer.data.api.path
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinPlaylist
import gg.dekiru.musicplayer.data.models.JellyfinTrack
import gg.dekiru.musicplayer.data.models.PlaylistEntry
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import gg.dekiru.musicplayer.playback.settle
import gg.dekiru.musicplayer.ui.playlist.PlaylistModel
import gg.dekiru.musicplayer.ui.playlist.PlaylistSort
import gg.dekiru.musicplayer.ui.playlist.PlaylistsModel
import gg.dekiru.musicplayer.ui.playlist.entryDetail
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Playlists tab's list (sorting, texts, offline) and a playlist's page (counts from its loaded tracks, art). */
class PlaylistModelsTest {

    private fun playlist(name: String, created: String? = null, songs: Int = 0, seconds: Long = 0, art: Boolean = false) =
        JellyfinPlaylist(id = "id-$name", name = name, songCount = songs, runTimeTicks = seconds * 10_000_000, dateCreated = created, hasArt = art)

    private fun listJson(vararg items: String) = """{"Items":[${items.joinToString(",")}],"TotalRecordCount":${items.size}}"""

    private fun item(name: String, created: String, songs: Int = 2, art: Boolean = false) =
        """{"Id":"id-$name","Name":"$name","Type":"Playlist","DateCreated":"$created","ChildCount":$songs,"CumulativeRunTimeTicks":6000000000,
            "UserData":{"PlayCount":0},"ImageTags":{${if (art) """"Primary":"tag"""" else ""}}}"""

    private fun song(id: String, album: String?, seconds: Long) = """
        {"Id":"$id","PlaylistItemId":"$id","Name":"Song $id","RunTimeTicks":${seconds * 10_000_000}
         ${album?.let { ""","AlbumId":"$it","Album":"Album $it","AlbumArtist":"Band $it"""" }.orEmpty()}}
    """

    // Sorting

    @Test
    fun sortsByName() {
        val sorted = PlaylistsModel.sort(listOf(playlist("zebra"), playlist("The Mix"), playlist("apple"), playlist("Éclair")), PlaylistSort.NAME)
        assertEquals(listOf("apple", "Éclair", "The Mix", "zebra"), sorted.map { it.name })
    }

    @Test
    fun sortsByDateAddedNewestFirst() {
        val sorted = PlaylistsModel.sort(
            listOf(
                playlist("old", created = "2025-01-01T00:00:00.0000000Z"),
                playlist("undated b"),
                playlist("new", created = "2026-11-11T23:28:15.0000000Z"),
                playlist("undated a"),
                playlist("mid", created = "2026-03-01T00:00:00.0000000Z"),
            ),
            PlaylistSort.DATE_ADDED,
        )
        assertEquals(listOf("new", "mid", "old", "undated a", "undated b"), sorted.map { it.name })
    }

    @Test
    fun sameDateSortsByName() {
        val day = "2026-03-01T00:00:00.0000000Z"
        val sorted = PlaylistsModel.sort(listOf(playlist("b", created = day), playlist("c", created = day), playlist("a", created = day)), PlaylistSort.DATE_ADDED)
        assertEquals(listOf("a", "b", "c"), sorted.map { it.name })
    }

    @Test
    fun sortsOnOfferAreNameAndDateAdded() = runTest {
        val prefs = InMemoryPreferences()
        val model = PlaylistsModel(FakeServer { json(listJson()) }.api(token = "t", userId = "u"), prefs, backgroundScope)
        assertEquals(listOf("Name", "Date Added"), model.sortOptions.map { it.title })
        // A sort saved by an older version that's no longer offered: Name.
        prefs.putString(PlaylistsModel.SORT_KEY, "Recently Played")
        assertEquals(PlaylistSort.NAME, PlaylistsModel(FakeServer { json(listJson()) }.api(token = "t", userId = "u"), prefs, backgroundScope).sortOption)
    }

    // Texts

    @Test
    fun summaryText() {
        assertEquals("1 song", PlaylistsModel.songCount(1))
        assertEquals("12 songs", PlaylistsModel.songCount(12))
        assertEquals("12 min", PlaylistsModel.playingTime(12 * 60))
        assertEquals("1 h 12 min", PlaylistsModel.playingTime(72 * 60 + 10))
        assertEquals("2 h", PlaylistsModel.playingTime(7200))
        assertEquals("1 min", PlaylistsModel.playingTime(20))
        assertEquals("16 min", PlaylistsModel.playingTime(1014), "whole minutes, rounded down")
        assertEquals("1 h", PlaylistsModel.playingTime(3659))
        assertEquals("4 songs · 16 min", PlaylistsModel.summary(playlist("x", songs = 4, seconds = 1014)))
        assertEquals("23 songs · 1 h 12 min", PlaylistsModel.summary(23, 72 * 60))
        assertEquals("0 songs", PlaylistsModel.summary(playlist("empty")))
        assertEquals("0 songs · 10 min", PlaylistsModel.summary(0, 600), "a count that lags behind the time still shows the time")
    }

    @Test
    fun rowDetailIsArtistThenAlbum() {
        val track = JellyfinTrack(id = "t")
        assertEquals("dark star · ...out flew reason", entryDetail(PlaylistEntry(entryId = "t", track = track, albumName = "...out flew reason", albumArtist = "dark star")))
        assertEquals("dark star", entryDetail(PlaylistEntry(entryId = "t", track = track, albumArtist = "dark star")))
        assertEquals("", entryDetail(PlaylistEntry(entryId = "t", track = track)))
    }

    // The list

    @Test
    fun loadsAndSortsAndRemembersTheSort() = runTest {
        val server = FakeServer { json(listJson(item("b", "2026-01-01T00:00:00Z"), item("a", "2025-01-01T00:00:00Z"))) }
        val prefs = InMemoryPreferences()
        val model = PlaylistsModel(server.api(token = "t", userId = "u"), prefs, backgroundScope)
        assertFalse(model.loaded)
        model.load()
        assertTrue(model.isLoading)
        settle()
        assertTrue(model.loaded)
        assertFalse(model.isLoading)
        assertEquals(listOf("a", "b"), model.playlists.map { it.name })
        model.sortOption = PlaylistSort.DATE_ADDED
        assertEquals(listOf("b", "a"), model.playlists.map { it.name })
        assertEquals(PlaylistSort.DATE_ADDED, PlaylistsModel(server.api(token = "t", userId = "u"), prefs, backgroundScope).sortOption)
    }

    @Test
    fun aFailedLoadSaysSoAndRetryWorks() = runTest {
        var fail = true
        val server = FakeServer { if (fail) json("", HttpStatusCode.InternalServerError) else json(listJson(item("a", "2025-01-01T00:00:00Z"))) }
        val model = PlaylistsModel(server.api(token = "t", userId = "u"), InMemoryPreferences(), backgroundScope)
        model.load()
        settle()
        assertTrue(model.errorMessage.orEmpty().startsWith("Couldn't load your playlists."), model.errorMessage)
        assertFalse(model.loaded)
        assertFalse(model.isLoading)
        fail = false
        model.load()
        settle()
        assertNull(model.errorMessage)
        assertEquals(listOf("a"), model.playlists.map { it.name })
        // A later failure keeps what was listed.
        fail = true
        model.load()
        assertFalse(model.isLoading, "no spinner over a list")
        settle()
        assertEquals(listOf("a"), model.playlists.map { it.name })
        assertTrue(model.errorMessage != null)
    }

    @Test
    fun offlineListsNothingAndAsksNothing() = runTest {
        val server = FakeServer { json(listJson(item("a", "2025-01-01T00:00:00Z"))) }
        val model = PlaylistsModel(server.api(token = "t", userId = "u"), InMemoryPreferences(), backgroundScope)
        model.networkChanged(online = false)
        model.load()
        settle()
        assertTrue(model.isOffline)
        assertTrue(server.requests.isEmpty())
        assertEquals(emptyList(), model.playlists)
        assertNull(model.errorMessage)
        model.networkChanged(online = true)
        settle()
        assertEquals(listOf("a"), model.playlists.map { it.name })
        model.networkChanged(online = false)
        assertEquals(emptyList(), model.playlists, "hidden again while offline")
    }

    // A playlist's page

    @Test
    fun countsComeFromTheLoadedTracks() = runTest {
        val server = FakeServer { json("""{"Items":[${song("s1", "A", 200)},${song("s2", "B", 250)},${song("s1", "A", 200)}]}""") }
        // The list said 2 songs, 10 min: out of date.
        val model = PlaylistModel(playlist("Mix", songs = 2, seconds = 600), server.api(token = "t", userId = "u"), backgroundScope)
        assertEquals("2 songs · 10 min", model.summary, "the server's numbers until the tracks are in")
        model.load()
        assertTrue(model.isLoading)
        settle()
        assertTrue(model.loaded)
        assertEquals("/Playlists/id-Mix/Items", server.requests.single().path)
        assertEquals(3, model.songCount)
        assertEquals(650, model.durationSeconds)
        assertEquals("3 songs · 10 min", model.summary)
        assertEquals(listOf(0, 1, 2), model.entries.map { it.position })
    }

    @Test
    fun anEmptyPlaylistCountsNothing() = runTest {
        val server = FakeServer { json("""{"Items":[]}""") }
        val model = PlaylistModel(playlist("Mix", songs = 2, seconds = 600), server.api(token = "t", userId = "u"), backgroundScope)
        model.load()
        settle()
        assertEquals("0 songs", model.summary)
        assertEquals("id-Mix", model.artItemId, "nothing to borrow a picture from: the placeholder")
    }

    @Test
    fun artIsThePlaylistsOwnElseTheFirstTracksAlbum() = runTest {
        val server = FakeServer { json("""{"Items":[${song("s1", "A", 200)},${song("s2", "B", 250)}]}""") }
        val api = server.api(token = "t", userId = "u")
        val plain = PlaylistModel(playlist("Plain"), api, backgroundScope)
        assertEquals("id-Plain", plain.artItemId)
        plain.load()
        settle()
        assertEquals("A", plain.artItemId)
        val pictured = PlaylistModel(playlist("Pictured", art = true), api, backgroundScope)
        pictured.load()
        settle()
        assertEquals("id-Pictured", pictured.artItemId)
    }

    @Test
    fun aMissingPlaylistSaysSo() = runTest {
        val server = FakeServer { json("", HttpStatusCode.NotFound) }
        val model = PlaylistModel(playlist("Gone", songs = 2, seconds = 600), server.api(token = "t", userId = "u"), backgroundScope)
        model.load()
        settle()
        assertTrue(model.errorMessage.orEmpty().startsWith("Couldn't load this playlist."), model.errorMessage)
        assertFalse(model.loaded)
        assertFalse(model.isLoading)
        assertEquals("2 songs · 10 min", model.summary)
    }

    @Test
    fun goToAlbumFetchesTheAlbumById() = runTest {
        val server = FakeServer { request ->
            when (request.path) {
                "/Playlists/id-Mix/Items" -> json("""{"Items":[${song("s1", "A", 200)},${song("s2", null, 100)}]}""")
                "/Users/u/Items/A" -> json("""{"Id":"A","Name":"Album A","Type":"MusicAlbum"}""")
                else -> json("", HttpStatusCode.NotFound)
            }
        }
        val model = PlaylistModel(playlist("Mix"), server.api(token = "t", userId = "u"), backgroundScope)
        model.load()
        settle()
        val opened = mutableListOf<JellyfinAlbum>()
        model.openAlbum(model.entries[0], opened::add)
        settle()
        assertEquals(listOf("A"), opened.map { it.id })
        assertEquals("Album A", opened.single().displayName)
        // A song with no album: nothing to open.
        model.openAlbum(model.entries[1], opened::add)
        settle()
        assertEquals(1, opened.size)
        assertEquals(2, server.requests.size)
        assertNull(model.albumError)
    }

    @Test
    fun goToAlbumThatFailsSaysSo() = runTest {
        val server = FakeServer { request ->
            if (request.path.startsWith("/Playlists")) json("""{"Items":[${song("s1", "A", 200)}]}""") else json("", HttpStatusCode.NotFound)
        }
        val model = PlaylistModel(playlist("Mix"), server.api(token = "t", userId = "u"), backgroundScope)
        model.load()
        settle()
        val opened = mutableListOf<JellyfinAlbum>()
        model.openAlbum(model.entries[0], opened::add)
        settle()
        assertTrue(opened.isEmpty())
        assertTrue(model.albumError.orEmpty().startsWith("Couldn't open the album."), model.albumError)
    }
}
