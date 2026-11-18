package gg.dekiru.musicplayer.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinArtist
import gg.dekiru.musicplayer.data.models.JellyfinPlaylist
import gg.dekiru.musicplayer.data.network.NetworkState
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.components.AlbumArt
import gg.dekiru.musicplayer.ui.components.ShakeEffect
import gg.dekiru.musicplayer.ui.downloads.collectActive
import gg.dekiru.musicplayer.ui.downloads.collectDownloaded
import gg.dekiru.musicplayer.ui.downloads.coverPath
import gg.dekiru.musicplayer.ui.home.OFFLINE_MESSAGE
import gg.dekiru.musicplayer.ui.home.StatusBanner
import gg.dekiru.musicplayer.ui.playlist.PlaylistGrid
import gg.dekiru.musicplayer.ui.playlist.PlaylistList
import gg.dekiru.musicplayer.ui.playlist.PlaylistsModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Room kept at the right of the tab row for the options button. */
private val accessoryWidth = 36.dp

/** On wide layouts, room for the zoom buttons too, kept on every tab so the labels don't move. */
private val wideAccessoryWidth = accessoryWidth * 3

/**
 * The Library (as `LibraryView` on iOS): Albums / Artists / Favourites /
 * Playlists along the top, the View, Sort and Filter menu at the right
 * (Albums and Favourites; View and Sort for Playlists).
 * Albums: grid or list, the A–Z strip when sorted by Name or Artist;
 * Random shows 24 at a time, pull down or shake for a new shuffle.
 * Offline: a banner and the downloaded albums only. Artists open through
 * [onOpenArtist] (an [ArtistScreen] on the tab's stack), playlists through
 * [onOpenPlaylist]. Offline, the Playlists tab lists the downloaded playlists.
 * Artists: grid (by default) or list, with its own View menu. Wide
 * layouts: zoom buttons beside the menu while a grid shows.
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
    val albumGrid = rememberLazyGridState()
    val albumList = rememberLazyListState()
    val artistList = rememberLazyListState()
    val artistGrid = rememberLazyGridState()
    val favouriteGrid = rememberLazyGridState()
    val favouriteList = rememberLazyListState()
    val playlistGrid = rememberLazyGridState()
    val playlistList = rememberLazyListState()

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        val wide = isWideLayout()
        // The zoom buttons only while the tab shows a grid they change.
        val zoomable = when (model.tab) {
            LibraryTab.ALBUMS -> model.displayMode == LibraryDisplayMode.GRID
            LibraryTab.ARTISTS -> model.artistsDisplayMode == LibraryDisplayMode.GRID
            LibraryTab.FAVOURITES -> model.favouritesOptions.displayMode == LibraryDisplayMode.GRID
            LibraryTab.PLAYLISTS -> false
        }
        LibraryTabBar(model.tab, onSelect = { model.tab = it }, accessoryWidth = if (wide) wideAccessoryWidth else accessoryWidth) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (wide && zoomable) GridZoomButtons(model.gridZoom)
                when (model.tab) {
                    LibraryTab.ALBUMS -> OptionsMenu(model)
                    LibraryTab.ARTISTS -> ArtistOptionsMenu(model)
                    LibraryTab.FAVOURITES -> FavouritesOptionsMenu(model.favouritesOptions)
                    LibraryTab.PLAYLISTS -> if (playlists != null) PlaylistOptionsMenu(model, playlists)
                    else -> Unit
                }
            }
        }
        if (model.isOffline) StatusBanner(OFFLINE_MESSAGE, icon = "⊘")
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val localArt: (JellyfinAlbum) -> String? = { album -> if (model.isDownloaded(album)) downloads.coverPath(album) else null }
            when (model.tab) {
                LibraryTab.ALBUMS -> AlbumsTab(api, model, onOpen, localArt, albumGrid, albumList)
                LibraryTab.ARTISTS -> ArtistsTab(api, model, onOpenArtist, artistList, artistGrid)
                LibraryTab.FAVOURITES -> when {
                    !model.favouritesLoaded -> LibrarySpinner()
                    model.favourites.isEmpty() && model.favouritesOptions.filter == LibraryFilter.DOWNLOADED && !model.isOffline && model.hasFavourites ->
                        NoDownloadedFavourites { model.favouritesOptions.filter = LibraryFilter.ALL }
                    model.favourites.isEmpty() -> LibraryNote("No favourite albums yet.\nTap the heart on an album to add one.")
                    else -> FavouriteAlbums(
                        api, model.favourites, model.favouritesOptions, onOpen, model::isDownloaded, localArt,
                        favouriteGrid, favouriteList, model.gridZoom,
                    )
                }
                LibraryTab.PLAYLISTS -> if (playlists != null) {
                    PlaylistsTab(api, playlists, model.displayMode, onOpenPlaylist, playlistGrid, playlistList)
                }
            }
        }
    }
}

/** The large tab row (as iOS `LibraryTabBar`); [accessory] sits at the right, its room ([accessoryWidth]) always kept. */
@Composable
private fun LibraryTabBar(selected: LibraryTab, onSelect: (LibraryTab) -> Unit, accessoryWidth: Dp, accessory: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(TetsubiColors.background.copy(alpha = 0.6f)).padding(start = 16.dp, end = 16.dp, top = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        BoxWithConstraints(Modifier.weight(1f)) {
            // Four labels: each as wide as its text, and smaller on a phone so "Favourites" isn't cut.
            val labelSize = if (maxWidth < 400.dp) 16.sp else 20.sp
            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.SpaceAround) {
                for (tab in LibraryTab.entries) {
                    val isSelected = tab == selected
                    Column(
                        Modifier.width(IntrinsicSize.Max).selectable(isSelected, role = Role.Tab) { onSelect(tab) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            tab.title, color = if (isSelected) Color.White else Color.White.copy(alpha = 0.5f),
                            fontSize = labelSize, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Box(
                            Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50))
                                .background(if (isSelected) TetsubiColors.pink else Color.Transparent),
                        )
                    }
                }
            }
        }
        // Lined up with the labels, above the underline.
        Box(Modifier.width(accessoryWidth).padding(bottom = 9.dp), contentAlignment = Alignment.CenterEnd) { accessory() }
    }
}

