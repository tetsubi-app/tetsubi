package gg.dekiru.musicplayer.ui.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
import gg.dekiru.musicplayer.playback.PlayerController
import gg.dekiru.musicplayer.ui.Radii
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.album.AlbumDownloads
import gg.dekiru.musicplayer.ui.album.DownloadProgress
import gg.dekiru.musicplayer.ui.album.formatDuration
import gg.dekiru.musicplayer.ui.components.AlbumArt
import gg.dekiru.musicplayer.ui.components.BannerKind
import gg.dekiru.musicplayer.ui.components.CheckCircleIcon
import gg.dekiru.musicplayer.ui.components.DetailMetrics
import gg.dekiru.musicplayer.ui.components.DetailPage
import gg.dekiru.musicplayer.ui.components.DetailTitle
import gg.dekiru.musicplayer.ui.components.DownloadCircleIcon
import gg.dekiru.musicplayer.ui.components.GripIcon
import gg.dekiru.musicplayer.ui.components.ListMessage
import gg.dekiru.musicplayer.ui.components.ListRow
import gg.dekiru.musicplayer.ui.components.LoadingSpinner
import gg.dekiru.musicplayer.ui.components.LocalPlayPrompt
import gg.dekiru.musicplayer.ui.components.MenuAction
import gg.dekiru.musicplayer.ui.components.MinusCircleIcon
import gg.dekiru.musicplayer.ui.components.MoreMenuButton
import gg.dekiru.musicplayer.ui.components.PageHeader
import gg.dekiru.musicplayer.ui.components.PillButton
import gg.dekiru.musicplayer.ui.components.PlayChoice
import gg.dekiru.musicplayer.ui.components.PlayPrompt
import gg.dekiru.musicplayer.ui.components.PrimaryButton
import gg.dekiru.musicplayer.ui.components.ProgressRing
import gg.dekiru.musicplayer.ui.components.WithContextMenu
import gg.dekiru.musicplayer.ui.components.queueActions
import gg.dekiru.musicplayer.ui.downloads.DownloadsText
import gg.dekiru.musicplayer.ui.downloads.collectActive
import gg.dekiru.musicplayer.ui.downloads.collectPlaylists
import gg.dekiru.musicplayer.ui.home.OFFLINE_MESSAGE
import gg.dekiru.musicplayer.ui.home.StatusBanner
import gg.dekiru.musicplayer.ui.layout.LocalWindowClass
import gg.dekiru.musicplayer.ui.layout.screenInsets
import gg.dekiru.musicplayer.ui.library.ShuffleIcon
import gg.dekiru.musicplayer.ui.tetsubiType

