package gg.dekiru.musicplayer.ui.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.PlaylistEntry
import gg.dekiru.musicplayer.downloads.DownloadAlbumState
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.downloads.DownloadPaths
import gg.dekiru.musicplayer.downloads.DownloadedPlaylist
import gg.dekiru.musicplayer.downloads.LocalFiles
import gg.dekiru.musicplayer.downloads.PlaylistDownloads
import gg.dekiru.musicplayer.ui.album.AlbumDownloads
import gg.dekiru.musicplayer.ui.album.DownloadProgress
import gg.dekiru.musicplayer.ui.components.CheckCircleIcon
import gg.dekiru.musicplayer.ui.components.DownloadCircleIcon
import gg.dekiru.musicplayer.ui.components.ProgressRing
import gg.dekiru.musicplayer.ui.downloads.DownloadsText
import gg.dekiru.musicplayer.ui.downloads.collectActive
import gg.dekiru.musicplayer.ui.downloads.collectPlaylists
import gg.dekiru.musicplayer.playback.PlayerController
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.album.formatDuration
import gg.dekiru.musicplayer.ui.components.AlbumArt
import gg.dekiru.musicplayer.ui.components.LocalPlayPrompt
import gg.dekiru.musicplayer.ui.components.MenuAction
import gg.dekiru.musicplayer.ui.components.MoreMenuButton
import gg.dekiru.musicplayer.ui.components.PlayChoice
import gg.dekiru.musicplayer.ui.components.PlayPrompt
import gg.dekiru.musicplayer.ui.components.WithContextMenu
import gg.dekiru.musicplayer.ui.components.menuClickable
import gg.dekiru.musicplayer.ui.components.queueActions
import gg.dekiru.musicplayer.ui.home.OFFLINE_MESSAGE
import gg.dekiru.musicplayer.ui.home.StatusBanner
import gg.dekiru.musicplayer.ui.library.ShuffleIcon

/** Content width on wide screens, as the album page. */
private val readableWidth = 720.dp

/**
 * A playlist (laid out as the album page): art, name, song count and
 * playing time; Play and Shuffle; the songs in playlist order, each with
 * its own album's art and "artist · album". Tap a song to play the
 * playlist from there; its menu has Add to Playlist… and Go to Album. The
 * same song may be listed twice, so rows go by position.
 *
 * When the server lets the user change the playlist there's Edit: the
 * rows become a list to drag (by the handle) and remove from, with Rename
 * above and Delete Playlist below. What's playing isn't touched.
 *
 * Download keeps the playlist on the device (Download → Downloading, with
 * the progress under the buttons → Downloaded; tapping Downloaded offers
 * to remove it). Offline, a downloaded playlist's page is filled from its
 * saved list with its saved pictures, and there's no Edit or Add to
 * Playlist….
 */
