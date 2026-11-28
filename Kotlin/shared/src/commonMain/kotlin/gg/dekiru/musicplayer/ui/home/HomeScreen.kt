package gg.dekiru.musicplayer.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinPlaylist
import gg.dekiru.musicplayer.data.network.NetworkState
import gg.dekiru.musicplayer.data.update.UpdateChecker
import gg.dekiru.musicplayer.data.update.UpdateInfo
import gg.dekiru.musicplayer.data.update.UpdateText
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.downloads.DownloadedAlbum
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.components.ActionChip
import gg.dekiru.musicplayer.ui.components.AlbumShelf
import gg.dekiru.musicplayer.ui.components.AlbumShelfPlaceholder
import gg.dekiru.musicplayer.ui.components.Banner
import gg.dekiru.musicplayer.ui.components.BannerKind
import gg.dekiru.musicplayer.ui.components.HeaderIconButton
import gg.dekiru.musicplayer.ui.components.PageHeader
import gg.dekiru.musicplayer.ui.components.RetryIcon
import gg.dekiru.musicplayer.ui.components.SectionHeader
import gg.dekiru.musicplayer.ui.downloads.coverPath
import gg.dekiru.musicplayer.ui.downloads.rememberIsDownloaded
import gg.dekiru.musicplayer.ui.layout.LocalWindowClass
import gg.dekiru.musicplayer.ui.layout.rememberBottomInset
import gg.dekiru.musicplayer.ui.layout.screenInsets
import gg.dekiru.musicplayer.ui.playlist.PlaylistShelf
import gg.dekiru.musicplayer.ui.playlist.PlaylistsModel
import gg.dekiru.musicplayer.ui.tetsubiType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Home (as `HomeView` on iOS): a page header (Refresh) over shelves of the app's cards, each with a
 * section header and "See all". The cards are the grid's own, so the card size setting changes them
 * too; on a narrow window the next card peeks in at the edge. Pull down to reload. Signing out is in
 * Settings, as iOS. Offline: a banner and the downloaded albums only.
 * A Playlists shelf follows Random albums when there's at least one playlist;
 * its "See all" goes to the Library's Playlists tab ([onMorePlaylists]).
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
    /** Recently Played's "See all": the Recently Played page. */
    onMoreRecentlyPlayed: () -> Unit = {},
    updates: UpdateChecker? = null,
    /** Why nothing can play here (Linux without libmpv), shown as a banner; null: nothing to say. */
    playerUnavailable: String? = null,
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
    val bottom = rememberBottomInset()
    fun reload() {
        model.refresh()
        playlists?.load()
    }

    Column(Modifier.fillMaxSize().screenInsets()) {
        PageHeader("Home") {
            if (!model.isOffline) HeaderIconButton("Refresh", ::reload) { RetryIcon(22.dp, TetsubiColors.textPrimary) }
        }
        PullToRefreshBox(
            isRefreshing = model.isLoading && hasAnyAlbums(model) && !model.isOffline,
            onRefresh = ::reload,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            LazyColumn(
                state = rememberLazyListState(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp + bottom),
                modifier = Modifier.fillMaxSize(),
            ) {
                // A newer release (Android and Linux), above everything else, offline too.
                updates?.banner?.let { update -> item(key = "update-${update.version}") { UpdateBanner(update, onSkip = updates::skip) } }
                // No player on this system (Linux without libmpv), offline too.
                playerUnavailable?.let { message -> item(key = "player-unavailable") { StatusBanner(message) } }
                if (model.isOffline) {
                    item { StatusBanner(OFFLINE_MESSAGE, kind = BannerKind.Offline) }
                    section("Downloaded")
                    item(key = "row-Downloaded") {
                        if (downloadedRow.isNotEmpty()) {
                            AlbumShelf(api, downloadedRow, onOpen, localArt = { album -> downloads.coverPath(album) }, isDownloaded = isDownloaded)
                        } else {
                            EmptyLine("No downloaded albums yet.")
                        }
                    }
                    // Downloaded playlists, when there are any (as online: no shelf without playlists).
                    val offlinePlaylists = playlists?.playlists.orEmpty()
                    if (playlists != null && offlinePlaylists.isNotEmpty()) {
                        section("Playlists", onMorePlaylists)
                        item(key = "row-Playlists") { PlaylistShelf(api, playlists, offlinePlaylists, onOpenPlaylist) }
                    }
                    return@LazyColumn
                }
                model.errorMessage?.takeIf { !model.isLoading }?.let { error ->
                    item { StatusBanner("Can't reach your Jellyfin server. $error", "Retry", action = ::reload) }
                }

                // On this day first: the most distinctive shelf. Shown while Home
                // loads and when something matched; hidden otherwise.
                if (model.onThisDay.isNotEmpty() || model.isLoading) {
                    section("On this day")
                    item(key = "row-On this day") {
                        if (model.onThisDay.isNotEmpty()) {
                            AlbumShelf(
                                api, model.onThisDay.map { it.album }, onOpen,
                                badge = { album -> model.onThisDay.firstOrNull { it.album.id == album.id }?.label },
                                isDownloaded = isDownloaded,
                            )
                        } else {
                            AlbumShelfPlaceholder()
                        }
                    }
                }
                albumSection("Favourites", model.favoriteAlbums, model.isLoading, "No favourite albums yet.", api, onOpen, isDownloaded) { onMore(AlbumList.FAVOURITES) }
                albumSection("Random albums", model.randomAlbums, model.isLoading, "No random albums available.", api, onOpen, isDownloaded) { onMore(AlbumList.RANDOM) }
                val playlistRow = playlists?.playlists.orEmpty()
                if (playlists != null && playlistRow.isNotEmpty()) {
                    section("Playlists", onMorePlaylists)
                    item(key = "row-Playlists") { PlaylistShelf(api, playlists, playlistRow, onOpenPlaylist) }
                }
                albumSection("Recently played", model.recentlyPlayed, model.isLoading, "No recently played music yet.", api, onOpen, isDownloaded, onMoreRecentlyPlayed)
                albumSection("Recently added", model.recentlyAdded, model.isLoading, "No recently added music.", api, onOpen, isDownloaded)
            }
        }
    }
}

