package gg.dekiru.musicplayer.ui

import gg.dekiru.musicplayer.data.api.FakeServer
import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import gg.dekiru.musicplayer.data.api.path
import gg.dekiru.musicplayer.data.settings.FavoritesStore
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import gg.dekiru.musicplayer.playback.settle
import gg.dekiru.musicplayer.ui.home.AlbumList
import gg.dekiru.musicplayer.ui.home.AlbumListModel
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Home ▸ Favourites › and Random Albums ›. */
class AlbumListModelTest {

    private val items = """{"Items":[
        {"Id":"d2","Name":"Set (Disc 2)","AlbumArtists":[{"Name":"A"}]},
        {"Id":"x","Name":"Other"},
        {"Id":"d1","Name":"Set (Disc 1)","AlbumArtists":[{"Name":"A"}]}]}"""

    @Test
    fun favouritesNewestFirstWithDiscsMerged() = runTest {
        val server = FakeServer { json(items) }
        var clock = 1L
        val favorites = FavoritesStore(InMemoryPreferences()) { clock++ }
        favorites.record("x", true) // favourited here: goes first
        val model = AlbumListModel(AlbumList.FAVOURITES, server.api(token = "t", userId = "u"), favorites, backgroundScope)
        model.loadIfNeeded()
        settle()
        assertEquals(listOf("x", "d1"), model.albums.map { it.id })
        assertEquals("Set", model.albums[1].displayName)
        assertEquals("IsFavorite", server.requests.single().url.parameters["Filters"])
        assertFalse(model.isLoading)
    }

    @Test
    fun randomAsksForTwelveAndRefreshFetchesAgain() = runTest {
        val server = FakeServer { json(items) }
        val model = AlbumListModel(AlbumList.RANDOM, server.api(token = "t", userId = "u"), FavoritesStore(InMemoryPreferences()) { 0 }, backgroundScope)
        model.loadIfNeeded()
        settle()
        model.loadIfNeeded() // already loaded: no second fetch
        settle()
        assertEquals(1, server.requests.size)
        assertEquals("12", server.requests.single().url.parameters["limit"])
        assertEquals("Random", server.requests.single().url.parameters["SortBy"])
        model.refresh()
        settle()
        assertEquals(2, server.requests.size)
    }

    @Test
    fun randomShowsTwelveWhenTheirRowsFitElseNine() {
        val card = 210f
        // Three across wherever they fit, so 4 rows: 8 + 4 × 210 + 3 × 12 + 40 = 924.
        assertEquals(3, AlbumListModel.columns(1280f))
        assertEquals(3, AlbumListModel.columns(500f))
        assertEquals(12, AlbumListModel.randomCount(1280f, 924f, card))
        assertEquals(9, AlbumListModel.randomCount(1280f, 923f, card))
        assertEquals(12, AlbumListModel.randomCount(1280f, 884f, card, footer = 0f), "no \"Shake it\" on desktop")
        // A narrow phone gets two across; twelve would need six rows.
        assertEquals(2, AlbumListModel.columns(411f))
        assertEquals(9, AlbumListModel.randomCount(411f, 800f, card))
    }

    @Test
    fun failureShowsTheError() = runTest {
        val server = FakeServer { json("", HttpStatusCode.BadGateway) }
        val model = AlbumListModel(AlbumList.RANDOM, server.api(token = "t", userId = "u"), FavoritesStore(InMemoryPreferences()) { 0 }, backgroundScope)
        model.loadIfNeeded()
        settle()
        assertTrue(model.errorMessage!!.startsWith("HTTP 502"))
        assertTrue(server.requests.all { it.path == "/Users/u/Items" })
    }
}
