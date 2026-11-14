package gg.dekiru.musicplayer.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.api.JellyfinError
import gg.dekiru.musicplayer.data.models.DiscGrouping
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinArtist
import gg.dekiru.musicplayer.data.models.allDiscs
import gg.dekiru.musicplayer.data.settings.FavoritesStore
import gg.dekiru.musicplayer.data.settings.Preferences
import gg.dekiru.musicplayer.downloads.DownloadedAlbum
import gg.dekiru.musicplayer.ui.home.HomeModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.random.Random

/** The three Library sections (as `LibraryTab` on iOS). */
enum class LibraryTab(val title: String) { ALBUMS("Albums"), ARTISTS("Artists"), FAVOURITES("Favourites"), PLAYLISTS("Playlists") }

enum class LibrarySort(val title: String) { NAME("Name"), ARTIST("Artist"), YEAR("Year"), DATE_ADDED("Date Added"), RANDOM("Random") }

enum class LibraryFilter(val title: String) { ALL("All"), DOWNLOADED("Downloaded") }

enum class LibraryDisplayMode(val title: String) { GRID("Grid"), LIST("List") }

/** Grid columns and the gap between them. */
data class GridLayout(val columns: Int, val spacing: Float)

/**
 * The Library (as `LibraryViewModel` on iOS, plus the Artists and
 * Favourites tabs' lists). Albums: the whole library fetched once, filtered
 * and sorted here (re-sorting doesn't ask the server again). Random shows
 * 24 at a time in an order fixed until Random is picked again. Offline:
 * the downloaded albums only. The tab, sort and view are remembered.
 */