@Composable
fun PlaylistScreen(
    api: JellyfinApi,
    model: PlaylistModel,
    player: PlayerController?,
    onOpenAlbum: (JellyfinAlbum) -> Unit,
    onBack: () -> Unit,
    isOnline: Boolean = true,
    onAddToPlaylist: ((List<String>) -> Unit)? = null,
    onDeleted: () -> Unit = onBack,
    manager: DownloadManager? = null,
) {
    // Each time the page shows, and again when the connection comes back.
    LaunchedEffect(model, isOnline) {
        if (isOnline) {
            model.clearPlayNotice()
            model.load()
            model.checkCanEdit()
        } else {
            model.loadOffline()
        }
    }
    val active by manager.collectActive()
    val allDownloads by manager.collectPlaylists()
    val download = allDownloads.firstOrNull { it.playlistId == model.playlist.id }
    val downloadState = active[PlaylistDownloads.key(model.playlist.id)]
    var confirmingRemove by remember { mutableStateOf(false) }
    // Scrolling content stops below the status bar; the bottom inset is padding inside the list.
    val insets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues()
    val editing = model.isEditing
    val listState = rememberLazyListState()
    val drag = remember { RowDrag() }
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val prompt = LocalPlayPrompt.current
    // Play Next and Add to Queue for the whole playlist (its loaded songs).
    val queue = player?.takeIf { it.isSupported && model.entries.isNotEmpty() }?.let { p ->
        queueActions({ p.playNext(model.playlist, model.entries) }, { p.addToQueue(model.playlist, model.entries) })
    }.orEmpty()

    BoxWithConstraints(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        contentAlignment = Alignment.TopCenter,
    ) {
        val wide = maxWidth > 600.dp
        LazyColumn(
            state = listState,
            contentPadding = insets,
            modifier = Modifier.widthIn(max = readableWidth).fillMaxSize()
                .then(if (editing && !model.isDeleting) Modifier.dragRows(listState, drag, { model.entries.size }, model::move) else Modifier),
        ) {
            item {
                TopBar(
                    model.playlist.displayName, onBack,
                    editTitle = when {
                        editing -> "Done"
                        model.canEdit && model.loaded && isOnline -> "Edit"
                        else -> null
                    },
                    onEdit = { if (editing) model.doneEditing() else model.startEditing() },
                    menu = if (editing) emptyList() else queue,
                )
            }
            if (editing) {
                model.saveError?.let { error -> item { StatusBanner(error) } }
                item { RenameRow(model.playlist.displayName, enabled = !model.isDeleting) { renaming = true } }
                itemsIndexed(model.entries, key = { position, _ -> "entry-$position" }) { position, entry ->
                    EditRow(
                        api, entry,
                        enabled = !model.isDeleting,
                        dragOffset = if (drag.index == position) drag.offset else null,
                        onRemove = { model.remove(position) },
                        onMoveUp = if (position > 0) ({ model.move(position, position - 1) }) else null,
                        onMoveDown = if (position < model.entries.lastIndex) ({ model.move(position, position + 1) }) else null,
                    )
                }
                item { DeleteRow(enabled = !model.isDeleting) { confirmingDelete = true } }
            } else {
                item { Header(api, model, artSize = if (wide) 240.dp else 160.dp, localArt = download?.coverPath) }
                item {
                    Actions(
                        model, player, manager, download, downloadState, prompt,
                        canDownload = isOnline && model.loaded && model.entries.isNotEmpty(),
                        onRemove = { confirmingRemove = true },
                    )
                }
                model.playNotice?.let { notice -> item { StatusBanner(notice) } }
                val loadError = model.errorMessage
                val saveError = model.saveError
                val albumError = model.albumError
                when {
                    !isOnline && !model.loaded -> item { StatusBanner(OFFLINE_MESSAGE, icon = "\u2298") }
                    loadError != null -> item { StatusBanner(loadError, "Retry", model::load) }
                    saveError != null -> item { StatusBanner(saveError) }
                    albumError != null -> item { StatusBanner(albumError) }
                }
                item { Spacer(Modifier.height(12.dp)) }
                when {
                    model.isLoading -> item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.5.dp)
                        }
                    }
                    model.loaded && model.entries.isEmpty() -> item {
                        Text(
                            "This playlist is empty.", color = TetsubiColors.albumArtistText, fontSize = 13.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        )
                    }
                    else -> itemsIndexed(model.entries, key = { position, _ -> "entry-$position" }) { position, entry ->
                        val playingHere = player != null && player.isPlaying(model.playlist) &&
                            player.currentPlace == position && player.currentTrack?.id == entry.track.id
                        // Maniac Mode: playlists start at the first song, so rows aren't buttons.
                        val canTap = player?.isSupported == true && !player.maniacMode
                        // The song's own Play Next / Add to Queue (in Maniac Mode too), as part of the playlist.
                        val songQueue = player?.takeIf { it.isSupported }?.let { p ->
                            queueActions({ p.playNext(model.playlist, listOf(entry)) }, { p.addToQueue(model.playlist, listOf(entry)) })
                        }.orEmpty()
                        EntryRow(
                            api, entry,
                            localArt = download?.let { rememberSavedArt(it, entry.artItemId) },
                            isPlaying = playingHere,
                            onClick = if (canTap && player != null) ({ prompt.play(player, playlistChoice(model, player, song = entry, startingAt = position)) }) else null,
                            queue = songQueue,
                            onAddToPlaylist = onAddToPlaylist?.takeIf { isOnline }?.let { add -> { add(listOf(entry.track.id)) } },
                            onGoToAlbum = if (entry.albumId != null) ({ model.openAlbum(entry, onOpenAlbum) }) else null,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    if (renaming) {
        PlaylistNameDialog(
            title = "Rename Playlist", action = "Rename", initial = model.playlist.name, onDismiss = { renaming = false },
        ) { model.rename(it) }
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(PlaylistWording.deleteTitle(model.playlist.displayName)) },
            text = { Text(PlaylistWording.DELETE_MESSAGE) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    model.delete(onDeleted)
                }) { Text("Delete", color = TetsubiColors.red) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
        )
    }
    if (confirmingRemove) {
        AlertDialog(
            onDismissRequest = { confirmingRemove = false },
            title = { Text("Remove this download?") },
            text = { Text(DownloadsText.REMOVE_PLAYLIST_MESSAGE) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingRemove = false
                    manager?.playlists?.delete(model.playlist.id)
                }) { Text("Remove", color = TetsubiColors.red) }
            },
            dismissButton = { TextButton(onClick = { confirmingRemove = false }) { Text("Cancel") } },
        )
    }
}

