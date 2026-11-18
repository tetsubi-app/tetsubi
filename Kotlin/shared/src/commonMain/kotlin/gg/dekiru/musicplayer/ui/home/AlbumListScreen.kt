package gg.dekiru.musicplayer.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.api.JellyfinError
import gg.dekiru.musicplayer.data.models.DiscGrouping
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.settings.FavoritesStore
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.downloads.rememberIsDownloaded
import gg.dekiru.musicplayer.ui.components.AlbumCard
import gg.dekiru.musicplayer.ui.components.ShakeEffect
import gg.dekiru.musicplayer.ui.library.FavouriteAlbums
import gg.dekiru.musicplayer.ui.library.FavouritesOptions
import gg.dekiru.musicplayer.ui.library.GridZoom
import gg.dekiru.musicplayer.ui.library.GridZoomButtons
import gg.dekiru.musicplayer.ui.library.LibraryDisplayMode
import gg.dekiru.musicplayer.ui.library.LibraryFilter
import gg.dekiru.musicplayer.ui.library.NoDownloadedFavourites
import gg.dekiru.musicplayer.ui.library.isWideLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The full lists Home's › buttons open. */
enum class AlbumList(val title: String, val emptyMessage: String) {
    FAVOURITES("Favourites", "No favourite albums yet. Tap the heart on an album to add one."),
    RANDOM("Random Albums", "No albums available."),
}

/** One full list: all favourites (newest first), or a page of random albums. */
class AlbumListModel(
    val list: AlbumList,
    private val api: JellyfinApi,
    private val favorites: FavoritesStore,
    private val scope: CoroutineScope,
) {
    var albums by mutableStateOf<List<JellyfinAlbum>>(emptyList())
        private set
    var isLoading by mutableStateOf(true)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    private var loaded = false
    private var job: Job? = null

    fun loadIfNeeded() {
        if (!loaded) refresh()
    }

    /** Fetches again; for Random, a fresh set. */
    fun refresh() {
        loaded = true
        job?.cancel()
        isLoading = true
        errorMessage = null
        job = scope.launch {
            try {
                albums = when (list) {
                    AlbumList.FAVOURITES -> DiscGrouping.merge(favorites.ordered(api.favoriteAlbums()))
                    AlbumList.RANDOM -> DiscGrouping.merge(api.randomAlbums(limit = RANDOM_FETCH))
                }
            } catch (e: JellyfinError) {
                errorMessage = e.message
            }
            isLoading = false
        }
    }

    companion object {
        /** Random albums fetched each time; 9 or 12 of them are shown. */
        const val RANDOM_FETCH = 12

        /** The grid: cards at least 152 dp wide, 8 dp apart, 10 dp in from the edges. */
        const val MIN_COLUMN = 152f
        const val COLUMN_GAP = 8f
        const val ROW_GAP = 12f
        const val SIDE_PADDING = 10f
        const val TOP_PADDING = 8f

        /** "Shake it" and the space around it. */
        const val FOOTER = 40f

        /** Random's grid: three across like the iPhone (two on a narrow phone), centred. */
        fun columns(width: Float): Int = ((width - 2 * SIDE_PADDING + COLUMN_GAP) / (MIN_COLUMN + COLUMN_GAP)).toInt().coerceIn(1, 3)
        const val RANDOM_MAX_WIDTH = 3 * MIN_COLUMN + 2 * COLUMN_GAP + 2 * SIDE_PADDING

        /**
         * The Random page (as on iOS): 12 albums when all their rows, and
         * "Shake it", fit in [height] (dp, above the system bars), else 9.
         */
        fun randomCount(width: Float, height: Float, cardHeight: Float, footer: Float = FOOTER): Int {
            val rows = (12 + columns(width) - 1) / columns(width)
            val needed = TOP_PADDING + rows * cardHeight + (rows - 1) * ROW_GAP + footer
            return if (needed <= height) 12 else 9
        }
    }
}

