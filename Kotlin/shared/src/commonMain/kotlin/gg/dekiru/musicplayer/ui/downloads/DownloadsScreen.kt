package gg.dekiru.musicplayer.ui.downloads

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinPerson
import gg.dekiru.musicplayer.downloads.DownloadAlbumState
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.downloads.DownloadStatus
import gg.dekiru.musicplayer.downloads.DownloadedAlbum
import gg.dekiru.musicplayer.downloads.DownloadedPlaylist
import gg.dekiru.musicplayer.downloads.PlaylistDownloads
import gg.dekiru.musicplayer.data.models.JellyfinPlaylist
import gg.dekiru.musicplayer.downloads.LocalFiles
import gg.dekiru.musicplayer.playback.PlayerController
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.album.AlbumDownloads
import gg.dekiru.musicplayer.ui.components.AlbumArt
import gg.dekiru.musicplayer.ui.components.LocalPlayPrompt
import gg.dekiru.musicplayer.ui.components.PlayChoice
import gg.dekiru.musicplayer.ui.components.CheckCircleIcon
import gg.dekiru.musicplayer.ui.components.CircleIcon
import gg.dekiru.musicplayer.ui.components.DownloadCircleIcon
import gg.dekiru.musicplayer.ui.components.PauseIcon
import gg.dekiru.musicplayer.ui.components.PlayIcon
import gg.dekiru.musicplayer.ui.components.PulsingProgressBar
import gg.dekiru.musicplayer.ui.components.RetryIcon
import gg.dekiru.musicplayer.ui.components.XCircleIcon

private val readableWidth = 720.dp
private val secondary = Color.White.copy(alpha = 0.6f)
private val tertiary = Color.White.copy(alpha = 0.4f)

/**
 * The Downloads tab (as iOS `DownloadsView`): albums downloading (progress,
 * Pause/Resume/Retry, Cancel), albums downloaded, newest first (tap to
 * play; Edit, or a long press, to remove several), and the storage used.
 * Downloaded playlists have their own section above the albums (tap to
 * open the playlist's page; removed the same way).
 */