/** View, Sort and Filter in one menu (and "Shuffle again" in Random). */
@Composable
private fun OptionsMenu(model: LibraryModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(accessoryWidth, 32.dp).clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button) { open = true }
                .semantics { contentDescription = "View, sort and filter" },
            contentAlignment = Alignment.Center,
        ) { SlidersIcon(20.dp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun pick(action: () -> Unit) {
                action()
                open = false
            }
            MenuHeading("View")
            for (mode in LibraryDisplayMode.entries) {
                MenuChoice(mode.title, mode == model.displayMode, icon = {
                    if (mode == LibraryDisplayMode.GRID) GridIcon(18.dp) else ListIcon(18.dp)
                }) { pick { model.displayMode = mode } }
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
            MenuHeading("Sort")
            for (sort in LibrarySort.entries) {
                MenuChoice(sort.title, sort == model.sortOption) { pick { model.sortOption = sort } }
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
            MenuHeading("Filter")
            for (filter in LibraryFilter.entries) {
                MenuChoice(filter.title, filter == model.filter) { pick { model.filter = filter } }
            }
            if (model.sortOption == LibrarySort.RANDOM) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
                DropdownMenuItem(
                    text = { Text("Shuffle again", color = Color.White) },
                    leadingIcon = { ShuffleIcon(18.dp) },
                    onClick = { pick(model::shuffleAgain) },
                )
            }
        }
    }
}

