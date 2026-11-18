package gg.dekiru.musicplayer.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinPlaylist
import gg.dekiru.musicplayer.data.network.NetworkState
import gg.dekiru.musicplayer.data.update.UpdateChecker
import gg.dekiru.musicplayer.data.update.UpdateInfo
import gg.dekiru.musicplayer.data.update.UpdateText
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.downloads.DownloadedAlbum
import gg.dekiru.musicplayer.ui.downloads.coverPath
import gg.dekiru.musicplayer.ui.playlist.PlaylistRow
import gg.dekiru.musicplayer.ui.playlist.PlaylistsModel
import gg.dekiru.musicplayer.ui.downloads.rememberIsDownloaded
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.components.AlbumRow
import gg.dekiru.musicplayer.ui.components.AlbumRowPlaceholder
import gg.dekiru.musicplayer.ui.components.ChevronRightIcon
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import gg.dekiru.musicplayer.ui.library.CardGrid
import gg.dekiru.musicplayer.ui.library.GridZoom
import gg.dekiru.musicplayer.ui.library.GridZoomButtons
import gg.dekiru.musicplayer.ui.library.LibraryModel
import gg.dekiru.musicplayer.ui.library.isWideLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Home (as `HomeView` on iOS): titled, horizontally scrolling rows over the
 * background picture. Pull down to reload. Signing out is in Settings, as
 * iOS. Offline: a banner and the downloaded albums only.
 * A Playlists row follows Favourites when there's at least one playlist;
 * its › goes to the Library's Playlists tab ([onMorePlaylists]).
 * Wide layouts (with [zoom]): every row's cards follow the grids' shared
 * cards-per-row setting, a third of the next card showing at the end
 * ([LibraryModel.homeRow]), and the − / + buttons sit at the right of a
 * top row that stays put (iOS: Home's toolbar). Phones: no top row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    api: JellyfinApi,
    model: HomeModel,
    onOpen: (JellyfinAlbum) -> Unit,
    onMore: (AlbumList) -> Unit,
    network: StateFlow<NetworkState> = MutableStateFlow(NetworkState()),
    downloads: DownloadManager? = null,
    playlists: PlaylistsModel? = null,
    onOpenPlaylist: (JellyfinPlaylist) -> Unit = {},
    onMorePlaylists: () -> Unit = {},
    zoom: GridZoom? = null,
    updates: UpdateChecker? = null,
) {
    // Loads on first appearance; reloads when the connection comes back.
    LaunchedEffect(model, network) { network.collect { model.networkChanged(it.online) } }
    LaunchedEffect(playlists, network) {
        if (playlists != null) network.map { it.online }.distinctUntilChanged().collect(playlists::networkChanged)
    }
    val emptyDownloads = remember { MutableStateFlow(emptyList<DownloadedAlbum>()) }
    val downloadedAlbums by (downloads?.downloaded ?: emptyDownloads).collectAsState()
    val downloadedRow = remember(downloadedAlbums) { HomeModel.downloadedRow(downloadedAlbums) }
    val isDownloaded = rememberIsDownloaded(downloads)
    // Scrolling content stops below the status bar; the bottom inset is padding inside the list.
    val insets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues()
    val wide = zoom != null && isWideLayout()
    val density = LocalDensity.current.density

    PullToRefreshBox(
        isRefreshing = model.isLoading && hasAnyAlbums(model) && !model.isOffline,
        onRefresh = {
            model.refresh()
            playlists?.load()
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
            // Wide: a top row that stays put, with the zoom buttons.
            if (wide) TopRow(zoom)
            BoxWithConstraints(Modifier.weight(1f)) {
                val row = LibraryModel.homeRow(maxWidth.value, wide, zoom?.columns, density)
                if (wide) SideEffect { zoom.shown = row.columns }
                val size = row.takeIf { wide }
                LazyColumn(
                    state = rememberLazyListState(),
                    contentPadding = insets,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // A newer release (Android and Linux), above everything else, offline too.
                    updates?.banner?.let { update -> item(key = "update-${update.version}") { UpdateBanner(update, onSkip = updates::skip) } }
                    if (model.isOffline) {
                        item { StatusBanner(OFFLINE_MESSAGE, icon = "\u2298") }
                        section("Downloaded")
                        item(key = "row-Downloaded") {
                            if (downloadedRow.isNotEmpty()) {
                                AlbumRow(api, downloadedRow, onOpen, localArt = { album -> downloads.coverPath(album) }, isDownloaded = isDownloaded, size = size)
                            } else {
                                EmptyLine("No downloaded albums yet.")
                            }
                        }
                        // Downloaded playlists, when there are any (as online: no row without playlists).
                        val offlinePlaylists = playlists?.playlists.orEmpty()
                        if (playlists != null && offlinePlaylists.isNotEmpty()) {
                            section("Playlists", onMorePlaylists)
                            item(key = "row-Playlists") { PlaylistRow(api, playlists, offlinePlaylists, onOpenPlaylist, size) }
                        }
                        item { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 24.dp)) }
                        return@LazyColumn
                    }
                    model.errorMessage?.takeIf { !model.isLoading }?.let { error ->
                        item {
                            StatusBanner("Can't reach your Jellyfin server. $error", "Retry", action = {
                                model.refresh()
                                playlists?.load()
                            })
                        }
                    }

                    // On This Day first: the most distinctive row. Shown while Home
                    // loads and when something matched; hidden otherwise.
                    if (model.onThisDay.isNotEmpty() || model.isLoading) {
                        section("On This Day")
                        item(key = "row-On This Day") {
                            if (model.onThisDay.isNotEmpty()) {
                                AlbumRow(
                                    api, model.onThisDay.map { it.album }, onOpen,
                                    badge = { album -> model.onThisDay.firstOrNull { it.album.id == album.id }?.label },
                                    isDownloaded = isDownloaded, size = size,
                                )
                            } else {
                                AlbumRowPlaceholder(size)
                            }
                        }
                    }
                    albumSection("Favourites", model.favoriteAlbums, model.isLoading, "No favourite albums yet.", api, onOpen, isDownloaded, size) { onMore(AlbumList.FAVOURITES) }
                    albumSection("Random Albums", model.randomAlbums, model.isLoading, "No random albums available.", api, onOpen, isDownloaded, size) { onMore(AlbumList.RANDOM) }
                    val playlistRow = playlists?.playlists.orEmpty()
                    if (playlists != null && playlistRow.isNotEmpty()) {
                        section("Playlists", onMorePlaylists)
                        item(key = "row-Playlists") { PlaylistRow(api, playlists, playlistRow, onOpenPlaylist, size) }
                    }
                    albumSection("Recently Played", model.recentlyPlayed, model.isLoading, "No recently played music yet.", api, onOpen, isDownloaded, size)
                    albumSection("Recently Added", model.recentlyAdded, model.isLoading, "No recently added music.", api, onOpen, isDownloaded, size)
                    item { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 24.dp)) }
                }
            }
        }
    }
}

