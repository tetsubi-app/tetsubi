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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Home (as `HomeView` on iOS): titled, horizontally scrolling rows over the
 * background picture. Pull down to reload. Sign out sits top right until
 * Settings arrives (A5). Offline: a banner and the downloaded albums only.
 * A Playlists row follows Favourites when there's at least one playlist;
 * its › goes to the Library's Playlists tab ([onMorePlaylists]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    api: JellyfinApi,
    model: HomeModel,
    onOpen: (JellyfinAlbum) -> Unit,
    onMore: (AlbumList) -> Unit,
    onSignOut: () -> Unit,
    network: StateFlow<NetworkState> = MutableStateFlow(NetworkState()),
    downloads: DownloadManager? = null,
    playlists: PlaylistsModel? = null,
    onOpenPlaylist: (JellyfinPlaylist) -> Unit = {},
    onMorePlaylists: () -> Unit = {},
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

    PullToRefreshBox(
        isRefreshing = model.isLoading && hasAnyAlbums(model) && !model.isOffline,
        onRefresh = {
            model.refresh()
            playlists?.load()
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = rememberLazyListState(),
            contentPadding = insets,
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onSignOut) { Text("Sign out", color = TetsubiColors.albumArtistText, fontSize = 13.sp) }
                }
            }
            if (model.isOffline) {
                item { StatusBanner(OFFLINE_MESSAGE, icon = "\u2298") }
                section("Downloaded")
                item(key = "row-Downloaded") {
                    if (downloadedRow.isNotEmpty()) {
                        AlbumRow(api, downloadedRow, onOpen, localArt = { album -> downloads.coverPath(album) }, isDownloaded = isDownloaded)
                    } else {
                        EmptyLine("No downloaded albums yet.")
                    }
                }
                // Downloaded playlists, when there are any (as online: no row without playlists).
                val offlinePlaylists = playlists?.playlists.orEmpty()
                if (playlists != null && offlinePlaylists.isNotEmpty()) {
                    section("Playlists", onMorePlaylists)
                    item(key = "row-Playlists") { PlaylistRow(api, playlists, offlinePlaylists, onOpenPlaylist) }
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
                            isDownloaded = isDownloaded,
                        )
                    } else {
                        AlbumRowPlaceholder()
                    }
                }
            }
            albumSection("Favourites", model.favoriteAlbums, model.isLoading, "No favourite albums yet.", api, onOpen, isDownloaded) { onMore(AlbumList.FAVOURITES) }
            albumSection("Random Albums", model.randomAlbums, model.isLoading, "No random albums available.", api, onOpen, isDownloaded) { onMore(AlbumList.RANDOM) }
            val playlistRow = playlists?.playlists.orEmpty()
            if (playlists != null && playlistRow.isNotEmpty()) {
                section("Playlists", onMorePlaylists)
                item(key = "row-Playlists") { PlaylistRow(api, playlists, playlistRow, onOpenPlaylist) }
            }
            albumSection("Recently Played", model.recentlyPlayed, model.isLoading, "No recently played music yet.", api, onOpen, isDownloaded)
            albumSection("Recently Added", model.recentlyAdded, model.isLoading, "No recently added music.", api, onOpen, isDownloaded)
            item { androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 24.dp)) }
        }
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
    onMore: (() -> Unit)? = null,
) {
    section(title, onMore)
    item(key = "row-$title") {
        when {
            albums.isNotEmpty() -> AlbumRow(api, albums, onOpen, isDownloaded = isDownloaded)
            isLoading -> AlbumRowPlaceholder()
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