/** View, Sort and Filter for the Favourites tab (as the Albums one); Home ▸ Favourites follows them too. */
@Composable
private fun FavouritesOptionsMenu(options: FavouritesOptions) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(accessoryWidth, 32.dp).clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button) { open = true }
                .semantics { contentDescription = "View, sort and filter" },
            contentAlignment = Alignment.Center,
        ) { SlidersIcon(20.dp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun pick(action: () -> Unit) {
                action()
                open = false
            }
            MenuHeading("View")
            for (mode in LibraryDisplayMode.entries) {
                MenuChoice(mode.title, mode == options.displayMode, icon = {
                    if (mode == LibraryDisplayMode.GRID) GridIcon(18.dp) else ListIcon(18.dp)
                }) { pick { options.displayMode = mode } }
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
            MenuHeading("Sort")
            for (sort in FavouritesSort.entries) {
                MenuChoice(sort.title, sort == options.sortOption) { pick { options.sortOption = sort } }
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
            MenuHeading("Filter")
            for (filter in LibraryFilter.entries) {
                MenuChoice(filter.title, filter == options.filter) { pick { options.filter = filter } }
            }
        }
    }
}

/**
 * Favourite albums (already arranged by [options]) as the Library's album
 * grid or list, with the A–Z strip for the Name and Artist sorts (as iOS
 * `FavouritesTabView`). Library ▸ Favourites and Home ▸ Favourites.
 */
@Composable
internal fun FavouriteAlbums(
    api: JellyfinApi,
    albums: List<JellyfinAlbum>,
    options: FavouritesOptions,
    onOpen: (JellyfinAlbum) -> Unit,
    isDownloaded: (JellyfinAlbum) -> Boolean,
    localArt: (JellyfinAlbum) -> String?,
    grid: androidx.compose.foundation.lazy.grid.LazyGridState,
    list: LazyListState,
    zoom: GridZoom?,
) {
    val scope = rememberCoroutineScope()
    val key = options.indexKey
    val isGrid = options.displayMode == LibraryDisplayMode.GRID
    val firstIds = remember(albums, options.sortOption) {
        key?.let { AlphaIndex.firstIds(albums, it) { album -> album.id } }.orEmpty()
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(end = if (key != null) AlphaIndexBarWidth else 0.dp)) {
            if (isGrid) {
                LibraryAlbumGrid(api, albums, grid, onOpen, isDownloaded, localArt, trailingEdge = key == null, zoom = zoom)
            } else {
                LibraryAlbumList(api, albums, list, onOpen, isDownloaded, localArt)
            }
        }
        if (key != null) {
            AlphaIndexBar(firstIds.keys, onSelect = { label ->
                // The letter, or the next one that has albums.
                val id = AlphaIndex.target(label, firstIds) ?: return@AlphaIndexBar
                val index = albums.indexOfFirst { it.id == id }.coerceAtLeast(0)
                scope.launch { if (isGrid) grid.scrollToItem(index) else list.scrollToItem(index) }
            }, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }
}

/** Favourites with the Downloaded filter and none downloaded (online). */
@Composable
internal fun NoDownloadedFavourites(onShowAll: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("No downloaded favourite albums yet.", color = TetsubiColors.albumYearText, fontSize = 13.sp, textAlign = TextAlign.Center)
        TextButton(onClick = onShowAll) { Text("Show all favourites", color = TetsubiColors.blue, fontSize = 13.sp) }
    }
}

/** View for the Artists tab (its own, as the albums' Grid / List). */
@Composable
private fun ArtistOptionsMenu(model: LibraryModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(accessoryWidth, 32.dp).clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button) { open = true }
                .semantics { contentDescription = "View" },
            contentAlignment = Alignment.Center,
        ) { SlidersIcon(20.dp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuHeading("View")
            for (mode in LibraryDisplayMode.entries) {
                MenuChoice(mode.title, mode == model.artistsDisplayMode, icon = {
                    if (mode == LibraryDisplayMode.GRID) GridIcon(18.dp) else ListIcon(18.dp)
                }) {
                    model.artistsDisplayMode = mode
                    open = false
                }
            }
        }
    }
}

