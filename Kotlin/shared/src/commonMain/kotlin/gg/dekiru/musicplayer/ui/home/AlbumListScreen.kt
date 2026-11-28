package gg.dekiru.musicplayer.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.api.JellyfinError
import gg.dekiru.musicplayer.data.models.DiscGrouping
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.settings.FavoritesStore
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.components.EmptyState
import gg.dekiru.musicplayer.ui.components.LoadingSpinner
import gg.dekiru.musicplayer.ui.components.PageControls
import gg.dekiru.musicplayer.ui.components.PageHeader
import gg.dekiru.musicplayer.ui.components.PageTitle
import gg.dekiru.musicplayer.ui.components.ShakeEffect
import gg.dekiru.musicplayer.ui.downloads.rememberIsDownloaded
import gg.dekiru.musicplayer.ui.layout.screenInsets
import gg.dekiru.musicplayer.ui.library.FavouriteAlbums
import gg.dekiru.musicplayer.ui.library.FavouritesControls
import gg.dekiru.musicplayer.ui.library.FavouritesOptions
import gg.dekiru.musicplayer.ui.library.LibraryAlbumGrid
import gg.dekiru.musicplayer.ui.library.LibraryFilter
import gg.dekiru.musicplayer.ui.library.NoDownloadedFavourites
import gg.dekiru.musicplayer.ui.tetsubiType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The full lists Home's › buttons open. */
enum class AlbumList(val title: String, val emptyMessage: String) {
    FAVOURITES("Favourites", "No favourite albums yet. Tap the star on an album to add one."),
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
        /** Random albums fetched each time, all shown. */
        const val RANDOM_FETCH = 12
    }
}

/**
 * A full page of albums (Home > Favourites or Random albums "See all"): Back and the page's
 * controls in the header, the title in the content, then the app's grid of cards; pull down to load
 * again. Random shows what it fetched, a "Shuffle again" action, and "Shake it" where the device can
 * be shaken. Favourites: Library > Favourites' view (as iOS, where it's the same view), following
 * its remembered View, Sort and Filter ([favourites]) with the same header controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumListScreen(
    api: JellyfinApi,
    model: AlbumListModel,
    onOpen: (JellyfinAlbum) -> Unit,
    onBack: () -> Unit,
    downloads: DownloadManager? = null,
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
    val favouriteState = rememberLazyGridState()
    val randomState = rememberLazyGridState()

    Column(Modifier.fillMaxSize().screenInsets()) {
        PageHeader(null, onBack = onBack) {
            if (isFavourites) FavouritesControls(options) else PageControls(density = true, shuffle = model::refresh)
        }
        PageTitle(model.list.title)
        PullToRefreshBox(
            isRefreshing = model.isLoading && model.albums.isNotEmpty(),
            onRefresh = model::refresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            when {
                isFavourites && model.albums.isNotEmpty() -> {
                    val shown = options.arrange(model.albums, offline = false, isDownloaded = isDownloaded)
                    if (shown.isEmpty()) {
                        NoDownloadedFavourites { options.filter = LibraryFilter.ALL }
                    } else {
                        FavouriteAlbums(api, shown, options, onOpen, isDownloaded, { null }, favouriteState)
                    }
                }
                model.albums.isNotEmpty() -> LibraryAlbumGrid(
                    api, model.albums, randomState, onOpen, isDownloaded,
                    footer = if (canShake) ({
                        Text(
                            "Shake it", style = tetsubiType.body, color = TetsubiColors.textSecondary, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }) else null,
                )
                model.isLoading -> LoadingSpinner()
                model.errorMessage != null -> StatusBanner("Can't reach your Jellyfin server. ${model.errorMessage}", "Retry", model::refresh)
                else -> EmptyState(model.list.emptyMessage)
            }
        }
    }
}
