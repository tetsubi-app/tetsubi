package gg.dekiru.musicplayer.ui.downloads

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinPerson
import gg.dekiru.musicplayer.data.models.JellyfinPlaylist
import gg.dekiru.musicplayer.downloads.DownloadAlbumState
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.downloads.DownloadStatus
import gg.dekiru.musicplayer.downloads.DownloadedAlbum
import gg.dekiru.musicplayer.downloads.DownloadedPlaylist
import gg.dekiru.musicplayer.downloads.LocalFiles
import gg.dekiru.musicplayer.downloads.PlaylistDownloads
import gg.dekiru.musicplayer.playback.PlayerController
import gg.dekiru.musicplayer.ui.Radii
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.album.AlbumDownloads
import gg.dekiru.musicplayer.ui.components.ActionChip
import gg.dekiru.musicplayer.ui.components.AlbumArt
import gg.dekiru.musicplayer.ui.components.CheckCircleIcon
import gg.dekiru.musicplayer.ui.components.CircleIcon
import gg.dekiru.musicplayer.ui.components.DownloadCircleIcon
import gg.dekiru.musicplayer.ui.components.EmptyState
import gg.dekiru.musicplayer.ui.components.LocalPlayPrompt
import gg.dekiru.musicplayer.ui.components.MediaCard
import gg.dekiru.musicplayer.ui.components.PageHeader
import gg.dekiru.musicplayer.ui.components.PauseIcon
import gg.dekiru.musicplayer.ui.components.PlayChoice
import gg.dekiru.musicplayer.ui.components.PlayIcon
import gg.dekiru.musicplayer.ui.components.PrimaryButton
import gg.dekiru.musicplayer.ui.components.PulsingProgressBar
import gg.dekiru.musicplayer.ui.components.RetryIcon
import gg.dekiru.musicplayer.ui.components.SectionHeader
import gg.dekiru.musicplayer.ui.components.SettingsRow
import gg.dekiru.musicplayer.ui.components.SettingsValue
import gg.dekiru.musicplayer.ui.components.XCircleIcon
import gg.dekiru.musicplayer.ui.components.artPixels
import gg.dekiru.musicplayer.ui.layout.CardGrid
import gg.dekiru.musicplayer.ui.layout.CardMetrics
import gg.dekiru.musicplayer.ui.layout.CardShelf
import gg.dekiru.musicplayer.ui.layout.LocalWindowClass
import gg.dekiru.musicplayer.ui.layout.WindowClass
import gg.dekiru.musicplayer.ui.layout.bleed
import gg.dekiru.musicplayer.ui.layout.fullWidthItem
import gg.dekiru.musicplayer.ui.layout.headerItem
import gg.dekiru.musicplayer.ui.layout.rememberBottomInset
import gg.dekiru.musicplayer.ui.layout.rowItems
import gg.dekiru.musicplayer.ui.layout.screenInsets
import gg.dekiru.musicplayer.ui.tetsubiType

/**
 * The Downloads tab (as iOS `DownloadsView`): a page header with Edit, albums downloading (progress,
 * Pause/Resume/Retry, Cancel), a shelf of downloaded playlists (tap to open the playlist's page),
 * the downloaded albums as the app's grid, newest first (tap to play; Edit, or a long press, to
 * select several and "Remove selected"), and the storage used: a card beside the grid on Expanded,
 * below it otherwise.
 */