/** View and Sort for the Playlists tab; the view is the one the albums use. */
@Composable
private fun PlaylistOptionsMenu(model: LibraryModel, playlists: PlaylistsModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(accessoryWidth, 32.dp).clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button) { open = true }
                .semantics { contentDescription = "View and sort" },
            contentAlignment = Alignment.Center,
        ) { SlidersIcon(20.dp) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun pick(action: () -> Unit) {
                action()
                open = false
            }
            MenuHeading("View")
            for (mode in LibraryDisplayMode.entries) {
                MenuChoice(mode.title, mode == model.displayMode, icon = {
                    if (mode == LibraryDisplayMode.GRID) GridIcon(18.dp) else ListIcon(18.dp)
                }) { pick { model.displayMode = mode } }
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.15f))
            MenuHeading("Sort")
            for (sort in playlists.sortOptions) {
                MenuChoice(sort.title, sort == playlists.sortOption) { pick { playlists.sortOption = sort } }
            }
        }
    }
}

/** The user's playlists, grid or list; a failed fetch shows the banner in the list's place (as iOS). */
@Composable
private fun PlaylistsTab(
    api: JellyfinApi,
    playlists: PlaylistsModel,
    displayMode: LibraryDisplayMode,
    onOpen: (JellyfinPlaylist) -> Unit,
    grid: androidx.compose.foundation.lazy.grid.LazyGridState,
    list: LazyListState,
) {
    Column(Modifier.fillMaxSize()) {
        val shown = playlists.playlists
        val error = playlists.errorMessage
        when {
            playlists.isOffline && shown.isEmpty() -> LibraryNote(PlaylistsModel.NONE_DOWNLOADED)
            playlists.isOffline -> when (displayMode) {
                LibraryDisplayMode.GRID -> PlaylistGrid(api, playlists, shown, grid, onOpen)
                LibraryDisplayMode.LIST -> PlaylistList(api, playlists, shown, list, onOpen)
            }
            playlists.isLoading -> LibrarySpinner()
            error != null -> StatusBanner(error, "Retry", playlists::load)
            shown.isNotEmpty() -> when (displayMode) {
                LibraryDisplayMode.GRID -> PlaylistGrid(api, playlists, shown, grid, onOpen)
                LibraryDisplayMode.LIST -> PlaylistList(api, playlists, shown, list, onOpen)
            }
            playlists.loaded -> LibraryNote("No playlists yet. Add songs from an album's track menu.")
        }
    }
}

@Composable
private fun MenuHeading(title: String) {
    Text(title, color = TetsubiColors.albumYearText, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp))
}

