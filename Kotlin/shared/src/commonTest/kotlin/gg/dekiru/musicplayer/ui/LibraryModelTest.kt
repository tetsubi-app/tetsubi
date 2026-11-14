package gg.dekiru.musicplayer.ui

import gg.dekiru.musicplayer.data.api.FakeServer
import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import gg.dekiru.musicplayer.data.api.path
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinArtist
import gg.dekiru.musicplayer.data.models.JellyfinPerson
import gg.dekiru.musicplayer.data.settings.FavoritesStore
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import gg.dekiru.musicplayer.downloads.DownloadedAlbum
import gg.dekiru.musicplayer.playback.settle
import gg.dekiru.musicplayer.ui.library.AlphaIndex
import gg.dekiru.musicplayer.ui.library.ArtistModel
import gg.dekiru.musicplayer.ui.library.LibraryDisplayMode
import gg.dekiru.musicplayer.ui.library.LibraryFilter
import gg.dekiru.musicplayer.ui.library.LibraryModel
import gg.dekiru.musicplayer.ui.library.LibrarySort
import gg.dekiru.musicplayer.ui.library.LibraryTab
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Library: sorting, filtering, the A–Z index, Random's pages, offline, remembered choices. */
class LibraryModelTest {

    private fun album(name: String, artist: String = "X", year: Int? = null, id: String = "id-$name") =
        JellyfinAlbum(id = id, name = name, productionYear = year, albumArtists = listOf(JellyfinPerson(name = artist)))

    private fun albums(count: Int) = (0 until count).map { album("Album $it", id = "a$it") }

    private fun downloaded(id: String, title: String, artist: String = "Band", at: Long = 0) =
        DownloadedAlbum(id, title, artist, null, "flac", at, 0, 0.0, emptyList(), "/d/$id")

    private fun model(
        scope: CoroutineScope,
        server: FakeServer = FakeServer { json("""{"Items":[]}""") },
        prefs: InMemoryPreferences = InMemoryPreferences(),
    ) = LibraryModel(server.api(token = "t", userId = "u"), FavoritesStore(InMemoryPreferences()) { 0 }, prefs, scope, Random(7))

    // A–Z index

    @Test
    fun indexBuckets() {
        assertEquals("A", AlphaIndex.bucket("Abbey Road"))
        assertEquals("E", AlphaIndex.bucket("émile"))
        assertEquals("#", AlphaIndex.bucket("13 Songs"))
        assertEquals("#", AlphaIndex.bucket("...out flew reason"))
        assertEquals("#", AlphaIndex.bucket(""))
        assertEquals("T", AlphaIndex.bucket("  the band"))
        assertEquals(AlphaIndex.OTHER, AlphaIndex.bucket("ヘイトスピーチ"))
        assertEquals(AlphaIndex.OTHER, AlphaIndex.bucket("Привет"))
    }

    @Test
    fun indexLabelOrder() {
        assertEquals("#", AlphaIndex.labels.first())
        assertEquals(AlphaIndex.OTHER, AlphaIndex.labels.last())
        assertEquals(28, AlphaIndex.labels.size)
        assertEquals("A", AlphaIndex.labels[1])
    }

    @Test
    fun indexJumpsToTheLetterOrTheNextWithItems() {
        val list = listOf(album("Abba"), album("Air"), album("Cream"), album("ゆらゆら"))
        val first = AlphaIndex.firstIds(list, { it.displayName }) { it.id }
        assertEquals(mapOf("A" to "id-Abba", "C" to "id-Cream", AlphaIndex.OTHER to "id-ゆらゆら"), first)
        assertEquals("id-Cream", AlphaIndex.target("B", first))
        assertEquals("id-ゆらゆら", AlphaIndex.target("Z", first))
        assertEquals("id-Abba", AlphaIndex.target("#", first))
        assertNull(AlphaIndex.target("A", emptyMap()))
    }

    // Sorting

