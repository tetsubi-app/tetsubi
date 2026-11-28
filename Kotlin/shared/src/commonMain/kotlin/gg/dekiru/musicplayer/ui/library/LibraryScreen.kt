package gg.dekiru.musicplayer.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinArtist
import gg.dekiru.musicplayer.data.models.JellyfinPlaylist
import gg.dekiru.musicplayer.data.network.NetworkState
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.ui.components.BannerKind
import gg.dekiru.musicplayer.ui.components.EmptyState
import gg.dekiru.musicplayer.ui.components.LoadingSpinner
import gg.dekiru.musicplayer.ui.components.PageControls
import gg.dekiru.musicplayer.ui.components.PageHeader
import gg.dekiru.musicplayer.ui.components.ShakeEffect
import gg.dekiru.musicplayer.ui.components.TetsubiTabs
import gg.dekiru.musicplayer.ui.components.WarningIcon
import gg.dekiru.musicplayer.ui.components.controlMenu
import gg.dekiru.musicplayer.ui.downloads.collectActive
import gg.dekiru.musicplayer.ui.downloads.collectDownloaded
import gg.dekiru.musicplayer.ui.downloads.coverPath
import gg.dekiru.musicplayer.ui.home.OFFLINE_MESSAGE
import gg.dekiru.musicplayer.ui.home.StatusBanner
import gg.dekiru.musicplayer.ui.layout.screenInsets
import gg.dekiru.musicplayer.ui.playlist.PlaylistGrid
import gg.dekiru.musicplayer.ui.playlist.PlaylistList
import gg.dekiru.musicplayer.ui.playlist.PlaylistsModel
import gg.dekiru.musicplayer.ui.TetsubiColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The Library (as `LibraryView` on iOS): a page header with the Sort, Grid / List, card size and
 * Filter controls (one Sort and view button on a narrow window), then Albums / Artists / Favourites /
 * Playlists as tabs. Albums: grid or list, the A-Z strip when sorted by Name or Artist;
 * Random shows 24 at a time, pull down or shake for a new shuffle.
 * Offline: a banner and the downloaded albums only. Artists open through
 * [onOpenArtist] (an [ArtistScreen] on the tab's stack), playlists through
 * [onOpenPlaylist]. Offline, the Playlists tab lists the downloaded playlists.
 * Artists: grid (by default) or list, with its own view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    api: JellyfinApi,
    model: LibraryModel,
    onOpen: (JellyfinAlbum) -> Unit,
    onOpenArtist: (JellyfinArtist) -> Unit,
    network: StateFlow<NetworkState> = MutableStateFlow(NetworkState()),
    downloads: DownloadManager? = null,
    playlists: PlaylistsModel? = null,
    onOpenPlaylist: (JellyfinPlaylist) -> Unit = {},
) {
    val active by downloads.collectActive()
    val downloaded by downloads.collectDownloaded()
    LaunchedEffect(model, active, downloaded) { model.downloadsChanged(downloaded, active.keys) }
    // Fetches on each appearance (as iOS), and again when the connection comes back.
    LaunchedEffect(model, network) { network.map { it.online }.distinctUntilChanged().collect(model::networkChanged) }
    LaunchedEffect(playlists, network) {
        if (playlists != null) network.map { it.online }.distinctUntilChanged().collect(playlists::networkChanged)
    }
    LaunchedEffect(model, model.tab, model.isOffline) {
        when (model.tab) {
            LibraryTab.PLAYLISTS -> playlists?.load()
            LibraryTab.ARTISTS -> model.loadArtists()
            LibraryTab.FAVOURITES -> model.loadFavourites()
            LibraryTab.ALBUMS -> Unit
        }
    }

    // Kept per tab, so switching tabs comes back to the same place.
    val albumState = rememberLazyGridState()
    val artistState = rememberLazyGridState()
    val favouriteState = rememberLazyGridState()
    val playlistState = rememberLazyGridState()

    // A new Albums sort starts at the top. Otherwise the grid keeps the album that was at the top
    // in view, wherever it now sorts, and seems to jump.
    var lastSort by remember { mutableStateOf(model.sortOption) }
    LaunchedEffect(model.sortOption) {
        if (model.sortOption != lastSort) {
            lastSort = model.sortOption
            albumState.scrollToItem(0)
        }
    }

    Column(Modifier.fillMaxSize().screenInsets()) {
        PageHeader("Library") { LibraryControls(model, playlists) }
        TetsubiTabs(LibraryTab.entries.map { it.title }, model.tab.ordinal, { model.tab = LibraryTab.entries[it] })
        if (model.isOffline) StatusBanner(OFFLINE_MESSAGE, kind = BannerKind.Offline)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val localArt: (JellyfinAlbum) -> String? = { album -> if (model.isDownloaded(album)) downloads.coverPath(album) else null }
            when (model.tab) {
                LibraryTab.ALBUMS -> AlbumsTab(api, model, onOpen, localArt, albumState)
                LibraryTab.ARTISTS -> ArtistsTab(api, model, onOpenArtist, artistState)
                LibraryTab.FAVOURITES -> when {
                    !model.favouritesLoaded -> LoadingSpinner()
                    model.favourites.isEmpty() && model.favouritesOptions.filter == LibraryFilter.DOWNLOADED && !model.isOffline && model.hasFavourites ->
                        NoDownloadedFavourites { model.favouritesOptions.filter = LibraryFilter.ALL }
                    model.favourites.isEmpty() -> EmptyState("No favourite albums yet", message = "Tap the star on an album to add one.")
                    else -> FavouriteAlbums(api, model.favourites, model.favouritesOptions, onOpen, model::isDownloaded, localArt, favouriteState)
                }
                LibraryTab.PLAYLISTS -> if (playlists != null) PlaylistsTab(api, playlists, model.displayMode, onOpenPlaylist, playlistState)
            }
        }
    }
}

