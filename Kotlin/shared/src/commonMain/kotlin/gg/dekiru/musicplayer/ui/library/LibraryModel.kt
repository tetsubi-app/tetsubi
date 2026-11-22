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
import gg.dekiru.musicplayer.data.models.releaseDate
import gg.dekiru.musicplayer.data.settings.FavoritesStore
import gg.dekiru.musicplayer.data.settings.Preferences
import gg.dekiru.musicplayer.downloads.DownloadedAlbum
import gg.dekiru.musicplayer.ui.home.HomeModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.random.Random

/** The three Library sections (as `LibraryTab` on iOS). */
enum class LibraryTab(val title: String) { ALBUMS("Albums"), ARTISTS("Artists"), FAVOURITES("Favourites"), PLAYLISTS("Playlists") }

enum class LibrarySort(val title: String) { NAME("Name"), ARTIST("Artist"), YEAR("Year"), DATE_ADDED("Date Added"), RANDOM("Random") }

enum class LibraryFilter(val title: String) { ALL("All"), DOWNLOADED("Downloaded") }

enum class LibraryDisplayMode(val title: String) { GRID("Grid"), LIST("List") }

/** Grid columns and the gap between them. */
data class GridLayout(val columns: Int, val spacing: Float)

/**
 * An album or artist grid as drawn (as on iOS): [columns] and the
 * gap, each card [cardWidth] wide, its text, inner gaps, tick, corners and the row gap times
 * [textScale], covers fetched [imageSize] pixels wide.
 */
data class CardGrid(val columns: Int, val spacing: Float, val cardWidth: Float, val textScale: Float, val imageSize: Int)