    @Test
    fun sortByNameFollowsTheIndex() {
        val list = listOf(album("Zebra"), album("ゆらゆら"), album("émile"), album("10 Songs"), album("apple"), album("Mango"))
        assertEquals(
            listOf("10 Songs", "apple", "émile", "Mango", "Zebra", "ゆらゆら"),
            LibraryModel.sort(list, LibrarySort.NAME).map { it.displayName },
        )
    }

    @Test
    fun sortByArtist() {
        val list = listOf(album("A", artist = "Zebra"), album("B", artist = "Alpha"), album("C", artist = "Delta"))
        assertEquals(listOf("Alpha", "Delta", "Zebra"), LibraryModel.sort(list, LibrarySort.ARTIST).map { it.artistName })
    }

    @Test
    fun sortByYearNewestFirstNoYearLast() {
        val list = listOf(album("NoYear"), album("Old", year = 2000), album("New", year = 2025), album("Mid", year = 2015))
        assertEquals(listOf(2025, 2015, 2000, null), LibraryModel.sort(list, LibrarySort.YEAR).map { it.productionYear })
    }

    @Test
    fun dateAddedKeepsTheServersOrder() {
        val list = listOf(album("First"), album("Second"), album("Third"))
        assertEquals(list, LibraryModel.sort(list, LibrarySort.DATE_ADDED))
    }

    @Test
    fun theIndexOnlyForAlphabeticalSorts() = runTest {
        val model = model(backgroundScope)
        model.sortOption = LibrarySort.NAME
        assertEquals("T", model.indexKey!!(album("T")))
        model.sortOption = LibrarySort.ARTIST
        assertEquals("Band", model.indexKey!!(album("T", artist = "Band")))
        model.sortOption = LibrarySort.YEAR
        assertNull(model.indexKey)
        model.sortOption = LibrarySort.RANDOM
        assertNull(model.indexKey)
    }

    // Grid

    @Test
    fun threeAcrossOnAPhoneWithTheStrip() {
        assertEquals(3, LibraryModel.gridLayout(393f - 4 - 22).columns)
        assertEquals(3.5f, LibraryModel.gridLayout(393f - 4 - 22).spacing, 0.01f)
        assertEquals(8f, LibraryModel.gridLayout(402f - 4 - 22).spacing, 0.01f)
        assertEquals(2, LibraryModel.gridLayout(375f - 4 - 22).columns)
        assertEquals(3, LibraryModel.gridLayout(411f - 4 - 22).columns) // a typical Android phone
        assertEquals(LibraryModel.gridLayout(1000f).columns, 7)
        assertEquals(12f, LibraryModel.gridLayout(1000f).spacing)
    }

    // Random

    private fun randomModel(scope: CoroutineScope, count: Int): LibraryModel {
        val model = model(scope)
        model.receive(albums(count))
        model.sortOption = LibrarySort.RANDOM
        return model
    }

    @Test
    fun randomShowsAPageThenMoreAtTheEnd() = runTest {
        val model = randomModel(backgroundScope, 60)
        assertEquals(24, model.sortedAlbums.size)
        model.loadMoreIfNeeded(model.sortedAlbums[10]) // not the last: nothing
        assertEquals(24, model.sortedAlbums.size)
        model.loadMoreIfNeeded(model.sortedAlbums.last())
        assertEquals(48, model.sortedAlbums.size)
        model.loadMoreIfNeeded(model.sortedAlbums.last())
        assertEquals(60, model.sortedAlbums.size)
        model.loadMoreIfNeeded(model.sortedAlbums.last())
        assertEquals(60, model.sortedAlbums.size)
        assertEquals(albums(60).map { it.id }.toSet(), model.sortedAlbums.map { it.id }.toSet())
    }

    @Test
    fun randomOrderSurvivesARefreshAndNewAlbumsGoLast() = runTest {
        val model = randomModel(backgroundScope, 60)
        model.loadMoreIfNeeded(model.sortedAlbums.last())
        val before = model.sortedAlbums.map { it.id }
        model.receive(albums(61))
        assertEquals(before, model.sortedAlbums.map { it.id }.take(48))
    }