/** The header's controls for the open tab: the same options as the old menus, inline or in the sheet. */
@Composable
private fun LibraryControls(model: LibraryModel, playlists: PlaylistsModel?) {
    when (model.tab) {
        LibraryTab.ALBUMS -> PageControls(
            sort = controlMenu("Sort", LibrarySort.entries, model.sortOption, { it.title }) { model.sortOption = it },
            filter = controlMenu("Filter", LibraryFilter.entries, model.filter, { it.title }) { model.filter = it },
            view = model.displayMode, onView = { model.displayMode = it },
            density = model.displayMode == LibraryDisplayMode.GRID,
            shuffle = if (model.sortOption == LibrarySort.RANDOM) model::shuffleAgain else null,
        )
        LibraryTab.ARTISTS -> PageControls(
            view = model.artistsDisplayMode, onView = { model.artistsDisplayMode = it },
            density = model.artistsDisplayMode == LibraryDisplayMode.GRID,
        )
        LibraryTab.FAVOURITES -> FavouritesControls(model.favouritesOptions)
        LibraryTab.PLAYLISTS -> PageControls(
            sort = playlists?.let { p -> controlMenu("Sort", p.sortOptions, p.sortOption, { it.title }) { p.sortOption = it } },
            view = model.displayMode, onView = { model.displayMode = it },
            density = model.displayMode == LibraryDisplayMode.GRID,
        )
    }
}

/** Sort, Grid / List, card size and Filter for Favourites (Library and Home's full list share them). */
@Composable
internal fun FavouritesControls(options: FavouritesOptions) {
    PageControls(
        sort = controlMenu("Sort", FavouritesSort.entries, options.sortOption, { it.title }) { options.sortOption = it },
        filter = controlMenu("Filter", LibraryFilter.entries, options.filter, { it.title }) { options.filter = it },
        view = options.displayMode, onView = { options.displayMode = it },
        density = options.displayMode == LibraryDisplayMode.GRID,
    )
}

/**
 * Favourite albums (already arranged by [options]) as the Library's album
 * grid or list, with the A-Z strip for the Name and Artist sorts (as iOS
 * `FavouritesTabView`). Library > Favourites and Home > Favourites.
 */