/**
 * A playlist (laid out as the album page, [DetailPage]): art, name, song count and playing time;
 * Play, Shuffle and Download; the songs in playlist order, each with its own album's art and
 * "artist · album". Tap a song to play the playlist from there; its menu has Add to Playlist… and
 * Go to Album. The same song may be listed twice, so rows go by position.
 *
 * When the server lets the user change the playlist there's Edit: the rows become a list to drag
 * (by the handle) and remove from, with Rename and Delete Playlist. What's playing isn't touched.
 *
 * Download keeps the playlist on the device (Download → Downloading, with the progress under the
 * buttons → Downloaded; tapping Downloaded offers to remove it). Offline, a downloaded playlist's
 * page is filled from its saved list with its saved pictures, and there's no Edit or Add to Playlist….
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
    val editing = model.isEditing
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val drag = remember { RowDrag() }
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val prompt = LocalPlayPrompt.current
    // Play Next and Add to Queue for the whole playlist (its loaded songs).
    val queue = player?.takeIf { it.isSupported && model.entries.isNotEmpty() }?.let { p ->
        queueActions({ p.playNext(model.playlist, model.entries) }, { p.addToQueue(model.playlist, model.entries) })
    }.orEmpty()

    Column(Modifier.fillMaxSize().screenInsets()) {
        PageHeader(null, onBack = onBack) {
            val editTitle = when {
                editing -> "Done"
                model.canEdit && model.loaded && isOnline -> "Edit"
                else -> null
            }
            if (editTitle != null) {
                Text(
                    editTitle, style = tetsubiType.rowTitle, color = TetsubiColors.blue,
                    modifier = Modifier.clip(RoundedCornerShape(Radii.FULL_PERCENT)).clickable(role = Role.Button) { if (editing) model.doneEditing() else model.startEditing() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            MoreMenuButton("More", if (editing) emptyList() else queue)
        }
        DetailPage(
            modifier = Modifier.weight(1f),
            state = listState,
            listModifier = if (editing && !model.isDeleting) Modifier.dragRows(listState, drag, { model.entries.size }, model::move) else Modifier,
            info = { m ->
                if (editing) {
                    if (m.twoPane) {
                        AlbumArt(api, model.artItemId, size = m.artSize, cornerRadius = Radii.card, maxWidth = 800, localPath = download?.coverPath)
                        DetailTitle(m, model.playlist.displayName, meta = model.summary)
                    }
                    RenameRow(model.playlist.displayName, enabled = !model.isDeleting) { renaming = true }
                } else {
                    AlbumArt(api, model.artItemId, size = m.artSize, cornerRadius = Radii.card, maxWidth = 800, localPath = download?.coverPath)
                    DetailTitle(m, model.playlist.displayName, meta = model.summary)
                    Actions(
                        m, model, player, manager, download, downloadState, prompt,
                        canDownload = isOnline && model.loaded && model.entries.isNotEmpty(),
                        onRemove = { confirmingRemove = true },
                    )
                }
            },
        ) {
            if (editing) {
                model.saveError?.let { error -> item(key = "save-error") { StatusBanner(error) } }
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
                item(key = "delete") { DeleteRow(enabled = !model.isDeleting) { confirmingDelete = true } }
            } else {
                model.playNotice?.let { notice -> item(key = "play-notice") { StatusBanner(notice) } }
                val loadError = model.errorMessage
                val saveError = model.saveError
                val albumError = model.albumError
                when {
                    !isOnline && !model.loaded -> item(key = "offline") { StatusBanner(OFFLINE_MESSAGE, kind = BannerKind.Offline) }
                    loadError != null -> item(key = "load-error") { StatusBanner(loadError, "Retry", model::load) }
                    saveError != null -> item(key = "save-error") { StatusBanner(saveError) }
                    albumError != null -> item(key = "album-error") { StatusBanner(albumError) }
                }
                when {
                    model.isLoading -> item(key = "loading") { LoadingSpinner() }
                    model.loaded && model.entries.isEmpty() -> item(key = "empty") { ListMessage("This playlist is empty.") }
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
private val handleWidth = 96.dp

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

/** A grouped-row button: rounded 16 surface, [title] in [color]. */
@Composable
private fun GroupButton(title: String, color: androidx.compose.ui.graphics.Color, enabled: Boolean, onClick: () -> Unit, value: String? = null) {
    val shape = RoundedCornerShape(Radii.panel)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(TetsubiColors.surface, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = tetsubiType.rowTitle, color = color, maxLines = 1, modifier = Modifier.weight(1f, fill = value == null))
        if (value != null) Text(value, style = tetsubiType.rowTitle, color = TetsubiColors.blue, maxLines = 1)
    }
}

/** The playlist's name with "Rename…". */
@Composable
private fun RenameRow(name: String, enabled: Boolean, onClick: () -> Unit) =
    GroupButton(name, TetsubiColors.textPrimary, enabled, onClick, value = "Rename…")