/** Wide layouts: the zoom buttons at the top right (as iOS's trailing toolbar items). */
@Composable
private fun TopRow(zoom: GridZoom) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        GridZoomButtons(zoom)
    }
}

private fun hasAnyAlbums(model: HomeModel) =
    model.onThisDay.isNotEmpty() || model.favoriteAlbums.isNotEmpty() || model.recentlyPlayed.isNotEmpty() ||
        model.recentlyAdded.isNotEmpty() || model.randomAlbums.isNotEmpty()

/** A row title; with `onMore`, a › on the right opens the full list. */
private fun LazyListScope.section(title: String, onMore: (() -> Unit)? = null) {
    item(key = "title-$title") {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (onMore != null) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable(role = Role.Button, onClick = onMore)
                        .semantics { contentDescription = "View all $title" },
                    contentAlignment = Alignment.Center,
                ) { ChevronRightIcon(26.dp, TetsubiColors.blue) }
            }
        }
    }
}

private fun LazyListScope.albumSection(
    title: String,
    albums: List<JellyfinAlbum>,
    isLoading: Boolean,
    emptyMessage: String,
    api: JellyfinApi,
    onOpen: (JellyfinAlbum) -> Unit,
    isDownloaded: (JellyfinAlbum) -> Boolean,
    size: CardGrid?,
    onMore: (() -> Unit)? = null,
) {
    section(title, onMore)
    item(key = "row-$title") {
        when {
            albums.isNotEmpty() -> AlbumRow(api, albums, onOpen, isDownloaded = isDownloaded, size = size)
            isLoading -> AlbumRowPlaceholder(size)
            else -> EmptyLine(emptyMessage)
        }
    }
}

@Composable
private fun EmptyLine(message: String) {
    Text(message, color = TetsubiColors.albumYearText, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

/** Shown on Home while offline (as `StatusBanner.offline` on iOS). */
const val OFFLINE_MESSAGE = "You're offline. Downloaded albums still play from the Downloads tab."

/** An offline / server-error notice at the top of a screen (as `StatusBanner` on iOS). */
@Composable
fun StatusBanner(message: String, actionTitle: String? = null, action: (() -> Unit)? = null, icon: String = "\u26A0") {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(TetsubiColors.groupBackground, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(icon, color = TetsubiColors.pink, fontSize = 15.sp)
        Text(message, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (actionTitle != null && action != null) {
            OutlinedButton(onClick = action) { Text(actionTitle, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/**
 * A newer release on GitHub (Android and Linux): in the style of [StatusBanner].
 * Download opens the release page in the browser (nothing is downloaded by the
 * app); What's new shows the release notes; Skip this version hides it for good.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UpdateBanner(update: UpdateInfo, onSkip: () -> Unit) {
    val uri = LocalUriHandler.current
    var showNotes by rememberSaveable(update.version) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(TetsubiColors.groupBackground, RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("\u2193", color = TetsubiColors.blue, fontSize = 15.sp)
            Text(UpdateText.available(update.version), color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { runCatching { uri.openUri(update.pageUrl) } }) {
                Text(UpdateText.DOWNLOAD, color = TetsubiColors.blue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = { showNotes = true }) { Text(UpdateText.WHATS_NEW, color = TetsubiColors.blue, fontSize = 13.sp) }
            TextButton(onClick = onSkip) { Text(UpdateText.SKIP, color = TetsubiColors.albumArtistText, fontSize = 13.sp) }
        }
    }
    if (showNotes) {
        AlertDialog(
            onDismissRequest = { showNotes = false },
            title = { Text(UpdateText.notesTitle(update.version)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(update.notes.ifBlank { UpdateText.NO_NOTES })
                }
            },
            confirmButton = { TextButton(onClick = { showNotes = false }) { Text(UpdateText.CLOSE) } },
        )
    }
}
