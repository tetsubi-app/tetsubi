package gg.dekiru.musicplayer.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.platformName
import gg.dekiru.musicplayer.playback.PlayerController
import gg.dekiru.musicplayer.playback.QueueEditor
import gg.dekiru.musicplayer.playback.QueueHeaderRow
import gg.dekiru.musicplayer.playback.QueueRow
import gg.dekiru.musicplayer.playback.QueueTrackRow
import gg.dekiru.musicplayer.playback.RepeatMode
import gg.dekiru.musicplayer.playback.formatAlbumDuration
import gg.dekiru.musicplayer.playback.formatTrackTime
import gg.dekiru.musicplayer.ui.TetsubiColors
import gg.dekiru.musicplayer.ui.Radii
import gg.dekiru.musicplayer.ui.components.ActionChip
import gg.dekiru.musicplayer.ui.components.AlbumArt
import gg.dekiru.musicplayer.ui.components.CloseIcon
import gg.dekiru.musicplayer.ui.components.GripIcon
import gg.dekiru.musicplayer.ui.tetsubiType

/** Android swipes rows away; the desktop has a remove button on each. */
private val swipesToRemove: Boolean get() = platformName == "Android"

private val dim = TetsubiColors.textSecondary

/**
 * Now Playing's queue (as iOS's queue editor): "Queue", the time left in it
 * and Clear queue; the playing track pinned at the top ("Now playing ·
 * <album>"); then only what follows it, under album / playlist headers
 * (shuffled: one list in play order, each track with "artist · album").
 * A group dragged by its header snaps between groups. The repeat notes
 * sit at the bottom.
 *
 * Tap a track to play it. Drag a track, or a group by its header, by the
 * handle (or after a long press) to move it; the playing track stays put.
 * Remove: swipe left (Android) or the × button (desktop); a header removes
 * its whole group. Screen readers get Move up, Move down and Remove.
 * Maniac Mode: read-only, with a note.
 */