class LibraryModel(
    private val api: JellyfinApi,
    private val favoritesStore: FavoritesStore,
    private val preferences: Preferences,
    private val scope: CoroutineScope,
    private val random: Random = Random.Default,
) {
    // Albums

    /** Every album from the server (discs merged), newest addition first. */
    var albums by mutableStateOf<List<JellyfinAlbum>>(emptyList())
        private set

    /** Filtered and sorted: what's shown. */
    var sortedAlbums by mutableStateOf<List<JellyfinAlbum>>(emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /** Pull to reshuffle in progress. */
    var isRefreshing by mutableStateOf(false)
        private set

    /** No network: the downloaded albums only. */
    var isOffline by mutableStateOf(false)
        private set

    private var _tab by mutableStateOf(saved(TAB_KEY, LibraryTab.entries, LibraryTab.ALBUMS) { it.title })
    var tab: LibraryTab
        get() = _tab
        set(value) {
            _tab = value
            preferences.putString(TAB_KEY, value.title)
        }

    private var _sort by mutableStateOf(saved(SORT_KEY, LibrarySort.entries, LibrarySort.NAME) { it.title })

    /** Picking Random (even again) deals a fresh shuffle. */
    var sortOption: LibrarySort
        get() = _sort
        set(value) {
            _sort = value
            preferences.putString(SORT_KEY, value.title)
            if (value == LibrarySort.RANDOM) reshuffle()
            apply()
        }

    private var _filter by mutableStateOf(LibraryFilter.ALL)
    var filter: LibraryFilter
        get() = _filter
        set(value) {
            _filter = value
            randomLimit = RANDOM_PAGE
            apply()
        }

    private var _display by mutableStateOf(saved(VIEW_KEY, LibraryDisplayMode.entries, LibraryDisplayMode.GRID) { it.title })
    var displayMode: LibraryDisplayMode
        get() = _display
        set(value) {
            _display = value
            preferences.putString(VIEW_KEY, value.title)
        }

    // Downloads (kept up to date by the screen)

    private var downloadedIds by mutableStateOf(emptySet<String>())

    /** The downloaded albums (discs merged), newest download first. */
    var downloadedAlbums by mutableStateOf<List<JellyfinAlbum>>(emptyList())
        private set

    /** Every disc downloaded (and not being downloaded again). */
    fun isDownloaded(album: JellyfinAlbum): Boolean = album.allDiscs.all { it.id in downloadedIds }

    fun downloadsChanged(downloaded: List<DownloadedAlbum>, activeIds: Set<String> = emptySet()) {
        downloadedIds = downloaded.map { it.albumId }.toSet() - activeIds
        downloadedAlbums = HomeModel.downloadedRow(downloaded.filter { it.albumId !in activeIds })
        apply()
    }

    // Random

    /** Shuffled album IDs. Fixed until Random is picked again, so going back or a refresh keeps the order. */
    private var randomOrder = emptyMap<String, Int>()
    private var randomLimit = RANDOM_PAGE

    /** Random, before the page limit. */
    private var randomFull = emptyList<JellyfinAlbum>()

    /** Call as each album appears: the last one shown reveals the next page. */
    fun loadMoreIfNeeded(after: JellyfinAlbum) {
        if (sortOption != LibrarySort.RANDOM || after.id != sortedAlbums.lastOrNull()?.id || sortedAlbums.size >= randomFull.size) return
        randomLimit += RANDOM_PAGE
        sortedAlbums = randomFull.take(randomLimit)
    }

    /** New shuffles dealt by hand (the screen goes back to the top on each). */
    var shuffles by mutableStateOf(0)
        private set

    /** "Shuffle again", or a shake: a new shuffle from one page. */
    fun shuffleAgain() {
        sortOption = LibrarySort.RANDOM
        shuffles++
    }

    /** Pull to refresh in Random: fetch the library again, then deal a new shuffle. Keeps the old albums if that fails. */
    fun refreshRandom() {
        if (isRefreshing) return
        isRefreshing = true
        scope.launch {
            if (!isOffline) {
                try {
                    receive(api.allAlbums())
                } catch (_: JellyfinError) {
                }
            }
            reshuffle()
            apply()
            shuffles++
            isRefreshing = false
        }
    }

    private fun reshuffle() {
        randomLimit = RANDOM_PAGE
        randomOrder = source().map { it.id }.shuffled(random).withIndex().associate { (i, id) -> id to i }
    }

    // Loading

    private var loadJob: Job? = null

    /**
     * The network now (the screen calls this as it appears, then on each
     * change). Online, the library is fetched again (quietly, keeping
     * what's shown, as iOS does on every appearance); offline, nothing is
     * asked of the server.
     */
    fun networkChanged(online: Boolean) {
        isOffline = !online
        if (!online) errorMessage = null
        apply()
        if (online) load()
    }

    /** Fetches the whole library. The spinner (and an error) only when there's nothing to show yet. */
    fun load() {
        if (isOffline) return
        val first = albums.isEmpty()
        if (first) isLoading = true
        errorMessage = null
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                receive(api.allAlbums())
            } catch (e: JellyfinError) {
                if (first) errorMessage = "Failed to load library: ${e.message}"
            }
            isLoading = false
        }
    }

    /** New albums from the server; multi-disc albums show as one. */
    internal fun receive(fetched: List<JellyfinAlbum>) {
        val merged = DiscGrouping.merge(fetched)
        if (merged.map { it.id } != albums.map { it.id }) {
            albums = merged
            apply()
        }
    }

    private fun source(): List<JellyfinAlbum> = if (isOffline) downloadedAlbums else albums

    private fun apply() {
        val filtered = when (filter) {
            LibraryFilter.ALL -> source()
            LibraryFilter.DOWNLOADED -> source().filter(::isDownloaded)
        }
        if (sortOption == LibrarySort.RANDOM) {
            if (randomOrder.isEmpty()) reshuffle()
            // Any album new since the shuffle goes at the end.
            randomFull = filtered.sortedBy { randomOrder[it.id] ?: Int.MAX_VALUE }
            sortedAlbums = randomFull.take(randomLimit)
        } else {
            sortedAlbums = sort(filtered, sortOption, random)
        }
    }

    /** The key the A–Z strip is built from; null when the sort isn't alphabetical. */
    val indexKey: ((JellyfinAlbum) -> String)?
        get() = when (sortOption) {
            LibrarySort.NAME -> { album -> album.displayName }
            LibrarySort.ARTIST -> { album -> album.artistName }
            else -> null
        }

    // Artists

    var artists by mutableStateOf<List<JellyfinArtist>>(emptyList())
        private set
    var artistsLoaded by mutableStateOf(false)
        private set

    /** Once (again if it came back empty), sorted to match the A–Z strip. */
    fun loadArtists() {
        if (artistsLoaded && artists.isNotEmpty()) return
        if (isOffline) {
            artistsLoaded = true
            return
        }
        scope.launch {
            try {
                artists = api.albumArtists().sortedWith(AlphaIndex.comparator { it.name })
            } catch (_: JellyfinError) {
            }
            artistsLoaded = true
        }
    }

    // Favourites

    private var allFavourites by mutableStateOf<List<JellyfinAlbum>>(emptyList())
    var favouritesLoaded by mutableStateOf(false)
        private set

    /** Most recently favourited first; offline, the downloaded ones only. */
    val favourites: List<JellyfinAlbum>
        get() = if (isOffline) allFavourites.filter(::isDownloaded) else allFavourites

    /** Each time the tab shows (favourites change on album pages). */
    fun loadFavourites() {
        if (isOffline) {
            favouritesLoaded = true
            return
        }
        scope.launch {
            try {
                allFavourites = DiscGrouping.merge(favoritesStore.ordered(api.favoriteAlbums()))
            } catch (_: JellyfinError) {
            }
            favouritesLoaded = true
        }
    }

    private fun <T> saved(key: String, values: List<T>, default: T, name: (T) -> String): T =
        preferences.getString(key)?.let { value -> values.firstOrNull { name(it) == value } } ?: default

    companion object {
        /** Random shows this many, then this many more each time the last one comes into view. */
        const val RANDOM_PAGE = 24

        // Remembered choices (iOS keeps them only while the app runs); values are the iOS names.
        const val TAB_KEY = "library.tab"
        const val SORT_KEY = "library.sort"
        const val VIEW_KEY = "library.viewMode"

        /** Cover size in the grid: fixed, columns adapt around it. */
        const val GRID_ART = 120f
        const val GRID_EDGE = 4f

        /**
         * Columns and gap for the width (as iOS `gridLayout`): a comfortable
         * 12 dp gap; when that only allows two, a third if three covers fit
         * with at least 2 dp between (three across on a phone, A–Z strip and all).
         */
        fun gridLayout(width: Float): GridLayout {
            val comfortable = maxOf(1, ((width + 12) / (GRID_ART + 12)).toInt())
            if (comfortable >= 3) return GridLayout(comfortable, 12f)
            if (3 * GRID_ART + 2 * 2 <= width) return GridLayout(3, minOf(12f, (width - 3 * GRID_ART) / 2))
            return GridLayout(comfortable, 12f)
        }

        /** Albums in the chosen order. Name and Artist follow the A–Z strip; Year is newest first; Date Added keeps the server's order. */
        fun sort(albums: List<JellyfinAlbum>, by: LibrarySort, random: Random = Random.Default): List<JellyfinAlbum> = when (by) {
            LibrarySort.NAME -> albums.sortedWith(AlphaIndex.comparator { it.displayName })
            LibrarySort.ARTIST -> albums.sortedWith(AlphaIndex.comparator { it.artistName })
            LibrarySort.YEAR -> albums.sortedByDescending { it.productionYear ?: 0 }
            LibrarySort.DATE_ADDED -> albums
            LibrarySort.RANDOM -> albums.shuffled(random)
        }
    }
}

/** One artist's page (as `ArtistAlbumsView` on iOS): their albums, newest release first. */
class ArtistModel(val artist: JellyfinArtist, private val api: JellyfinApi, private val scope: CoroutineScope) {
    var albums by mutableStateOf<List<JellyfinAlbum>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set
    private var job: Job? = null

    /** Once, and again if it came back empty (say, after being offline). */
    fun loadIfNeeded() {
        if (loaded && albums.isNotEmpty() || job?.isActive == true) return
        job = scope.launch {
            try {
                albums = DiscGrouping.merge(api.albumsByArtist(artist.id))
            } catch (_: JellyfinError) {
            }
            loaded = true
        }
    }

    /**
     * What to show: the server's list; offline, only the downloaded ones
     * (by name from the downloads when the list never loaded).
     */
    fun shown(offline: Boolean, downloaded: List<JellyfinAlbum>, isDownloaded: (JellyfinAlbum) -> Boolean): List<JellyfinAlbum> = when {
        !offline -> albums
        albums.isNotEmpty() -> albums.filter(isDownloaded)
        else -> downloaded.filter { it.artistName.equals(artist.name, ignoreCase = true) }
    }
}
