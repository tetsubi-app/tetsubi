package gg.dekiru.musicplayer.ui

import gg.dekiru.musicplayer.data.api.FakeServer
import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinPerson
import gg.dekiru.musicplayer.data.settings.FavoritesStore
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import gg.dekiru.musicplayer.downloads.DownloadedAlbum
import gg.dekiru.musicplayer.playback.settle
import gg.dekiru.musicplayer.ui.library.FavouritesOptions
import gg.dekiru.musicplayer.ui.library.FavouritesSort
import gg.dekiru.musicplayer.ui.library.LibraryDisplayMode
import gg.dekiru.musicplayer.ui.library.LibraryFilter
import gg.dekiru.musicplayer.ui.library.LibraryModel
import gg.dekiru.musicplayer.ui.library.LibrarySort
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Library ▸ Favourites' View, Sort and Filter (as iOS `FavouritesOptionsTests`). */
class FavouritesOptionsTest {

    private fun album(id: String, name: String, artist: String = "Artist", date: String? = null, year: Int? = null) =
        JellyfinAlbum(id = id, name = name, premiereDate = date, productionYear = year, albumArtists = listOf(JellyfinPerson(name = artist)))

    /** In favourited order (most recent first), as `FavoritesStore.ordered` gives. */
    private val favourites = listOf(
        album("1", "Zeitgeist", "Smashing Pumpkins", "2007-07-10T00:00:00.0000000Z", 2007),
        album("2", "adore", "Smashing Pumpkins", year = 1998),
        album("3", "Ágætis byrjun", "Sigur Rós", "1999-06-12T00:00:00.0000000Z", 1999),
        album("4", "1979", "Bowie"),
        album("5", "Mellon Collie", "Smashing Pumpkins", "1995-10-24T00:00:00.0000000Z", 1995),
        album("6", "Gish", "Smashing Pumpkins", year = 1998),
    )

    private fun arrange(sort: FavouritesSort, filter: LibraryFilter = LibraryFilter.ALL, offline: Boolean = false, downloaded: Set<String> = emptySet()) =
        FavouritesOptions.arrange(favourites, sort, filter, offline) { it.id in downloaded }.map { it.id }