@Composable
internal fun QueueSection(api: JellyfinApi, player: PlayerController) {
    val queue = player.playQueue
    val editor = remember(queue) { QueueEditor.from(queue) }
    val readOnly = QueueEditor.isReadOnly(player.maniacMode)
    val drag = remember { QueueDrag() }
    val bounds = remember { mutableMapOf<String, RowBounds>() }
    var confirmingClear by remember { mutableStateOf(false) }
    val currentIndex = queue.currentPlayIndex

    fun move(ids: List<String>, slot: Int?) {
        if (slot != null) player.moveQueue(ids, QueueEditor.toIndex(currentIndex, slot))
    }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Queue", style = tetsubiType.sectionTitle, color = TetsubiColors.textPrimary, modifier = Modifier.semantics { heading() })
            val left = formatAlbumDuration(player.queueRemaining)
            Text(
                left, style = tetsubiType.caption, color = dim, maxLines = 1,
                modifier = Modifier.padding(start = 12.dp).weight(1f).semantics { contentDescription = "$left left" },
            )
            if (!readOnly && queue.entries.size > 1) {
                ActionChip("Clear queue", onClick = { confirmingClear = true })
            }
        }
        if (readOnly) Note(QueueEditor.MANIAC_NOTE)

        // Drag bookkeeping: each row's place, the dragged rows' offset, and where they'd land.
        val dragged = drag.keys
        val trackKeys = editor.rows.filterIsInstance<QueueTrackRow>().filter { !it.isCurrent }.map { it.key }

        fun trackDrop(row: QueueTrackRow): Pair<Int, String?> {
            val others = trackKeys.filter { it != row.key }
            val me = bounds[row.key] ?: return 0 to null
            val centres = others.map { bounds[it]?.centre ?: Float.MAX_VALUE }
            val slot = QueueEditor.dropSlot(centres, me.centre + drag.offset)
            return slot to others.getOrNull(slot)
        }

        fun runKeys(i: Int): List<String> = listOf("group-${editor.runs[i].first().id}") + editor.runs[i].map { "entry-${it.id}" }

        // A group snaps between groups: dragged down into one it goes after it, up into one before it.
        fun groupDrop(runIndex: Int): Pair<Int, String?> {
            val mine = runKeys(runIndex).mapNotNull { bounds[it] }
            if (mine.isEmpty()) return 0 to null
            val centre = (mine.minOf { it.top } + mine.maxOf { it.bottom }) / 2 + drag.offset
            val others = editor.runs.indices.filter { it != runIndex }
            val spans = others.map { i -> runKeys(i).mapNotNull { bounds[it] } }
            val tops = spans.map { b -> if (b.isEmpty()) Float.MAX_VALUE else b.minOf { it.top } }
            val bottoms = spans.map { b -> if (b.isEmpty()) Float.MAX_VALUE else b.maxOf { it.bottom } }
            val unit = QueueEditor.groupDropSlot(tops, bottoms, centre, movingDown = drag.offset > 0)
            return editor.groupSlot(runIndex, unit) to others.getOrNull(unit)?.let { runKeys(it).first() }
        }

        for (row in editor.rows) {
            key(row.key) {
                val isDragged = row.key in dragged
                val lineAbove = drag.target == row.key && dragged.isNotEmpty()
                val place = Modifier
                    .onGloballyPositioned { bounds[row.key] = RowBounds(it.positionInParent().y, it.size.height.toFloat()) }
                    .zIndex(if (isDragged) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragged) drag.offset else 0f }
                    // The rows being dragged (a track, or an album's header and tracks) get a light
                    // wash, so they stand apart from the rows they pass over.
                    .then(if (isDragged) Modifier.background(TetsubiColors.dragWash) else Modifier)
                    .then(if (lineAbove) Modifier.dropLine() else Modifier)
                when (row) {
                    is QueueHeaderRow -> {
                        val run = row.runIndex
                        val canMove = !readOnly && editor.runCanMove(run)
                        val gestures = if (canMove) {
                            DragHandlers(
                                start = { drag.start(runKeys(run)) },
                                moved = { dy -> drag.offset += dy; drag.target = groupDrop(run).second ?: END },
                                end = {
                                    val slot = groupDrop(run).first
                                    drag.stop()
                                    move(row.entryIds, slot)
                                },
                                cancel = drag::stop,
                            )
                        } else {
                            null
                        }
                        Removable(enabled = canMove, modifier = place, onRemove = { player.removeFromQueue(row.entryIds) }) {
                            GroupHeader(
                                api, player, row,
                                drag = gestures,
                                onRemove = if (canMove && !swipesToRemove) ({ player.removeFromQueue(row.entryIds) }) else null,
                                actions = if (canMove) listOfNotNull(
                                    editor.groupUpSlot(run)?.let { s -> CustomAccessibilityAction("Move up") { move(row.entryIds, s); true } },
                                    editor.groupDownSlot(run)?.let { s -> CustomAccessibilityAction("Move down") { move(row.entryIds, s); true } },
                                    CustomAccessibilityAction("Remove") { player.removeFromQueue(row.entryIds); true },
                                ) else emptyList(),
                            )
                        }
                    }
                    is QueueTrackRow -> {
                        val id = row.entry.id
                        val canEdit = !readOnly && !row.isCurrent
                        val gestures = if (canEdit) {
                            DragHandlers(
                                start = { drag.start(listOf(row.key)) },
                                moved = { dy -> drag.offset += dy; drag.target = trackDrop(row).second ?: END },
                                end = {
                                    val slot = trackDrop(row).first
                                    drag.stop()
                                    move(listOf(id), slot)
                                },
                                cancel = drag::stop,
                            )
                        } else {
                            null
                        }
                        Removable(enabled = canEdit, modifier = place, onRemove = { player.removeFromQueue(listOf(id)) }) {
                            TrackRow(
                                player, row,
                                drag = gestures,
                                onClick = if (canEdit) ({ player.skipTo(id) }) else null,
                                onRemove = if (canEdit && !swipesToRemove) ({ player.removeFromQueue(listOf(id)) }) else null,
                                actions = if (canEdit) listOfNotNull(
                                    editor.trackUpSlot(id)?.let { s -> CustomAccessibilityAction("Move up") { move(listOf(id), s); true } },
                                    editor.trackDownSlot(id)?.let { s -> CustomAccessibilityAction("Move down") { move(listOf(id), s); true } },
                                    CustomAccessibilityAction("Remove") { player.removeFromQueue(listOf(id)); true },
                                ) else emptyList(),
                            )
                        }
                    }
                }
            }
        }
        // Dropped past the last row.
        if (dragged.isNotEmpty() && drag.target == END) Box(Modifier.fillMaxWidth().height(0.dp).dropLine())
        if (editor.upcoming.isEmpty()) {
            Text(QueueEditor.EMPTY_NOTE, style = tetsubiType.body, color = dim, modifier = Modifier.padding(vertical = 8.dp))
        }
        when {
            player.repeatMode == RepeatMode.ONE -> Note(QueueEditor.REPEATING_TRACK, top = true)
            player.repeatMode == RepeatMode.ALL && queue.entries.size > 1 -> Note(QueueEditor.THEN_REPEATS, top = true)
        }
    }

    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text(QueueEditor.CLEAR_TITLE) },
            text = { Text(QueueEditor.CLEAR_MESSAGE) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingClear = false
                    player.clearUpcoming()
                }) { Text("Clear", color = TetsubiColors.red) }
            },
            dismissButton = { TextButton(onClick = { confirmingClear = false }) { Text("Cancel") } },
        )
    }
}