@Composable
internal fun FavouriteAlbums(
    api: JellyfinApi,
    albums: List<JellyfinAlbum>,
    options: FavouritesOptions,
    onOpen: (JellyfinAlbum) -> Unit,
    isDownloaded: (JellyfinAlbum) -> Boolean,
    localArt: (JellyfinAlbum) -> String?,
    state: LazyGridState,
) {
    val scope = rememberCoroutineScope()
    val key = options.indexKey
    val firstIds = remember(albums, options.sortOption) {
        key?.let { AlphaIndex.firstIds(albums, it) { album -> album.id } }.orEmpty()
    }
    val strip = if (key != null) AlphaIndexBarWidth else 0.dp
    Box(Modifier.fillMaxSize()) {
        when (options.displayMode) {
            LibraryDisplayMode.GRID -> LibraryAlbumGrid(api, albums, state, onOpen, isDownloaded, localArt, endInset = strip)
            LibraryDisplayMode.LIST -> LibraryAlbumList(api, albums, state, onOpen, isDownloaded, localArt, endInset = strip)
        }
        if (key != null) {
            AlphaIndexBar(firstIds.keys, onSelect = { label ->
                // The letter, or the next one that has albums.
                val id = AlphaIndex.target(label, firstIds) ?: return@AlphaIndexBar
                val index = albums.indexOfFirst { it.id == id }.coerceAtLeast(0)
                scope.launch { state.scrollToItem(index) }
            }, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }
}

/** Favourites with the Downloaded filter and none downloaded (online). */
@Composable
internal fun NoDownloadedFavourites(onShowAll: () -> Unit) {
    EmptyState("No downloaded favourite albums yet", actionTitle = "Show all favourites", onAction = onShowAll)
}

/** The user's playlists, grid or list; a failed fetch shows the banner in the list's place (as iOS). */
@Composable
private fun PlaylistsTab(
    api: JellyfinApi,
    playlists: PlaylistsModel,
    displayMode: LibraryDisplayMode,
    onOpen: (JellyfinPlaylist) -> Unit,
    state: LazyGridState,
) {
    val shown = playlists.playlists
    val error = playlists.errorMessage
    @Composable
    fun results() = when (displayMode) {
        LibraryDisplayMode.GRID -> PlaylistGrid(api, playlists, shown, state, onOpen)
        LibraryDisplayMode.LIST -> PlaylistList(api, playlists, shown, state, onOpen)
    }
    when {
        playlists.isOffline && shown.isEmpty() -> EmptyState("No downloaded playlists yet")
        playlists.isOffline -> results()
        playlists.isLoading -> LoadingSpinner()
        error != null -> StatusBanner(error, "Retry", playlists::load)
        shown.isNotEmpty() -> results()
        playlists.loaded -> EmptyState("No playlists yet", message = "Add songs from an album's track menu.")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumsTab(
    api: JellyfinApi,
    model: LibraryModel,
    onOpen: (JellyfinAlbum) -> Unit,
    localArt: (JellyfinAlbum) -> String?,
    state: LazyGridState,
) {
    when {
        model.isLoading -> LoadingSpinner()
        model.errorMessage != null -> EmptyState(
            "Couldn't load library", message = model.errorMessage.orEmpty(),
            icon = { WarningIcon(48.dp, TetsubiColors.pink) }, actionTitle = "Retry", onAction = model::load,
        )
        model.sortedAlbums.isEmpty() -> NoAlbums(model)
        else -> {
            val scope = rememberCoroutineScope()
            val haptics = LocalHapticFeedback.current
            val isRandom = model.sortOption == LibrarySort.RANDOM
            // Random only: a shake (while this is on screen) deals a new shuffle, as "Shuffle again".
            if (isRandom) {
                ShakeEffect {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    model.shuffleAgain()
                }
            }
            // A new shuffle starts at the top (not coming back from an album).
            var seenShuffles by rememberSaveable { mutableStateOf(model.shuffles) }
            LaunchedEffect(model.shuffles) {
                if (model.shuffles != seenShuffles) {
                    seenShuffles = model.shuffles
                    state.scrollToItem(0)
                }
            }
            val key = model.indexKey
            val firstIds = remember(model.sortedAlbums, model.sortOption) {
                key?.let { AlphaIndex.firstIds(model.sortedAlbums, it) { album -> album.id } }.orEmpty()
            }
            val strip = if (key != null) AlphaIndexBarWidth else 0.dp
            val content = @Composable {
                Box(Modifier.fillMaxSize()) {
                    when (model.displayMode) {
                        LibraryDisplayMode.GRID -> LibraryAlbumGrid(
                            api, model.sortedAlbums, state, onOpen, model::isDownloaded, localArt,
                            endInset = strip, onAppear = model::loadMoreIfNeeded,
                        )
                        LibraryDisplayMode.LIST -> LibraryAlbumList(
                            api, model.sortedAlbums, state, onOpen, model::isDownloaded, localArt,
                            endInset = strip, onAppear = model::loadMoreIfNeeded,
                        )
                    }
                    if (key != null) {
                        AlphaIndexBar(firstIds.keys, onSelect = { label ->
                            // The letter, or the next one that has albums.
                            val id = AlphaIndex.target(label, firstIds) ?: return@AlphaIndexBar
                            val index = model.sortedAlbums.indexOfFirst { it.id == id }.coerceAtLeast(0)
                            scope.launch { state.scrollToItem(index) }
                        }, modifier = Modifier.align(Alignment.CenterEnd))
                    }
                }
            }
            // Random only: pull down for a fresh shuffle (and any new albums).
            if (isRandom) {
                PullToRefreshBox(isRefreshing = model.isRefreshing, onRefresh = model::refreshRandom, modifier = Modifier.fillMaxSize()) { content() }
            } else {
                content()
            }
        }
    }
}

@Composable
private fun NoAlbums(model: LibraryModel) {
    val downloadedOnly = model.filter == LibraryFilter.DOWNLOADED || model.isOffline
    EmptyState(
        if (downloadedOnly) "No downloaded albums yet" else "Library is empty",
        message = if (downloadedOnly) "Download an album from its detail page to play it offline." else "Connect to a server with music to get started.",
        actionTitle = if (model.filter == LibraryFilter.DOWNLOADED && !model.isOffline) "Show all albums" else null,
        onAction = { model.filter = LibraryFilter.ALL },
    )
}

/**
 * All album artists with the A-Z strip (as iOS `ArtistsTabView`): round
 * pictures in a grid (the album cards' size and columns) or rows;
 * the strip jumps in either. Tap one for their albums.
 */
@Composable
private fun ArtistsTab(
    api: JellyfinApi,
    model: LibraryModel,
    onOpenArtist: (JellyfinArtist) -> Unit,
    state: LazyGridState,
) {
    when {
        !model.artistsLoaded -> LoadingSpinner()
        model.artists.isEmpty() -> EmptyState("No artists found")
        else -> {
            val scope = rememberCoroutineScope()
            val firstIds = remember(model.artists) { AlphaIndex.firstIds(model.artists, { it.name }) { it.id } }
            Box(Modifier.fillMaxSize()) {
                when (model.artistsDisplayMode) {
                    LibraryDisplayMode.GRID -> LibraryArtistGrid(api, model.artists, state, onOpenArtist, endInset = AlphaIndexBarWidth)
                    LibraryDisplayMode.LIST -> LibraryArtistList(api, model.artists, state, onOpenArtist, endInset = AlphaIndexBarWidth)
                }
                AlphaIndexBar(firstIds.keys, onSelect = { label ->
                    val id = AlphaIndex.target(label, firstIds) ?: return@AlphaIndexBar
                    val index = model.artists.indexOfFirst { it.id == id }.coerceAtLeast(0)
                    scope.launch { state.scrollToItem(index) }
                }, modifier = Modifier.align(Alignment.CenterEnd))
            }
        }
    }
}