/** A song's saved picture in the playlist's download, if it's on disk. */
@Composable
private fun rememberSavedArt(download: DownloadedPlaylist, itemId: String): String? =
    remember(download, itemId) { "${download.directory}/${DownloadPaths.COVERS}/${DownloadPaths.sanitize(itemId)}.jpg".takeIf(LocalFiles::exists) }

@Composable
private fun TopBar(title: String, onBack: () -> Unit, editTitle: String?, onEdit: () -> Unit, menu: List<MenuAction> = emptyList()) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) {
            Text("‹ Back", color = TetsubiColors.blue, fontSize = 16.sp)
        }
        Text(
            title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 16.dp),
        )
        if (editTitle != null) {
            TextButton(onClick = onEdit) {
                Text(editTitle, color = TetsubiColors.blue, fontSize = 16.sp, fontWeight = if (editTitle == "Done") FontWeight.SemiBold else FontWeight.Normal)
            }
        }
        MoreMenuButton("More", menu, Modifier.padding(end = 8.dp))
    }
}

/**
 * Play the playlist (from the tapped `song`, or shuffled), or, from the
 * prompt, queue it instead (a tapped song: just that song; Shuffle leaves
 * shuffle as it is). A refused play says why on the page.
 */
private fun playlistChoice(
    model: PlaylistModel, player: PlayerController, song: PlaylistEntry? = null, startingAt: Int? = null, shuffled: Boolean = false,
) = PlayChoice(
    title = song?.track?.displayName ?: model.playlist.displayName,
    replace = { model.play(player, startingAt = startingAt, shuffled = shuffled, openNowPlaying = song == null) },
    playNext = { player.playNext(model.playlist, song?.let(::listOf) ?: model.entries) },
    addToQueue = { player.addToQueue(model.playlist, song?.let(::listOf) ?: model.entries) },
)

// Edit mode

/** The row being dragged (its place in the list now) and how far it sits from that place, in pixels. */
private class RowDrag {
    var index by mutableStateOf<Int?>(null)
    var offset by mutableStateOf(0f)
}

/** The drag handle's width at the right of an edit row (with the row's own edge padding). */
private val handleWidth = 64.dp

/**
 * Dragging a row by its handle: the press is taken before the list can
 * scroll with it. The row follows the finger; passing half a row swaps it
 * with its neighbour (`move`). Near the top or bottom the list scrolls.
 */
private fun Modifier.dragRows(listState: LazyListState, drag: RowDrag, count: () -> Int, move: (Int, Int) -> Unit): Modifier =
    pointerInput(listState, drag) {
        val handle = handleWidth.toPx()
        val edge = 56.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.position.x < size.width - handle) return@awaitEachGesture
            val layout = listState.layoutInfo
            val item = layout.visibleItemsInfo.firstOrNull { info ->
                val top = info.offset + layout.beforeContentPadding
                (info.key as? String)?.startsWith("entry-") == true && down.position.y >= top && down.position.y < top + info.size
            } ?: return@awaitEachGesture
            val height = item.size.toFloat()
            if (height <= 0f) return@awaitEachGesture
            var at = (item.key as String).removePrefix("entry-").toIntOrNull() ?: return@awaitEachGesture
            down.consume()
            drag.index = at
            drag.offset = 0f
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        change.consume()
                        break
                    }
                    var offset = drag.offset + change.positionChange().y
                    change.consume()
                    // Near an edge: scroll, and keep the row under the finger.
                    val y = change.position.y
                    val wanted = when {
                        y < edge -> -(edge - y) / 4
                        y > size.height - edge -> (y - (size.height - edge)) / 4
                        else -> 0f
                    }
                    if (wanted != 0f) offset += listState.dispatchRawDelta(wanted)
                    while (offset > height / 2 && at < count() - 1) {
                        move(at, at + 1)
                        at += 1
                        offset -= height
                    }
                    while (offset < -height / 2 && at > 0) {
                        move(at, at - 1)
                        at -= 1
                        offset += height
                    }
                    drag.index = at
                    drag.offset = offset
                }
            } finally {
                drag.index = null
                drag.offset = 0f
            }
        }
    }