@Composable
fun DownloadsScreen(api: JellyfinApi, downloads: DownloadManager?, player: PlayerController?, onOpenPlaylist: (JellyfinPlaylist) -> Unit = {}) {
    val active by downloads.collectActive()
    val downloaded by downloads.collectDownloaded()
    val allPlaylists by downloads.collectPlaylists()
    val playlists = allPlaylists.filter { it.isComplete }
    var isEditing by rememberSaveable { mutableStateOf(false) }
    var selection by rememberSaveable { mutableStateOf(setOf<String>()) }
    var confirmRemove by remember { mutableStateOf(false) }
    val prompt = LocalPlayPrompt.current
    // Nothing left to edit (removed elsewhere): leave edit mode.
    LaunchedEffect(downloaded.isEmpty() && playlists.isEmpty()) {
        if (downloaded.isEmpty() && playlists.isEmpty()) {
            isEditing = false
            selection = emptySet()
        }
    }
    val insets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues()

    Box(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        contentAlignment = Alignment.TopCenter,
    ) {
        if (downloads == null || (active.isEmpty() && downloaded.isEmpty() && playlists.isEmpty())) {
            EmptyState()
            return@Box
        }
        LazyColumn(
            state = rememberLazyListState(),
            contentPadding = insets,
            modifier = Modifier.widthIn(max = readableWidth).fillMaxSize(),
        ) {
            if (active.isNotEmpty()) {
                item { SectionHeader("Downloading") }
                val states = active.values.toList()
                groupItems(states, key = { "active-${it.albumId}" }) { state ->
                    DownloadingRow(api, downloads, state)
                }
            }
            // Playlists go by their queue key in the selection, so they can't be taken for albums.
            if (playlists.isNotEmpty()) {
                item {
                    SectionHeader("Playlists") {
                        TextButton(onClick = { isEditing = !isEditing; selection = emptySet() }) {
                            Text(if (isEditing) "Done" else "Edit", color = TetsubiColors.blue, fontSize = 15.sp)
                        }
                    }
                }
                groupItems(playlists, key = { "playlist-${it.playlistId}" }) { playlist ->
                    val key = PlaylistDownloads.key(playlist.playlistId)
                    PlaylistDownloadedRow(
                        api, playlist, isEditing, isSelected = key in selection,
                        onOpen = { onOpenPlaylist(playlist.manifest.toPlaylist()) },
                        onToggle = { selection = if (key in selection) selection - key else selection + key },
                        onLongPress = {
                            isEditing = true
                            selection = setOf(key)
                        },
                    )
                }
            }
            item {
                SectionHeader("Downloaded") {
                    if (downloaded.isNotEmpty()) {
                        TextButton(onClick = { isEditing = !isEditing; selection = emptySet() }) {
                            Text(if (isEditing) "Done" else "Edit", color = TetsubiColors.blue, fontSize = 15.sp)
                        }
                    }
                }
            }
            groupItems(downloaded, key = { "done-${it.albumId}" }) { album ->
                DownloadedRow(
                    api, album, isEditing, isSelected = album.albumId in selection,
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
            if (isEditing) {
                item {
                    Button(
                        onClick = { confirmRemove = true },
                        enabled = selection.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = TetsubiColors.red, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    ) { Text(DownloadsText.removeLabel(selection.size)) }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
            item { Storage(downloaded, playlists) }
            item { Spacer(Modifier.height(32.dp)) }
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

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        DownloadCircleIcon(48.dp, secondary)
        Text(DownloadsText.EMPTY_TITLE, color = secondary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text(DownloadsText.EMPTY_MESSAGE, color = tertiary, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SectionHeader(title: String, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 32.dp, end = 20.dp, top = 20.dp, bottom = 4.dp).height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = secondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
        trailing()
    }
}

/** Rows in one rounded box over the background picture, as an iOS grouped list. */
private fun <T> LazyListScope.groupItems(list: List<T>, key: (T) -> Any, row: @Composable (T) -> Unit) {
    itemsIndexed(list, key = { _, item -> key(item) }) { i, item ->
        val r = 12.dp
        val shape = RoundedCornerShape(
            topStart = if (i == 0) r else 0.dp, topEnd = if (i == 0) r else 0.dp,
            bottomStart = if (i == list.lastIndex) r else 0.dp, bottomEnd = if (i == list.lastIndex) r else 0.dp,
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(shape).background(TetsubiColors.groupBackground)) {
            row(item)
            if (i < list.lastIndex) Box(Modifier.fillMaxWidth().padding(start = 84.dp).height(0.5.dp).background(Color.White.copy(alpha = 0.12f)))
        }
    }
}

@Composable
private fun DownloadingRow(api: JellyfinApi, manager: DownloadManager, state: DownloadAlbumState) {
    val paused = state.status == DownloadStatus.PAUSED
    val failed = state.status == DownloadStatus.FAILED
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val playlistId = state.playlistId
            if (playlistId != null) {
                val saved = manager.playlists.saved(playlistId)
                // Its own picture, else its first song's (from the server until it's saved).
                val artId = saved?.manifest?.takeIf { !it.hasArt }?.tracks?.firstOrNull()?.art ?: playlistId
                AlbumArt(api, artId, size = 56.dp, cornerRadius = 6.dp, localPath = saved?.coverPath)
            } else {
                val cover = manager.paths.coverPath(state.albumId).takeIf(LocalFiles::exists)
                AlbumArt(api, state.albumId, size = 56.dp, cornerRadius = 6.dp, localPath = cover)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(state.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (playlistId != null) DownloadsText.PLAYLIST else state.artist, color = secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(DownloadsText.formatLine(state), color = tertiary, fontSize = 11.sp)
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
        Text(DownloadsText.progressLine(state), color = secondary, fontSize = 12.sp)
        state.error?.takeIf { it.isNotEmpty() }?.let {
            Text(it, color = TetsubiColors.red, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadedRow(
    api: JellyfinApi,
    album: DownloadedAlbum,
    isEditing: Boolean,
    isSelected: Boolean,
    canPlay: Boolean,
    onPlay: () -> Unit,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                role = Role.Button,
                onClickLabel = if (isEditing) null else "Play album",
                onLongClick = if (isEditing) null else onLongPress,
                onClick = { if (isEditing) onToggle() else if (canPlay) onPlay() },
            )
            .semantics { if (isEditing) selected = isSelected }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (isEditing) {
            if (isSelected) CheckCircleIcon(24.dp, TetsubiColors.blue) else CircleIcon(24.dp, secondary)
        }
        AlbumArt(api, album.albumId, size = 56.dp, cornerRadius = 6.dp, localPath = album.coverPath.takeIf(LocalFiles::exists))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(album.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (album.artist.isNotEmpty()) Text(album.artist, color = secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(DownloadsText.detailLine(album), color = secondary, fontSize = 12.sp)
        }
        if (!isEditing && canPlay) {
            IconButton("Play locally", onClick = onPlay) { PlayIcon(24.dp, Color.White) }
        }
    }
}

/** A downloaded playlist: its picture, name and "12 songs · 47 min · 512 MB"; tap to open its page. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistDownloadedRow(
    api: JellyfinApi,
    playlist: DownloadedPlaylist,
    isEditing: Boolean,
    isSelected: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                role = Role.Button,
                onClickLabel = if (isEditing) null else "Open playlist",
                onLongClick = if (isEditing) null else onLongPress,
                onClick = { if (isEditing) onToggle() else onOpen() },
            )
            .semantics { if (isEditing) selected = isSelected }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (isEditing) {
            if (isSelected) CheckCircleIcon(24.dp, TetsubiColors.blue) else CircleIcon(24.dp, secondary)
        }
        val artId = playlist.manifest.takeIf { !it.hasArt }?.tracks?.firstOrNull()?.art ?: playlist.playlistId
        AlbumArt(api, artId, size = 56.dp, cornerRadius = 6.dp, localPath = playlist.coverPath)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(playlist.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(DownloadsText.detailLine(playlist), color = secondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun Storage(downloaded: List<DownloadedAlbum>, playlists: List<DownloadedPlaylist>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp)).background(TetsubiColors.groupBackground)) {
        StorageRow("Storage used", DownloadsText.storageUsed(downloaded, playlists))
        DownloadsText.totalPlayingTime(downloaded, playlists)?.let { total ->
            Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(0.5.dp).background(Color.White.copy(alpha = 0.12f)))
            StorageRow("Total playing time", total)
        }
    }
}

@Composable
private fun StorageRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = secondary, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = Color.White, fontSize = 15.sp)
    }
}

@Composable
private fun IconButton(label: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content() }
}