    @Test
    fun dateFavouritedKeepsTheFavouritedOrder() {
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), arrange(FavouritesSort.DATE_FAVOURITED))
    }

    @Test
    fun nameArtistAndYearAreTheAlbumsSorts() {
        for ((sort, library) in listOf(FavouritesSort.NAME to LibrarySort.NAME, FavouritesSort.ARTIST to LibrarySort.ARTIST, FavouritesSort.YEAR to LibrarySort.YEAR)) {
            assertEquals(LibraryModel.sort(favourites, library).map { it.id }, arrange(sort), sort.title)
        }
    }

    @Test
    fun nameIsTheStripOrder() {
        assertEquals(listOf("4", "2", "3", "6", "5", "1"), arrange(FavouritesSort.NAME))
    }

    /** Newest full date first, else Jan 1 of the year, none last; ties keep the favourited order. */
    @Test
    fun yearNewestFirstTiesKeepTheOrder() {
        assertEquals(listOf("1", "3", "2", "6", "5", "4"), arrange(FavouritesSort.YEAR))
    }

    @Test
    fun downloadedFilter() {
        assertEquals(listOf("2", "5"), arrange(FavouritesSort.DATE_FAVOURITED, LibraryFilter.DOWNLOADED, downloaded = setOf("5", "2")))
        assertEquals(listOf("2", "5"), arrange(FavouritesSort.NAME, LibraryFilter.DOWNLOADED, downloaded = setOf("5", "2")))
        assertEquals(6, arrange(FavouritesSort.DATE_FAVOURITED, LibraryFilter.ALL, downloaded = setOf("5", "2")).size)
    }

    @Test
    fun offlineShowsTheDownloadedOnesWhateverTheFilter() {
        assertEquals(listOf("1", "6"), arrange(FavouritesSort.DATE_FAVOURITED, LibraryFilter.ALL, offline = true, downloaded = setOf("6", "1")))
    }

    @Test
    fun indexKeyForTheAlphabeticalSortsOnly() {
        val options = FavouritesOptions(InMemoryPreferences())
        assertNull(options.indexKey)
        options.sortOption = FavouritesSort.NAME
        assertEquals("Zeitgeist", options.indexKey?.invoke(favourites[0]))
        options.sortOption = FavouritesSort.ARTIST
        assertEquals("Smashing Pumpkins", options.indexKey?.invoke(favourites[0]))
        options.sortOption = FavouritesSort.YEAR
        assertNull(options.indexKey)
    }

    @Test
    fun defaults() {
        val options = FavouritesOptions(InMemoryPreferences())
        assertEquals(LibraryDisplayMode.GRID, options.displayMode)
        assertEquals(FavouritesSort.DATE_FAVOURITED, options.sortOption)
        assertEquals(LibraryFilter.ALL, options.filter)
    }

    @Test
    fun choicesAreRememberedUnderTheSharedKeys() {
        val prefs = InMemoryPreferences()
        val options = FavouritesOptions(prefs)
        options.displayMode = LibraryDisplayMode.LIST
        options.sortOption = FavouritesSort.YEAR
        options.filter = LibraryFilter.DOWNLOADED
        assertEquals("List", prefs.getString("library.favouritesViewMode"))
        assertEquals("Year", prefs.getString("library.favouritesSort"))
        assertEquals("Downloaded", prefs.getString("library.favouritesFilter"))

        val again = FavouritesOptions(prefs)
        assertEquals(LibraryDisplayMode.LIST, again.displayMode)
        assertEquals(FavouritesSort.YEAR, again.sortOption)
        assertEquals(LibraryFilter.DOWNLOADED, again.filter)

        again.sortOption = FavouritesSort.DATE_FAVOURITED
        assertEquals("Date Favourited", prefs.getString("library.favouritesSort"))
        assertEquals(FavouritesSort.DATE_FAVOURITED, FavouritesOptions(prefs).sortOption)
    }

    @Test
    fun strangeSavedValuesFallBackToTheDefaults() {
        val prefs = InMemoryPreferences()
        prefs.putString(FavouritesOptions.VIEW_KEY, "Mosaic")
        prefs.putString(FavouritesOptions.SORT_KEY, "Date Added") // an Albums sort, not one of these
        prefs.putString(FavouritesOptions.FILTER_KEY, "3")
        val options = FavouritesOptions(prefs)
        assertEquals(LibraryDisplayMode.GRID, options.displayMode)
        assertEquals(FavouritesSort.DATE_FAVOURITED, options.sortOption)
        assertEquals(LibraryFilter.ALL, options.filter)
    }

    @Test
    fun albumsChoicesUntouched() {
        val prefs = InMemoryPreferences()
        val options = FavouritesOptions(prefs)
        options.displayMode = LibraryDisplayMode.LIST
        options.sortOption = FavouritesSort.ARTIST
        assertNull(prefs.getString(LibraryModel.VIEW_KEY))
        assertNull(prefs.getString(LibraryModel.SORT_KEY))
    }

    /** The Library's Favourites tab follows the options; offline, the downloaded ones only. */
    @Test
    fun libraryFavouritesFollowTheOptions() = runTest {
        val server = FakeServer { json("""{"Items":[{"Id":"f1","Name":"Zebra"},{"Id":"f2","Name":"Apple"},{"Id":"f3","Name":"Mango"}]}""") }
        val prefs = InMemoryPreferences()
        val model = LibraryModel(server.api(token = "t", userId = "u"), FavoritesStore(InMemoryPreferences()) { 0 }, prefs, backgroundScope, Random(7))
        model.loadFavourites()
        settle()
        assertEquals(listOf("f1", "f2", "f3"), model.favourites.map { it.id })
        model.favouritesOptions.sortOption = FavouritesSort.NAME
        assertEquals(listOf("f2", "f3", "f1"), model.favourites.map { it.id })
        model.downloadsChanged(listOf(DownloadedAlbum("f1", "Zebra", "X", null, "flac", 0, 0, 0.0, emptyList(), "/d/f1"),
            DownloadedAlbum("f3", "Mango", "X", null, "flac", 0, 0, 0.0, emptyList(), "/d/f3")))
        model.favouritesOptions.filter = LibraryFilter.DOWNLOADED
        assertEquals(listOf("f3", "f1"), model.favourites.map { it.id })
        assertTrue(model.hasFavourites)
        model.favouritesOptions.filter = LibraryFilter.ALL
        model.networkChanged(online = false)
        assertEquals(listOf("f3", "f1"), model.favourites.map { it.id })
        // Remembered for the next launch, apart from the Albums tab's choices.
        assertEquals(FavouritesSort.NAME, LibraryModel(server.api(token = "t", userId = "u"), FavoritesStore(InMemoryPreferences()) { 0 }, prefs, backgroundScope).favouritesOptions.sortOption)
        assertEquals(LibrarySort.NAME, model.sortOption)
        assertFalse(prefs.getString(LibraryModel.SORT_KEY) != null)
    }
}