@Composable
private fun DeleteRow(enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = LocalWindowClass.current.pageMargin, vertical = 16.dp)) {
        GroupButton("Delete Playlist", TetsubiColors.red, enabled, onClick)
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
    val title = entry.track.displayName
    Row(
        Modifier
            .fillMaxWidth()
            .zIndex(if (dragOffset != null) 1f else 0f)
            .graphicsLayer { translationY = dragOffset ?: 0f }
            .background(if (dragOffset != null) TetsubiColors.background else androidx.compose.ui.graphics.Color.Transparent)
            .heightIn(min = 56.dp)
            .padding(horizontal = LocalWindowClass.current.pageMargin, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(32.dp).clip(RoundedCornerShape(Radii.FULL_PERCENT))
                .clickable(enabled = enabled, role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = "Remove $title" },
            contentAlignment = Alignment.Center,
        ) { MinusCircleIcon(24.dp) }
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(Radii.thumb)).background(TetsubiColors.art)) {
            AlbumArt(api, entry.artItemId, size = 40.dp, cornerRadius = 0.dp, maxWidth = 100)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = tetsubiType.rowTitle, color = TetsubiColors.textPrimary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            val detail = entryDetail(entry)
            if (detail.isNotEmpty()) {
                Text(detail, style = tetsubiType.caption, color = TetsubiColors.textSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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
        ) { GripIcon(24.dp, TetsubiColors.textSecondary) }
    }
}

/**
 * Play (or "Now playing" while this playlist plays), Shuffle (not in Maniac Mode) and Download; the
 * buttons wrap on a narrow screen. A download under way shows its progress below.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(
    m: DetailMetrics,
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, if (m.twoPane) Alignment.Start else Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val nowPlaying = player?.isPlaying(model.playlist) == true
            PrimaryButton(
                if (nowPlaying) "Now playing" else "Play",
                onClick = {
                    when {
                        player == null -> {}
                        nowPlaying -> player.isNowPlayingOpen = true
                        // More than the playing album queued: Replace queue, Play Next or Add to Queue.
                        else -> prompt.play(player, playlistChoice(model, player))
                    }
                },
                enabled = canPlay,
                color = if (nowPlaying) TetsubiColors.pink else TetsubiColors.blue,
            )
            if (player?.maniacMode != true) {
                PillButton(
                    "Shuffle", onClick = { player?.let { prompt.play(it, playlistChoice(model, it, shuffled = true)) } }, enabled = canPlay,
                    icon = { ShuffleIcon(18.dp, if (canPlay) TetsubiColors.textPrimary else TetsubiColors.textTertiary) },
                )
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
        state != null -> PillButton(
            "Downloading", onClick = { AlbumDownloads.toggle(manager, state) },
            description = "Downloading. ${AlbumDownloads.actionLabel(state)}",
            icon = { ProgressRing(state.progress, 18.dp) },
        )
        download?.isComplete == true -> PillButton(
            "Downloaded", onClick = onRemove, color = TetsubiColors.green, description = "Downloaded. Remove download",
            icon = { CheckCircleIcon(18.dp, TetsubiColors.green) },
        )
        else -> PillButton(
            "Download", onClick = { manager.playlists.download(model.playlist, model.entries) }, enabled = canDownload,
            icon = { DownloadCircleIcon(18.dp, if (canDownload) TetsubiColors.textPrimary else TetsubiColors.textTertiary) },
        )
    }
}

/** One song: its album's art, title, "artist · album", length, and the ⋯ menu. The playing row is pink-tinted. */
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
    // ⋯, a long press and a right-click: Play Next, Add to Queue, Add to Playlist…, Go to Album.
    val menu = queue + listOfNotNull(
        onAddToPlaylist?.let { MenuAction("Add to Playlist…", action = it) },
        onGoToAlbum?.let { MenuAction("Go to Album", action = it) },
    )
    WithContextMenu(menu) { openMenu ->
        ListRow(
            title = entry.track.displayName,
            subtitle = entryDetail(entry).ifEmpty { null },
            art = { AlbumArt(api, entry.artItemId, size = 40.dp, cornerRadius = 0.dp, maxWidth = 100, localPath = localArt) },
            trailingMeta = formatDuration(entry.track.durationSeconds),
            // Keeps its room when empty, so the lengths line up.
            trailing = { if (menu.isEmpty()) Box(Modifier.size(40.dp)) else MoreMenuButton("More for ${entry.track.displayName}", menu) },
            current = isPlaying,
            onClick = onClick,
            onMenu = openMenu,
        )
    }
}

/** "artist · album", or whichever is known. */
internal fun entryDetail(entry: PlaylistEntry): String =
    listOfNotNull(entry.albumArtist?.takeIf { it.isNotEmpty() }, entry.albumName?.takeIf { it.isNotEmpty() }).joinToString(" · ")
