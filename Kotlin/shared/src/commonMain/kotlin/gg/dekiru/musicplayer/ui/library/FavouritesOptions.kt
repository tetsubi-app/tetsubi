package gg.dekiru.musicplayer.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.settings.Preferences

/**
 * How Library ▸ Favourites is ordered (as `FavouritesSortOption` on iOS).
 * Date Favourited is the order `FavoritesStore.ordered` gives (most
 * recently favourited first); the rest are the Albums tab's sorts.
 */
enum class FavouritesSort(val title: String, val librarySort: LibrarySort?) {
    DATE_FAVOURITED("Date Favourited", null),
    NAME("Name", LibrarySort.NAME),
    ARTIST("Artist", LibrarySort.ARTIST),
    YEAR("Year", LibrarySort.YEAR),
}

/**
 * Library ▸ Favourites' View, Sort and Filter (as iOS `FavouritesOptions`),
 * remembered with the Library's other choices. One for the app (held by
 * [LibraryModel]), so Home ▸ Favourites follows the choices made in the Library.
 */
class FavouritesOptions(private val preferences: Preferences) {
    private var _display by mutableStateOf(saved(VIEW_KEY, LibraryDisplayMode.entries, LibraryDisplayMode.GRID) { it.title })
    var displayMode: LibraryDisplayMode
        get() = _display
        set(value) {
            _display = value
            preferences.putString(VIEW_KEY, value.title)
        }

    private var _sort by mutableStateOf(saved(SORT_KEY, FavouritesSort.entries, FavouritesSort.DATE_FAVOURITED) { it.title })
    var sortOption: FavouritesSort
        get() = _sort
        set(value) {
            _sort = value
            preferences.putString(SORT_KEY, value.title)
        }

    private var _filter by mutableStateOf(saved(FILTER_KEY, LibraryFilter.entries, LibraryFilter.ALL) { it.title })
    var filter: LibraryFilter
        get() = _filter
        set(value) {
            _filter = value
            preferences.putString(FILTER_KEY, value.title)
        }

    /** The key the A–Z strip is built from; null when the sort isn't alphabetical (as the Albums tab). */
    val indexKey: ((JellyfinAlbum) -> String)?
        get() = when (sortOption) {
            FavouritesSort.NAME -> { album -> album.displayName }
            FavouritesSort.ARTIST -> { album -> album.artistName }
            else -> null
        }

    /** The favourites (in favourited order) as the tab shows them, with these choices. */
    fun arrange(favourites: List<JellyfinAlbum>, offline: Boolean, isDownloaded: (JellyfinAlbum) -> Boolean): List<JellyfinAlbum> =
        arrange(favourites, sortOption, filter, offline, isDownloaded)

    // Unknown saved values fall back to the defaults.
    private fun <T> saved(key: String, values: List<T>, default: T, name: (T) -> String): T =
        preferences.getString(key)?.let { value -> values.firstOrNull { name(it) == value } } ?: default

    companion object {
        // Same store and naming as the Library's; values are the iOS names.
        const val VIEW_KEY = "library.favouritesViewMode"
        const val SORT_KEY = "library.favouritesSort"
        const val FILTER_KEY = "library.favouritesFilter"

        /**
         * What the tab shows, from the favourites in favourited order:
         * offline the downloaded ones only (whatever the filter), Downloaded
         * likewise, then sorted.
         */
        fun arrange(
            favourites: List<JellyfinAlbum>,
            sort: FavouritesSort,
            filter: LibraryFilter,
            offline: Boolean,
            isDownloaded: (JellyfinAlbum) -> Boolean,
        ): List<JellyfinAlbum> {
            val downloadedOnly = offline || filter == LibraryFilter.DOWNLOADED
            return sort(if (downloadedOnly) favourites.filter(isDownloaded) else favourites, sort)
        }

        /** Date Favourited keeps the incoming order; the others are exactly the Albums tab's sorts. */
        fun sort(albums: List<JellyfinAlbum>, by: FavouritesSort): List<JellyfinAlbum> =
            by.librarySort?.let { LibraryModel.sort(albums, it) } ?: albums
    }
}