    @Test
    fun shufflingAgainStartsOverAtOnePage() = runTest {
        val model = randomModel(backgroundScope, 60)
        val first = model.sortedAlbums.map { it.id }
        model.loadMoreIfNeeded(model.sortedAlbums.last())
        model.shuffleAgain()
        assertEquals(24, model.sortedAlbums.size)
        assertNotEquals(first, model.sortedAlbums.map { it.id })
        assertEquals(1, model.shuffles)
    }

    @Test
    fun otherSortsShowEverything() = runTest {
        val model = randomModel(backgroundScope, 60)
        model.sortOption = LibrarySort.NAME
        assertEquals(60, model.sortedAlbums.size)
    }

    @Test
    fun pullToReshuffleFetchesAgain() = runTest {
        val server = FakeServer { json("""{"Items":[{"Id":"a"},{"Id":"b"},{"Id":"c"}]}""") }
        val model = model(backgroundScope, server)
        model.sortOption = LibrarySort.RANDOM
        model.refreshRandom()
        settle()
        assertEquals(setOf("a", "b", "c"), model.sortedAlbums.map { it.id }.toSet())
        assertEquals(1, server.requests.size)
        assertFalse(model.isRefreshing)
    }

    // Filter and downloads

    @Test
    fun downloadedFilterNeedsEveryDisc() = runTest {
        val model = model(backgroundScope)
        model.receive(listOf(album("Set (Disc 1)", id = "d1"), album("Set (Disc 2)", id = "d2"), album("Solo", id = "s")))
        model.downloadsChanged(listOf(downloaded("d1", "Set (Disc 1)"), downloaded("s", "Solo")))
        model.filter = LibraryFilter.DOWNLOADED
        assertEquals(listOf("s"), model.sortedAlbums.map { it.id })
        model.downloadsChanged(listOf(downloaded("d1", "Set (Disc 1)"), downloaded("d2", "Set (Disc 2)"), downloaded("s", "Solo")))
        assertEquals(listOf("d1", "s"), model.sortedAlbums.map { it.id })
        // Being downloaded again doesn't count.
        model.downloadsChanged(listOf(downloaded("s", "Solo")), activeIds = setOf("s"))
        assertEquals(emptyList(), model.sortedAlbums)
        model.filter = LibraryFilter.ALL
        assertEquals(listOf("Set", "Solo"), model.sortedAlbums.map { it.displayName })
    }

    @Test
    fun offlineShowsTheDownloadedAlbumsAndAsksNothing() = runTest {
        val server = FakeServer { json("""{"Items":[{"Id":"a","Name":"Server Album"}]}""") }
        val model = model(backgroundScope, server)
        model.downloadsChanged(listOf(downloaded("x", "Kept")))
        model.networkChanged(online = false)
        settle()
        assertTrue(server.requests.isEmpty())
        assertTrue(model.isOffline)
        assertEquals(listOf("x"), model.sortedAlbums.map { it.id })

        model.networkChanged(online = true)
        settle()
        assertEquals(listOf("a"), model.sortedAlbums.map { it.id })
        assertEquals("/Users/u/Items", server.requests.single().path)
    }

    @Test
    fun aFailedFirstLoadShowsTheError() = runTest {
        val server = FakeServer { json("", HttpStatusCode.BadGateway) }
        val model = model(backgroundScope, server)
        model.networkChanged(online = true)
        settle()
        assertTrue(model.errorMessage!!.startsWith("Failed to load library: HTTP 502"))
        assertFalse(model.isLoading)
    }

    @Test
    fun aFailedReloadKeepsWhatsShown() = runTest {
        var fail = false
        val server = FakeServer { if (fail) json("", HttpStatusCode.BadGateway) else json("""{"Items":[{"Id":"a"}]}""") }
        val model = model(backgroundScope, server)
        model.load()
        settle()
        fail = true
        model.load()
        settle()
        assertNull(model.errorMessage)
        assertEquals(listOf("a"), model.sortedAlbums.map { it.id })
    }