@Composable
private fun MenuChoice(title: String, selected: Boolean, icon: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(title, color = Color.White) },
        leadingIcon = icon,
        trailingIcon = { if (selected) Text("✓", color = TetsubiColors.blue, fontSize = 15.sp) },
        onClick = onClick,
        modifier = Modifier.semantics { if (selected) contentDescription = "$title, selected" },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumsTab(
    api: JellyfinApi,
    model: LibraryModel,
    onOpen: (JellyfinAlbum) -> Unit,
    localArt: (JellyfinAlbum) -> String?,
    grid: androidx.compose.foundation.lazy.grid.LazyGridState,
    list: LazyListState,
) {
    when {
        model.isLoading -> LibrarySpinner()
        model.errorMessage != null -> ErrorState(model.errorMessage.orEmpty(), model::load)
        model.sortedAlbums.isEmpty() -> EmptyState(model)
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
                    grid.scrollToItem(0)
                    list.scrollToItem(0)
                }
            }
            val key = model.indexKey
            val firstIds = remember(model.sortedAlbums, model.sortOption) {
                key?.let { AlphaIndex.firstIds(model.sortedAlbums, it) { album -> album.id } }.orEmpty()
            }
            val content = @Composable {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().padding(end = if (key != null) AlphaIndexBarWidth else 0.dp)) {
                        when (model.displayMode) {
                            LibraryDisplayMode.GRID -> LibraryAlbumGrid(
                                api, model.sortedAlbums, grid, onOpen, model::isDownloaded, localArt,
                                trailingEdge = key == null, onAppear = model::loadMoreIfNeeded, zoom = model.gridZoom,
                            )
                            LibraryDisplayMode.LIST -> LibraryAlbumList(
                                api, model.sortedAlbums, list, onOpen, model::isDownloaded, localArt, onAppear = model::loadMoreIfNeeded,
                            )
                        }
                    }
                    if (key != null) {
                        AlphaIndexBar(firstIds.keys, onSelect = { label ->
                            // The letter, or the next one that has albums.
                            val id = AlphaIndex.target(label, firstIds) ?: return@AlphaIndexBar
                            val index = model.sortedAlbums.indexOfFirst { it.id == id }.coerceAtLeast(0)
                            scope.launch {
                                if (model.displayMode == LibraryDisplayMode.GRID) grid.scrollToItem(index) else list.scrollToItem(index)
                            }
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
private fun EmptyState(model: LibraryModel) {
    val downloadedOnly = model.filter == LibraryFilter.DOWNLOADED || model.isOffline
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("♫", color = TetsubiColors.albumYearText, fontSize = 48.sp)
        Text(
            if (downloadedOnly) "No downloaded albums yet" else "Library is empty",
            color = TetsubiColors.albumArtistText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
        )
        Text(
            if (downloadedOnly) "Download an album from its detail page to play it offline." else "Connect to a server with music to get started.",
            color = TetsubiColors.albumYearText, fontSize = 12.sp, textAlign = TextAlign.Center,
        )
        if (model.filter == LibraryFilter.DOWNLOADED && !model.isOffline) {
            TextButton(onClick = { model.filter = LibraryFilter.ALL }) { Text("Show all albums", color = TetsubiColors.blue) }
        }
    }
}

@Composable
private fun ErrorState(message: String, retry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("⚠︎", color = Color(0xFFFF9F0A), fontSize = 48.sp)
        Text("Couldn't load library", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(message, color = TetsubiColors.albumArtistText, fontSize = 12.sp, textAlign = TextAlign.Center)
        Button(onClick = retry, colors = ButtonDefaults.buttonColors(containerColor = TetsubiColors.blue)) { Text("Retry", color = Color.White) }
    }
}

/**
 * All album artists with the A–Z strip (as iOS `ArtistsTabView`): round
 * pictures in a grid (the album cards' size and columns) or today's list;
 * the strip jumps in either. Tap one for their albums.
 */
@Composable
private fun ArtistsTab(
    api: JellyfinApi,
    model: LibraryModel,
    onOpenArtist: (JellyfinArtist) -> Unit,
    list: LazyListState,
    grid: androidx.compose.foundation.lazy.grid.LazyGridState,
) {
    when {
        !model.artistsLoaded -> LibrarySpinner()
        model.artists.isEmpty() -> LibraryNote("No artists found.")
        else -> {
            val scope = rememberCoroutineScope()
            val firstIds = remember(model.artists) { AlphaIndex.firstIds(model.artists, { it.name }) { it.id } }
            val bottom = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
            val isGrid = model.artistsDisplayMode == LibraryDisplayMode.GRID
            Box(Modifier.fillMaxSize()) {
                if (isGrid) {
                    Box(Modifier.fillMaxSize().padding(end = AlphaIndexBarWidth)) {
                        LibraryArtistGrid(api, model.artists, grid, onOpenArtist, zoom = model.gridZoom)
                    }
                } else {
                    LazyColumn(
                        state = list,
                        contentPadding = PaddingValues(bottom = 16.dp + bottom),
                        modifier = Modifier.fillMaxSize().padding(end = AlphaIndexBarWidth),
                    ) {
                        items(model.artists, key = { it.id }) { artist ->
                            Row(
                                Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpenArtist(artist) }.padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                AlbumArt(api, artist.id, size = 48.dp, cornerRadius = 24.dp, maxWidth = 100)
                                Text(artist.name, color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                AlphaIndexBar(firstIds.keys, onSelect = { label ->
                    val id = AlphaIndex.target(label, firstIds) ?: return@AlphaIndexBar
                    val index = model.artists.indexOfFirst { it.id == id }.coerceAtLeast(0)
                    scope.launch { if (isGrid) grid.scrollToItem(index) else list.scrollToItem(index) }
                }, modifier = Modifier.align(Alignment.CenterEnd))
            }
        }
    }
}