private const val END = "end"

/** A row's place in the queue's column, in pixels. */
private class RowBounds(val top: Float, val height: Float) {
    val bottom: Float get() = top + height
    val centre: Float get() = top + height / 2
}

/** What's being dragged (row keys), how far, and the row the drop line sits above ([END]: below the last). */
private class QueueDrag {
    var keys by mutableStateOf<Set<String>>(emptySet())
    var offset by mutableStateOf(0f)
    var target by mutableStateOf<String?>(null)

    fun start(rows: List<String>) {
        keys = rows.toSet()
        offset = 0f
        target = null
    }

    fun stop() {
        keys = emptySet()
        offset = 0f
        target = null
    }
}

private class DragHandlers(val start: () -> Unit, val moved: (Float) -> Unit, val end: () -> Unit, val cancel: () -> Unit)

/** The drop indicator: a line along the top. */
private fun Modifier.dropLine(): Modifier = drawBehind {
    drawLine(TetsubiColors.pink, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 2.dp.toPx())
}

/** Dragging from the handle at once. */
private fun Modifier.dragNow(handlers: State<DragHandlers?>): Modifier = pointerInput(Unit) {
    detectDragGestures(
        onDragStart = { handlers.value?.start?.invoke() },
        onDragEnd = { handlers.value?.end?.invoke() },
        onDragCancel = { handlers.value?.cancel?.invoke() },
    ) { change, amount ->
        if (handlers.value != null) change.consume()
        handlers.value?.moved?.invoke(amount.y)
    }
}

/** Dragging anywhere on the row after a long press. */
private fun Modifier.dragAfterLongPress(handlers: State<DragHandlers?>): Modifier = pointerInput(Unit) {
    detectDragGesturesAfterLongPress(
        onDragStart = { handlers.value?.start?.invoke() },
        onDragEnd = { handlers.value?.end?.invoke() },
        onDragCancel = { handlers.value?.cancel?.invoke() },
    ) { change, amount ->
        if (handlers.value != null) change.consume()
        handlers.value?.moved?.invoke(amount.y)
    }
}

/** Android: swipe left to remove (when `enabled`); elsewhere just the row. */
@Composable
private fun Removable(enabled: Boolean, modifier: Modifier, onRemove: () -> Unit, content: @Composable () -> Unit) {
    if (!swipesToRemove || !enabled) {
        Box(modifier) { content() }
        return
    }
    val state = rememberSwipeToDismissBoxState()
    val remove by rememberUpdatedState(onRemove)
    LaunchedEffect(state.currentValue) {
        if (state.currentValue == SwipeToDismissBoxValue.EndToStart) remove()
    }
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            // Only while a swipe is under way: the rows are see-through, so a background drawn
            // all the time showed through every row and covered the times and drag handles.
            if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) Box(
                Modifier.fillMaxSize().background(TetsubiColors.red, RoundedCornerShape(6.dp)).padding(end = 16.dp),
                contentAlignment = Alignment.CenterEnd,
            ) { Text("Remove", style = tetsubiType.control, color = Color.White) }
        },
    ) { content() }
}

@Composable
private fun Note(text: String, top: Boolean = false) {
    Text(text, style = tetsubiType.caption, color = dim, modifier = Modifier.padding(top = if (top) 8.dp else 0.dp, bottom = 6.dp))
}