/**
 * A full page of albums (Home ▸ Favourites › or Random Albums ›): a grid
 * of cards, pull down to load again. Random shows 9 or 12 (as many rows as
 * fit), one-line titles, and "Shake it" where the device can be shaken.
 * Favourites: Library ▸ Favourites' view (as iOS, where it's the same
 * view), following its remembered View, Sort and Filter ([favourites]);
 * on wide layouts in Grid, the zoom buttons ([zoom]) in the top bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumListScreen(
    api: JellyfinApi,
    model: AlbumListModel,
    onOpen: (JellyfinAlbum) -> Unit,
    onBack: () -> Unit,
    downloads: DownloadManager? = null,
    zoom: GridZoom? = null,
    favourites: FavouritesOptions? = null,
) {
    LaunchedEffect(model) { model.loadIfNeeded() }
    val options = favourites ?: remember { FavouritesOptions(InMemoryPreferences()) }
    val isFavourites = model.list == AlbumList.FAVOURITES
    val isDownloaded = rememberIsDownloaded(downloads)
    val isRandom = model.list == AlbumList.RANDOM
    val haptics = LocalHapticFeedback.current
    // Shake for a fresh set, Random only (this screen is only composed while on top).
    val canShake = ShakeEffect {
        if (isRandom && !model.isLoading) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            model.refresh()
        }
    } && isRandom
    // One card: padding, cover, then one-line title, artist and year.
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current
    val density = LocalDensity.current
    val cardHeight = with(density) {
        val title = measurer.measure("Ag", style.merge(TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold))).size.height
        val line = measurer.measure("Ag", style.merge(TextStyle(fontSize = 11.sp))).size.height
        (title + 2 * line).toDp().value + 12 + 140 + 12
    }
    val bottom = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues()
    // Random keeps its fixed cards (its 9 or 12 depends on their size); Favourites' list has no zoom.
    val zoomed = if (isFavourites && options.displayMode == LibraryDisplayMode.GRID && isWideLayout()) zoom else null
    val favouriteGrid = rememberLazyGridState()
    val favouriteList = rememberLazyListState()

    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) {
                    Text("‹ Back", color = TetsubiColors.blue, fontSize = 16.sp)
                }
                Text(
                    model.list.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (zoomed != null) GridZoomButtons(zoomed, Modifier.padding(end = 8.dp))
            }
            PullToRefreshBox(
                isRefreshing = model.isLoading && model.albums.isNotEmpty(),
                onRefresh = model::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    isFavourites && model.albums.isNotEmpty() -> {
                        val shown = options.arrange(model.albums, offline = false, isDownloaded = isDownloaded)
                        if (shown.isEmpty()) {
                            NoDownloadedFavourites { options.filter = LibraryFilter.ALL }
                        } else {
                            FavouriteAlbums(api, shown, options, onOpen, isDownloaded, { null }, favouriteGrid, favouriteList, zoom)
                        }
                    }
                    model.albums.isNotEmpty() -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    val shown = if (isRandom) {
                        val height = maxHeight.value - bottom.calculateBottomPadding().value
                        model.albums.take(AlbumListModel.randomCount(maxWidth.value, height, cardHeight, if (canShake) AlbumListModel.FOOTER else 0f))
                    } else model.albums
                    LazyVerticalGrid(
                        columns = if (isRandom) GridCells.Fixed(AlbumListModel.columns(maxWidth.value)) else GridCells.Adaptive(152.dp),
                        state = rememberLazyGridState(),
                        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 24.dp + bottom.calculateBottomPadding()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = if (isRandom) {
                            Modifier.fillMaxHeight().widthIn(max = AlbumListModel.RANDOM_MAX_WIDTH.dp).align(Alignment.TopCenter)
                        } else Modifier.fillMaxSize(),
                    ) {
                        items(shown, key = { it.id }) { album ->
                            Box(contentAlignment = Alignment.TopCenter) {
                                AlbumCard(api, album, onClick = { onOpen(album) }, titleLines = if (isRandom) 1 else 2, isDownloaded = isDownloaded(album))
                            }
                        }
                        if (canShake) item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                "Shake it", color = TetsubiColors.albumYearText, fontSize = 13.sp, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                        }
                    }
                    }
                    model.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        CircularProgressIndicator(Modifier.padding(32.dp).size(24.dp), color = TetsubiColors.albumArtistText, strokeWidth = 2.dp)
                    }
                    model.errorMessage != null -> StatusBanner("Can't reach your Jellyfin server. ${model.errorMessage}", "Retry", model::refresh)
                    else -> Text(model.list.emptyMessage, color = TetsubiColors.albumYearText, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}