/**
 * The Library (as `LibraryViewModel` on iOS, plus the Artists and
 * Favourites tabs' lists). Albums: the whole library fetched once, filtered
 * and sorted here (re-sorting doesn't ask the server again). Random shows
 * 24 at a time in an order fixed until Random is picked again. Offline:
 * the downloaded albums only. The tab, sort, filter, view (albums', artists' and
 * favourites' apart; favourites' sort and filter too) and wide grids' cards per row are remembered.
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

    private var _filter by mutableStateOf(saved(FILTER_KEY, LibraryFilter.entries, LibraryFilter.ALL) { it.title })
    var filter: LibraryFilter
        get() = _filter
        set(value) {
            _filter = value
            preferences.putString(FILTER_KEY, value.title)
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

    private var _artistsDisplay by mutableStateOf(saved(ARTISTS_VIEW_KEY, LibraryDisplayMode.entries, LibraryDisplayMode.GRID) { it.title })

    /** Artists' own Grid / List (grid by default on every device), kept apart from the albums' view. */
    var artistsDisplayMode: LibraryDisplayMode
        get() = _artistsDisplay
        set(value) {
            _artistsDisplay = value
            preferences.putString(ARTISTS_VIEW_KEY, value.title)
        }

    /** Cards per row on wide layouts, shared by every album and artist grid. */
    val gridZoom = GridZoom(preferences)

    /** Favourites' own View, Sort and Filter (Home ▸ Favourites follows them too). */
    val favouritesOptions = FavouritesOptions(preferences)

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

    /**
     * As [favouritesOptions] arranges them (most recently favourited first
     * by default); offline, the downloaded ones only.
     */
    val favourites: List<JellyfinAlbum>
        get() = favouritesOptions.arrange(allFavourites, isOffline, ::isDownloaded)

    /** Any favourites at all, before the filter (the Downloaded filter's own empty note). */
    val hasFavourites: Boolean get() = allFavourites.isNotEmpty()

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
        const val FILTER_KEY = "library.filter"
        const val VIEW_KEY = "library.viewMode"
        const val ARTISTS_VIEW_KEY = "library.artistsViewMode"
        const val GRID_COLUMNS_KEY = "library.gridColumns"

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

        /** Cards per row on wide layouts: the zoom buttons stop at these. */
        const val MIN_GRID_COLUMNS = 3
        const val MAX_GRID_COLUMNS = 10

        /** The gap between cards on wide layouts (today's comfortable gap). */
        const val WIDE_SPACING = 12f

        /** Android is wide from this window width (dp) up, as iPad's regular width; the desktop always is. */
        const val WIDE_WIDTH = 600f

        /** Text grows with the card up to this; never smaller than a phone's. */
        const val MAX_TEXT_SCALE = 3f

        /** Cover sizes asked of the server, few so the image cache keeps hitting. */
        val IMAGE_SIZES = listOf(300, 600, 1000, 1500)

        /** Wide (zoom buttons, cards fill their columns): the desktop always, Android from [WIDE_WIDTH] dp. */
        fun isWide(isAndroid: Boolean, windowWidth: Float): Boolean = !isAndroid || windowWidth >= WIDE_WIDTH

        /**
         * The grid for [width] (dp, inside the edges). Phones (not [wide]):
         * [gridLayout] with fixed 120 dp covers, as before. Wide: [chosen]
         * columns (null: today's automatic count, kept to 3…10), each card
         * filling its column so a wider window means bigger cards; the text
         * scales with the card (×1…×3) and the cover is fetched at the
         * size it's drawn ([displayScale] pixels per dp). [scale] is the
         * platform's UI scale (1 here; Mac's UIScale on iOS).
         */
        fun cardGrid(width: Float, wide: Boolean, chosen: Int? = null, displayScale: Float = 1f, scale: Float = 1f): CardGrid {
            val auto = gridLayout(width)
            if (!wide) return CardGrid(auto.columns, auto.spacing, GRID_ART, 1f, IMAGE_SIZES.first())
            val columns = chosen?.coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS) ?: auto.columns.coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS)
            return fixedCardGrid(width, columns, displayScale, scale)
        }

        /**
         * Exactly [columns] across on any device (Recently Played: as many as
         * the export's size), each card filling its column with the wide
         * layout's gap, as iOS `gridMetrics(width:columns:)`. The text scales
         * with the card as on wide layouts but never below a phone's (narrow
         * cards keep the phone's text and cut it short), at most ×3.
         */
        fun fixedCardGrid(width: Float, columns: Int, displayScale: Float = 1f, scale: Float = 1f): CardGrid {
            val n = maxOf(1, columns)
            val spacing = WIDE_SPACING * scale
            val card = maxOf(0f, (width - spacing * (n - 1)) / n)
            val textScale = (card / (GRID_ART * scale)).coerceIn(1f, MAX_TEXT_SCALE)
            return CardGrid(n, spacing, card, textScale, imageSize(card * displayScale))
        }

        /** Home's card (cover and text width) on phones, as iOS `AlbumCardView.width`. */
        const val HOME_CARD = 140f

        /** Home rows' padding before the first card on phones. */
        const val HOME_EDGE = 10f

        /** Each Home card's own margin on phones, on each side (its press highlight); wide: top and bottom only. */
        const val HOME_CARD_PADDING = 6f

        /** Home rows' space before the first card on wide layouts (as iOS `homeRowLeading`); [homeRow] counts on it. */
        const val HOME_WIDE_LEADING = 16f

        /** Of the next card, this much shows at the end of a wide Home row, so it plainly scrolls. */
        const val HOME_PEEK = 1f / 3

        /**
         * Home's sideways rows for [width] (dp, the whole row,
         * [HOME_WIDE_LEADING] included), as iOS `homeRowMetrics`. Phones
         * (not [wide]): fixed 140 dp cards, as before. Wide: [chosen] cards
         * (null: as many of today's 140 dp cards as fit whole, kept to
         * 3…10) plus a third of the next, so each card is
         * (width − 16 − n × gap) / (n + ⅓), cards [CardGrid.spacing] apart
         * with no side margin. Text scales with the card (×1…×3 of 140 dp
         * times [scale]) and the cover is fetched at the size it's drawn.
         */
        fun homeRow(width: Float, wide: Boolean, chosen: Int? = null, displayScale: Float = 1f, scale: Float = 1f): CardGrid {
            val art = HOME_CARD * scale
            val spacing = WIDE_SPACING * scale
            val visible = width - HOME_WIDE_LEADING
            val fits = maxOf(1, ((visible + spacing) / (art + spacing)).toInt())
            if (!wide) return CardGrid(fits, spacing, art, 1f, IMAGE_SIZES.first())
            val count = (chosen ?: fits).coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS)
            val card = maxOf(1f, (visible - count * spacing) / (count + HOME_PEEK))
            val textScale = (card / art).coerceIn(1f, MAX_TEXT_SCALE)
            return CardGrid(count, spacing, card, textScale, imageSize(card * displayScale))
        }

        /** The smallest of [IMAGE_SIZES] at least [pixels] wide (the largest beyond that). */
        fun imageSize(pixels: Float): Int = IMAGE_SIZES.firstOrNull { it >= pixels } ?: IMAGE_SIZES.last()

        /**
         * Columns after a zoom button, from what's shown now (so the first
         * press never jumps): in (bigger cards) is one fewer, out one more,
         * kept to 3…10.
         */
        fun zoomed(shown: Int, zoomIn: Boolean): Int =
            (if (zoomIn) shown - 1 else shown + 1).coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS)

        fun canZoomIn(shown: Int): Boolean = shown > MIN_GRID_COLUMNS
        fun canZoomOut(shown: Int): Boolean = shown < MAX_GRID_COLUMNS

        /** Albums in the chosen order. Name and Artist follow the A–Z strip; Year is newest release first (full date, else Jan 1 of the year; none last); Date Added keeps the server's order. */
        fun sort(albums: List<JellyfinAlbum>, by: LibrarySort, random: Random = Random.Default): List<JellyfinAlbum> = when (by) {
            LibrarySort.NAME -> albums.sortedWith(AlphaIndex.comparator { it.displayName })
            LibrarySort.ARTIST -> albums.sortedWith(AlphaIndex.comparator { it.artistName })
            LibrarySort.YEAR -> albums.sortedWith(compareByDescending(nullsFirst<LocalDate>()) { it.releaseDate })
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