private fun hasAnyAlbums(model: HomeModel) =
    model.onThisDay.isNotEmpty() || model.favoriteAlbums.isNotEmpty() || model.recentlyPlayed.isNotEmpty() ||
        model.recentlyAdded.isNotEmpty() || model.randomAlbums.isNotEmpty()

/** A shelf title (20 semibold); with `onMore`, a blue "See all" on the right opens the full list. */
private fun LazyListScope.section(title: String, onMore: (() -> Unit)? = null) {
    item(key = "title-$title") { SectionHeader(title, Modifier.padding(top = 8.dp), onSeeAll = onMore) }
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
            albums.isNotEmpty() -> AlbumShelf(api, albums, onOpen, isDownloaded = isDownloaded)
            isLoading -> AlbumShelfPlaceholder()
            else -> EmptyLine(emptyMessage)
        }
    }
}

@Composable
private fun EmptyLine(message: String) {
    Text(
        message, style = tetsubiType.body, color = TetsubiColors.textSecondary,
        modifier = Modifier.padding(horizontal = LocalWindowClass.current.pageMargin, vertical = 4.dp),
    )
}

/** Shown on Home while offline (as `StatusBanner.offline` on iOS). */
const val OFFLINE_MESSAGE = "You're offline. Downloaded albums still play from the Downloads tab."

/** An offline / server-error notice at the top of a screen (as `StatusBanner` on iOS): the one [Banner] style. */
@Composable
fun StatusBanner(message: String, actionTitle: String? = null, action: (() -> Unit)? = null, kind: BannerKind = BannerKind.Warning) {
    Banner(message, kind = kind, actionTitle = actionTitle, onAction = action)
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
    Banner(UpdateText.available(update.version), kind = BannerKind.Update) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionChip(UpdateText.DOWNLOAD, { runCatching { uri.openUri(update.pageUrl) } }, color = TetsubiColors.blue)
            ActionChip(UpdateText.WHATS_NEW, { showNotes = true })
            ActionChip(UpdateText.SKIP, onSkip, color = TetsubiColors.textSecondary)
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