    // Remembered choices

    @Test
    fun tabSortAndViewAreRemembered() = runTest {
        val prefs = InMemoryPreferences()
        val first = model(backgroundScope, prefs = prefs)
        assertEquals(LibraryTab.ALBUMS, first.tab)
        assertEquals(LibrarySort.NAME, first.sortOption)
        assertEquals(LibraryDisplayMode.GRID, first.displayMode)
        first.tab = LibraryTab.ARTISTS
        first.sortOption = LibrarySort.DATE_ADDED
        first.displayMode = LibraryDisplayMode.LIST
        first.filter = LibraryFilter.DOWNLOADED
        assertEquals("Date Added", prefs.getString(LibraryModel.SORT_KEY))

        val again = model(backgroundScope, prefs = prefs)
        assertEquals(LibraryTab.ARTISTS, again.tab)
        assertEquals(LibrarySort.DATE_ADDED, again.sortOption)
        assertEquals(LibraryDisplayMode.LIST, again.displayMode)
        assertEquals(LibraryFilter.ALL, again.filter, "the filter isn't kept")
    }

    @Test
    fun aStrangeSavedValueFallsBackToTheDefault() = runTest {
        val prefs = InMemoryPreferences()
        prefs.putString(LibraryModel.SORT_KEY, "Colour")
        assertEquals(LibrarySort.NAME, model(backgroundScope, prefs = prefs).sortOption)
    }

    // Artists and Favourites

    @Test
    fun artistsSortedLikeTheStripAndLoadedOnce() = runTest {
        val server = FakeServer { json("""{"Items":[{"Id":"1","Name":"Ólafur"},{"Id":"2","Name":"!!!"},{"Id":"3","Name":"Air"},{"Id":"4","Name":"坂本龍一"}]}""") }
        val model = model(backgroundScope, server)
        model.loadArtists()
        settle()
        assertEquals(listOf("!!!", "Air", "Ólafur", "坂本龍一"), model.artists.map { it.name })
        model.loadArtists()
        settle()
        assertEquals(1, server.requests.size)
    }

    @Test
    fun favouritesOfflineAreTheDownloadedOnes() = runTest {
        val server = FakeServer { json("""{"Items":[{"Id":"f1","Name":"One"},{"Id":"f2","Name":"Two"}]}""") }
        val model = model(backgroundScope, server)
        model.loadFavourites()
        settle()
        assertEquals("IsFavorite", server.requests.single().url.parameters["Filters"])
        assertEquals(listOf("f1", "f2"), model.favourites.map { it.id })
        model.downloadsChanged(listOf(downloaded("f2", "Two")))
        model.networkChanged(online = false)
        model.loadFavourites()
        settle()
        assertEquals(listOf("f2"), model.favourites.map { it.id })
        assertTrue(model.favouritesLoaded)
    }

    @Test
    fun anArtistsPageOfflineShowsTheirDownloads() = runTest {
        val server = FakeServer { json("""{"Items":[{"Id":"b1","Name":"Bleach"},{"Id":"n1","Name":"Nevermind"}]}""") }
        val model = ArtistModel(JellyfinArtist("ar", "Nirvana"), server.api(token = "t", userId = "u"), backgroundScope)
        val downloads = listOf(album("Nevermind", artist = "nirvana", id = "n1"), album("Other", artist = "Someone", id = "o"))
        // Never loaded: matched by name from the downloads.
        assertEquals(listOf("n1"), model.shown(offline = true, downloads) { it.id == "n1" }.map { it.id })
        model.loadIfNeeded()
        settle()
        assertEquals("ar", server.requests.single().url.parameters["AlbumArtistIds"])
        assertEquals(listOf("b1", "n1"), model.shown(offline = false, downloads) { false }.map { it.id })
        assertEquals(listOf("n1"), model.shown(offline = true, downloads) { it.id == "n1" }.map { it.id })
    }
}