/** The playlist's name with "Rename…". */
@Composable
private fun RenameRow(name: String, enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(shape)
            .background(TetsubiColors.groupBackground, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(name, color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("Rename…", color = TetsubiColors.pink, fontSize = 15.sp)
    }
}

@Composable
private fun DeleteRow(enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .clip(shape)
            .background(TetsubiColors.groupBackground, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Delete Playlist", color = TetsubiColors.red, fontSize = 17.sp)
    }
}

/** One song in edit mode: ⊖ to remove it, its art and names, and the handle to drag it by. */
@Composable
private fun EditRow(
    api: JellyfinApi,
    entry: PlaylistEntry,
    enabled: Boolean,
    dragOffset: Float?,
    onRemove: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(6.dp)
    val title = entry.track.displayName
    Row(
        Modifier
            .fillMaxWidth()
            .zIndex(if (dragOffset != null) 1f else 0f)
            .graphicsLayer { translationY = dragOffset ?: 0f }
            .padding(horizontal = 16.dp, vertical = 1.dp)
            .clip(shape)
            .background(if (dragOffset != null) TetsubiColors.groupBackground else Color.Gray.copy(alpha = 0.05f), shape)
            .padding(top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(50))
                .clickable(enabled = enabled, role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = "Remove $title" },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(22.dp).background(TetsubiColors.red, RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                Box(Modifier.width(10.dp).height(2.dp).background(Color.White))
            }
        }
        AlbumArt(api, entry.artItemId, size = 40.dp, cornerRadius = 4.dp, maxWidth = 100)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = entryDetail(entry)
            if (detail.isNotEmpty()) {
                Text(detail, color = TetsubiColors.albumArtistText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(
            Modifier.size(48.dp, 40.dp).semantics {
                contentDescription = "Reorder $title"
                customActions = listOfNotNull(
                    onMoveUp?.takeIf { enabled }?.let { CustomAccessibilityAction("Move up") { it(); true } },
                    onMoveDown?.takeIf { enabled }?.let { CustomAccessibilityAction("Move down") { it(); true } },
                )
            },
            contentAlignment = Alignment.Center,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(3) { Box(Modifier.width(18.dp).height(2.dp).background(Color.White.copy(alpha = 0.5f))) }
            }
        }
    }
}

@Composable
private fun Header(api: JellyfinApi, model: PlaylistModel, artSize: Dp, localArt: String?) {
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
        AlbumArt(api, model.artItemId, size = artSize, cornerRadius = 12.dp, maxWidth = 600, localPath = localArt)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(model.playlist.displayName, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(model.summary, color = TetsubiColors.albumArtistText, fontSize = 15.sp)
        }
    }
}

/**
 * Play (or "Now playing" while this playlist plays), Shuffle (not in
 * Maniac Mode) and Download; the buttons wrap on a narrow screen. A
 * download under way shows its progress below.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(
    model: PlaylistModel,
    player: PlayerController?,
    manager: DownloadManager?,
    download: DownloadedPlaylist?,
    state: DownloadAlbumState?,
    prompt: PlayPrompt,
    canDownload: Boolean,
    onRemove: () -> Unit,
) {
    val canPlay = player?.isSupported == true && model.entries.isNotEmpty()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val nowPlaying = player?.isPlaying(model.playlist) == true
        Button(
            onClick = {
                when {
                    player == null -> {}
                    nowPlaying -> player.isNowPlayingOpen = true
                    // More than the playing album queued: Replace queue, Play Next or Add to Queue.
                    else -> prompt.play(player, playlistChoice(model, player))
                }
            },
            enabled = canPlay,
            colors = ButtonDefaults.buttonColors(containerColor = if (nowPlaying) TetsubiColors.pink else TetsubiColors.blue, contentColor = Color.White),
        ) { Text(if (nowPlaying) "Now playing" else "▶  Play") }
        if (player?.maniacMode != true) {
            OutlinedButton(
                onClick = {
                    player?.let { prompt.play(it, playlistChoice(model, it, shuffled = true)) }
                },
                enabled = canPlay,
            ) {
                ShuffleIcon(18.dp, if (canPlay) Color.White else Color.White.copy(alpha = 0.5f))
                Spacer(Modifier.width(8.dp))
                Text("Shuffle", color = if (canPlay) Color.White else Color.White.copy(alpha = 0.5f))
            }
        }
        if (manager != null) DownloadButton(model, manager, download, state, canDownload, onRemove)
    }
    if (manager != null && state != null) DownloadProgress(manager, state)
    }
}

/**
 * Download → Downloading (a progress ring; tap to pause, resume or retry)
 * → Downloaded ✓ (green; tap to remove the download). Labels as iOS.
 */