/** A group's header: small cover, title, artist, the group's time left; the handle and × when it can be moved and removed. */
@Composable
private fun GroupHeader(
    api: JellyfinApi,
    player: PlayerController,
    row: QueueHeaderRow,
    drag: DragHandlers?,
    onRemove: (() -> Unit)?,
    actions: List<CustomAccessibilityAction>,
) {
    val handlers = rememberUpdatedState(drag)
    val first = row.entryIds.firstOrNull()?.let { player.playQueue.entry(it) }
    val local = first?.let { player.track(it).localArtPath }
    val time = formatAlbumDuration(row.seconds)
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .then(if (drag != null) Modifier.dragAfterLongPress(handlers) else Modifier)
            .padding(top = 14.dp, bottom = 6.dp)
            .semantics(mergeDescendants = true) {
                heading()
                contentDescription = listOfNotNull(row.group.title, row.group.artist, "$time left").joinToString(", ")
                customActions = actions
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AlbumArt(api, row.group.artItemId ?: first?.track?.artItemId ?: row.group.id, size = 36.dp, cornerRadius = Radii.thumb, maxWidth = 100, localPath = local)
        Column(Modifier.weight(1f)) {
            Text(row.group.title, style = tetsubiType.cardTitle, color = TetsubiColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            row.group.artist?.let { Text(it, style = tetsubiType.caption, color = TetsubiColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Text(time, style = tetsubiType.caption, color = dim, maxLines = 1)
        EditButtons(drag, onRemove, "Remove ${row.group.title}", handlers)
    }
}

/** One track: title, artist (when not the group's), album (shuffled), length; the playing one tinted and fixed. */
@Composable
private fun TrackRow(
    player: PlayerController,
    row: QueueTrackRow,
    drag: DragHandlers?,
    onClick: (() -> Unit)?,
    onRemove: (() -> Unit)?,
    actions: List<CustomAccessibilityAction>,
) {
    val handlers = rememberUpdatedState(drag)
    val track = player.track(row.entry)
    val shape = RoundedCornerShape(Radii.thumb)
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(if (row.isCurrent) TetsubiColors.pinkTint else Color.Transparent, shape)
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClickLabel = "Plays this track", onClick = onClick) else Modifier)
                .then(if (drag != null) Modifier.dragAfterLongPress(handlers) else Modifier)
                .padding(start = if (row.isCurrent) 8.dp else 0.dp, end = if (row.isCurrent) 8.dp else 0.dp, top = 8.dp, bottom = 8.dp)
                .semantics {
                    customActions = actions
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    track.title, style = tetsubiType.rowTitle, color = TetsubiColors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (row.isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                )
                val detail = when {
                    row.isCurrent -> listOfNotNull(QueueEditor.NOW_PLAYING, track.album.ifEmpty { null }).joinToString(" · ")
                    row.showAlbum -> listOfNotNull(track.artist.ifEmpty { null }, track.album.ifEmpty { null }).joinToString(" · ")
                    row.showArtist -> track.artist
                    else -> ""
                }
                if (detail.isNotEmpty()) {
                    Text(detail, style = tetsubiType.caption, color = TetsubiColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (track.durationSeconds > 0) Text(formatTrackTime(track.durationSeconds), style = tetsubiType.control.copy(fontWeight = FontWeight.Normal), color = dim, modifier = Modifier.padding(start = 8.dp))
            EditButtons(drag, onRemove, "Remove ${track.title}", handlers)
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(TetsubiColors.hairline))
    }
}

/** × (desktop) and the drag handle, when the row can be edited. */
@Composable
private fun RowScope.EditButtons(drag: DragHandlers?, onRemove: (() -> Unit)?, removeLabel: String, handlers: State<DragHandlers?>) {
    if (onRemove != null) {
        Box(
            Modifier.padding(start = 4.dp).size(32.dp).clip(RoundedCornerShape(50))
                .clickable(role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = removeLabel },
            contentAlignment = Alignment.Center,
        ) { CloseIcon(18.dp, TetsubiColors.textSecondary) }
    }
    if (drag != null) {
        Box(
            Modifier.size(40.dp, 36.dp).dragNow(handlers),
            contentAlignment = Alignment.Center,
        ) { GripIcon(24.dp, TetsubiColors.textTertiary) }
    }
}