@Composable
fun DownloadsScreen(api: JellyfinApi, downloads: DownloadManager?, player: PlayerController?, onOpenPlaylist: (JellyfinPlaylist) -> Unit = {}) {
    val active by downloads.collectActive()
    val downloaded by downloads.collectDownloaded()
    val allPlaylists by downloads.collectPlaylists()
    val playlists = allPlaylists.filter { it.isComplete }
    var isEditing by rememberSaveable { mutableStateOf(false) }
    // Playlists go by their queue key in the selection, so they can't be taken for albums.
    var selection by rememberSaveable { mutableStateOf(setOf<String>()) }
    var confirmRemove by remember { mutableStateOf(false) }
    val prompt = LocalPlayPrompt.current
    val windowClass = LocalWindowClass.current
    val gridState = rememberLazyGridState()
    val hasDownloads = downloaded.isNotEmpty() || playlists.isNotEmpty()
    // Nothing left to edit (removed elsewhere): leave edit mode.
    LaunchedEffect(hasDownloads) {
        if (!hasDownloads) {
            isEditing = false
            selection = emptySet()
        }
    }

    Column(Modifier.fillMaxSize().screenInsets()) {
        PageHeader("Downloads") {
            if (hasDownloads) ActionChip(if (isEditing) "Done" else "Edit", { isEditing = !isEditing; selection = emptySet() })
        }
        if (downloads == null || (active.isEmpty() && !hasDownloads)) {
            EmptyState(
                DownloadsText.EMPTY_TITLE, message = DownloadsText.EMPTY_MESSAGE,
                icon = { DownloadCircleIcon(48.dp, TetsubiColors.textTertiary) },
            )
        } else {
            val storage = @Composable { Storage(downloaded, playlists) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        CardGrid(state = gridState, bottomPadding = rememberBottomInset() + (if (isEditing) 72.dp else 0.dp)) { metrics ->
                            if (active.isNotEmpty()) {
                                headerItem("downloading-header") { SectionHeader("Downloading", horizontalPadding = 0.dp) }
                                rowItems(active.values.toList(), metrics, key = { "active-${it.albumId}" }) { state ->
                                    DownloadingRow(api, downloads, state)
                                }
                            }
                            if (playlists.isNotEmpty()) {
                                headerItem("playlists-header") { SectionHeader("Playlists", horizontalPadding = 0.dp) }
                                fullWidthItem("playlists-shelf") {
                                    CardShelf(playlists, key = { it.playlistId }, Modifier.bleed(windowClass.pageMargin)) { playlist, width ->
                                        val key = PlaylistDownloads.key(playlist.playlistId)
                                        PlaylistDownloadedCard(
                                            api, playlist, width, isEditing, isSelected = key in selection,
                                            onOpen = { onOpenPlaylist(playlist.manifest.toPlaylist()) },
                                            onToggle = { selection = if (key in selection) selection - key else selection + key },
                                            onLongPress = {
                                                isEditing = true
                                                selection = setOf(key)
                                            },
                                        )
                                    }
                                }
                            }
                            if (downloaded.isNotEmpty()) {
                                headerItem("downloaded-header") { SectionHeader("Downloaded", horizontalPadding = 0.dp) }
                                items(downloaded, key = { "done-${it.albumId}" }) { album ->
                                    DownloadedCard(
                                        api, album, metrics.cardWidth, isEditing, isSelected = album.albumId in selection,
                                        canPlay = player?.isSupported == true,
                                        // More than the playing album queued: Replace queue, Play Next or Add to Queue.
                                        onPlay = {
                                            val a = album.toAlbum()
                                            player?.let { p ->
                                                prompt.play(p, PlayChoice(a.displayName, replace = { p.play(a) }, playNext = { p.playNext(a) }, addToQueue = { p.addToQueue(a) }))
                                            }
                                        },
                                        onToggle = { selection = if (album.albumId in selection) selection - album.albumId else selection + album.albumId },
                                        onLongPress = {
                                            isEditing = true
                                            selection = setOf(album.albumId)
                                        },
                                    )
                                }
                            }
                            if (windowClass != WindowClass.Expanded) fullWidthItem("storage") { storage() }
                        }
                    }
                    // Expanded: the storage card stays beside the grid.
                    if (windowClass == WindowClass.Expanded) {
                        Column(
                            Modifier.width(352.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                                .padding(top = 32.dp, end = 32.dp, bottom = 32.dp),
                        ) { storage() }
                    }
                }
                if (isEditing) {
                    // As the tab bar: navy at 85% with a hairline on top, content at the page margin.
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(TetsubiColors.background.copy(alpha = 0.85f))) {
                        Box(Modifier.fillMaxWidth().height(0.5.dp).background(TetsubiColors.hairline))
                        PrimaryButton(
                            DownloadsText.removeLabel(selection.size), { confirmRemove = true },
                            Modifier.fillMaxWidth().padding(horizontal = windowClass.pageMargin, vertical = 12.dp),
                            enabled = selection.isNotEmpty(), color = TetsubiColors.red,
                        )
                    }
                }
            }
        }
    }

    if (confirmRemove) {
        val count = selection.size
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(if (count == 1) "Remove this download?" else "Remove $count downloads?") },
            text = {
                val chosenPlaylists = selection.count(PlaylistDownloads::isKey)
                Text(DownloadsText.removeMessage(albums = count - chosenPlaylists, playlists = chosenPlaylists))
            },
            confirmButton = {
                TextButton(onClick = {
                    val (playlistKeys, albumIds) = selection.partition(PlaylistDownloads::isKey)
                    downloads?.playlists?.delete(playlistKeys.map { it.removePrefix(PlaylistDownloads.key("")) })
                    if (albumIds.isNotEmpty()) downloads?.delete(albumIds)
                    selection = emptySet()
                    isEditing = false
                    confirmRemove = false
                }) { Text("Remove", color = TetsubiColors.red) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

/** What the player needs to play a download (it plays from the files). */
private fun DownloadedAlbum.toAlbum() =
    JellyfinAlbum(id = albumId, name = title, productionYear = year, albumArtists = listOf(JellyfinPerson(name = artist)))

/** In edit mode: an empty or ticked circle over the cover's corner. */
@Composable
private fun BoxScope.SelectionMark(isSelected: Boolean) {
    Box(Modifier.align(Alignment.TopStart).padding(8.dp)) {
        if (isSelected) CheckCircleIcon(26.dp, TetsubiColors.blue) else CircleIcon(26.dp, TetsubiColors.textPrimary)
    }
}

/** A downloading album or playlist: a card with its cover, names, progress bar, and Pause / Resume / Retry and Cancel. */
@Composable
private fun DownloadingRow(api: JellyfinApi, manager: DownloadManager, state: DownloadAlbumState) {
    val paused = state.status == DownloadStatus.PAUSED
    val failed = state.status == DownloadStatus.FAILED
    val shape = RoundedCornerShape(Radii.panel)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(TetsubiColors.surface, shape).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(Radii.thumb))) {
                val playlistId = state.playlistId
                if (playlistId != null) {
                    val saved = manager.playlists.saved(playlistId)
                    // Its own picture, else its first song's (from the server until it's saved).
                    val artId = saved?.manifest?.takeIf { !it.hasArt }?.tracks?.firstOrNull()?.art ?: playlistId
                    AlbumArt(api, artId, size = 40.dp, cornerRadius = Radii.thumb, maxWidth = 100, localPath = saved?.coverPath)
                } else {
                    val cover = manager.paths.coverPath(state.albumId).takeIf(LocalFiles::exists)
                    AlbumArt(api, state.albumId, size = 40.dp, cornerRadius = Radii.thumb, maxWidth = 100, localPath = cover)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(state.title, style = tetsubiType.rowTitle, color = TetsubiColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (state.playlistId != null) DownloadsText.PLAYLIST else state.artist,
                    style = tetsubiType.caption, color = TetsubiColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(DownloadsText.formatLine(state), style = tetsubiType.caption, color = TetsubiColors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Retry (failed), Resume (paused) or Pause.
            val label = when {
                failed -> "Retry download"
                paused -> "Resume download"
                else -> "Pause download"
            }
            IconButton(label, onClick = { AlbumDownloads.toggle(manager, state) }) {
                when {
                    failed -> RetryIcon(24.dp, TetsubiColors.blue)
                    paused -> PlayIcon(22.dp, TetsubiColors.blue)
                    else -> PauseIcon(22.dp, TetsubiColors.blue)
                }
            }
            IconButton("Cancel", onClick = { manager.cancel(state.albumId) }) { XCircleIcon(22.dp, TetsubiColors.red) }
        }
        PulsingProgressBar(state.progress, isPaused = paused || failed)
        Text(DownloadsText.progressLine(state), style = tetsubiType.caption, color = TetsubiColors.textSecondary)
        state.error?.takeIf { it.isNotEmpty() }?.let {
            Text(it, style = tetsubiType.caption, color = TetsubiColors.red, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A downloaded album as a card: tap plays it; in edit mode (or after a long press) it is selected. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadedCard(
    api: JellyfinApi,
    album: DownloadedAlbum,
    width: Dp,
    isEditing: Boolean,
    isSelected: Boolean,
    canPlay: Boolean,
    onPlay: () -> Unit,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
) {
    val pixels = artPixels(width)
    MediaCard(
        width, album.title, album.artist.takeIf { it.isNotEmpty() }, detail = DownloadsText.detailLine(album),
        modifier = Modifier
            .combinedClickable(
                role = Role.Button,
                onClickLabel = if (isEditing) null else "Play album",
                onLongClick = if (isEditing) null else onLongPress,
                onClick = { if (isEditing) onToggle() else if (canPlay) onPlay() },
            )
            .semantics { if (isEditing) selected = isSelected },
        overlay = { if (isEditing) SelectionMark(isSelected) },
    ) { corner -> AlbumArt(api, album.albumId, size = width, cornerRadius = corner, maxWidth = pixels, localPath = album.coverPath.takeIf(LocalFiles::exists)) }
}

/** A downloaded playlist as a card: its picture, name and "12 songs · 47 min · 512 MB"; tap to open its page. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistDownloadedCard(
    api: JellyfinApi,
    playlist: DownloadedPlaylist,
    width: Dp,
    isEditing: Boolean,
    isSelected: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
) {
    val pixels = artPixels(width)
    val artId = playlist.manifest.takeIf { !it.hasArt }?.tracks?.firstOrNull()?.art ?: playlist.playlistId
    MediaCard(
        width, playlist.name, DownloadsText.detailLine(playlist),
        modifier = Modifier
            .combinedClickable(
                role = Role.Button,
                onClickLabel = if (isEditing) null else "Open playlist",
                onLongClick = if (isEditing) null else onLongPress,
                onClick = { if (isEditing) onToggle() else onOpen() },
            )
            .semantics { if (isEditing) selected = isSelected },
        overlay = { if (isEditing) SelectionMark(isSelected) },
    ) { corner -> AlbumArt(api, artId, size = width, cornerRadius = corner, maxWidth = pixels, localPath = playlist.coverPath) }
}

/** Storage used and total playing time: one card. */
@Composable
private fun Storage(downloaded: List<DownloadedAlbum>, playlists: List<DownloadedPlaylist>) {
    val total = DownloadsText.totalPlayingTime(downloaded, playlists)
    val shape = RoundedCornerShape(Radii.panel)
    Column(Modifier.fillMaxWidth().clip(shape).background(TetsubiColors.surface, shape)) {
        SettingsRow("Storage used", divider = total != null) { SettingsValue(DownloadsText.storageUsed(downloaded, playlists)) }
        if (total != null) SettingsRow("Total playing time", divider = false) { SettingsValue(total) }
    }
}

@Composable
private fun IconButton(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(Radii.FULL_PERCENT))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}