@Composable
private fun DownloadButton(
    model: PlaylistModel,
    manager: DownloadManager,
    download: DownloadedPlaylist?,
    state: DownloadAlbumState?,
    canDownload: Boolean,
    onRemove: () -> Unit,
) {
    when {
        state != null -> OutlinedButton(
            onClick = { AlbumDownloads.toggle(manager, state) },
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "Downloading. ${AlbumDownloads.actionLabel(state)}" },
        ) {
            ProgressRing(state.progress, 18.dp)
            Spacer(Modifier.width(8.dp))
            Text("Downloading", color = Color.White)
        }
        download?.isComplete == true -> OutlinedButton(
            onClick = onRemove,
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "Downloaded. Remove download" },
        ) {
            CheckCircleIcon(18.dp, TetsubiColors.green)
            Spacer(Modifier.width(8.dp))
            Text("Downloaded", color = TetsubiColors.green)
        }
        else -> OutlinedButton(onClick = { manager.playlists.download(model.playlist, model.entries) }, enabled = canDownload) {
            DownloadCircleIcon(18.dp, if (canDownload) Color.White else Color.White.copy(alpha = 0.5f))
            Spacer(Modifier.width(8.dp))
            Text("Download", color = if (canDownload) Color.White else Color.White.copy(alpha = 0.5f))
        }
    }
}

/** One song: its album's art, title, "artist · album", length, and the ⋯ menu. The playing row is solid pink. */
@Composable
private fun EntryRow(
    api: JellyfinApi,
    entry: PlaylistEntry,
    localArt: String?,
    isPlaying: Boolean,
    onClick: (() -> Unit)?,
    queue: List<MenuAction>,
    onAddToPlaylist: (() -> Unit)?,
    onGoToAlbum: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(6.dp)
    // ⋯, a long press and a right-click: Play Next, Add to Queue, Add to Playlist…, Go to Album.
    val menu = queue + listOfNotNull(
        onAddToPlaylist?.let { MenuAction("Add to Playlist…", action = it) },
        onGoToAlbum?.let { MenuAction("Go to Album", action = it) },
    )
    WithContextMenu(menu) { openMenu ->
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 1.dp)
            .clip(shape)
            .background(if (isPlaying) TetsubiColors.pink else Color.Gray.copy(alpha = 0.05f), shape)
            .then(
                when {
                    onClick != null -> Modifier.menuClickable(onClick, openMenu)
                    openMenu != null -> Modifier.menuClickable({}, openMenu)
                    else -> Modifier
                },
            )
            .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AlbumArt(api, entry.artItemId, size = 40.dp, cornerRadius = 4.dp, maxWidth = 100, localPath = localArt)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                entry.track.displayName, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (isPlaying) FontWeight.SemiBold else FontWeight.Normal,
            )
            val detail = entryDetail(entry)
            if (detail.isNotEmpty()) {
                Text(
                    detail, color = if (isPlaying) Color.White.copy(alpha = 0.85f) else TetsubiColors.albumArtistText,
                    fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            formatDuration(entry.track.durationSeconds), color = if (isPlaying) Color.White.copy(alpha = 0.85f) else TetsubiColors.albumArtistText,
            fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp),
        )
        // Keeps its room when empty, so the lengths line up.
        if (menu.isEmpty()) Spacer(Modifier.size(40.dp)) else MoreMenuButton("More for ${entry.track.displayName}", menu)
    }
    }
}

/** "artist · album", or whichever is known. */
internal fun entryDetail(entry: PlaylistEntry): String =
    listOfNotNull(entry.albumArtist?.takeIf { it.isNotEmpty() }, entry.albumName?.takeIf { it.isNotEmpty() }).joinToString(" · ")
