package gg.dekiru.musicplayer.playback

/*
 * The pure parts of the queue's screens (as iOS): when Play asks before
 * replacing the queue, Now Playing's end times, and the queue editor's rows
 * and move maths. The screens only draw them.
 */

/**
 * Play on an album or playlist asks first (Replace queue, Play Next, Add to
 * Queue, Cancel): what plays after the playing entry (with Repeat All, the
 * earlier ones too) holds something of another batch, i.e. more than the
 * playing album or playlist is queued.
 */
val PlayQueue.asksBeforeReplacing: Boolean
    get() {
        val batch = currentEntry?.batchId ?: return false
        return upcomingEntries.any { it.batchId != batch }
    }

/**
 * Something of another batch plays after the playing one before the queue
 * ends: Now Playing shows the queue's end as well. Not while shuffled
 * (groups don't play through): then just "Ends:" with the queue's end.
 */
val PlayQueue.showsQueueEnd: Boolean
    get() {
        if (shuffle) return false
        val batch = currentEntry?.batchId ?: return false
        return entriesInPlayOrder.drop(currentPlayIndex + 1).any { it.batchId != batch }
    }

/** Now Playing's end times. */
object EndTimes {
    /** 9, 5 → "09:05". */
    fun clock(hour: Int, minute: Int): String = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

    /** The playing group's end: "Album ends", "Playlist ends", "Song ends" (one queued song) or "Songs end". */
    fun groupLabel(kind: QueueGroupKind, count: Int): String = when (kind) {
        QueueGroupKind.ALBUM -> "Album ends"
        QueueGroupKind.PLAYLIST -> "Playlist ends"
        QueueGroupKind.TRACKS -> if (count > 1) "Songs end" else "Song ends"
    }

    /**
     * "Ends: 13:02" when only the playing album (or playlist) is queued
     * (`queueEnd` null); else "Album ends 13:02 · Queue ends 15:40", or, when
     * that doesn't fit on the line (`short`), just "Queue ends 15:40".
     */
    fun text(groupEnd: String, queueEnd: String?, short: Boolean = false, groupLabel: String = "Album ends"): String = when {
        queueEnd == null -> "Ends: $groupEnd"
        short -> "Queue ends $queueEnd"
        else -> "$groupLabel $groupEnd · Queue ends $queueEnd"
    }
}

/** A row of the queue editor. */
sealed interface QueueRow {
    val key: String
}

/** A group's header: its picture, title, artist and time (its tracks'). It heads [QueueEditor.runs]`[runIndex]`, which moves and goes as a whole. */
data class QueueHeaderRow(
    val group: QueueGroup,
    val entryIds: List<String>,
    val seconds: Double,
    val runIndex: Int,
) : QueueRow {
    override val key: String get() = "group-${entryIds.first()}"
}

/**
 * A track: title, its artist when it isn't the group's ([showArtist]),
 * "artist · album" while shuffled ([showAlbum]). [isCurrent]: the playing
 * one, pinned at the top ("Now playing · <album>").
 */
data class QueueTrackRow(
    val entry: QueueEntry,
    val isCurrent: Boolean,
    val showArtist: Boolean,
    val showAlbum: Boolean,
) : QueueRow {
    override val key: String get() = "entry-${entry.id}"
}

/**
 * The queue as the editor shows it (as iOS): the playing entry pinned at
 * the top, then only what follows it in play order ([upcoming]: no played
 * entries, no Repeat All wrap). In order: under group headers (runs of one
 * batch, the rest of the playing album included); shuffled: one flat list,
 * each track with its artist and album.
 *
 * Moves are worked out as a *slot*: the place in [upcoming], less what's
 * moving, where the moved entries go; [toIndex] turns it into
 * `PlayerController.moveQueue`'s index.
 */
class QueueEditor(
    val current: QueueEntry?,
    val upcoming: List<QueueEntry>,
    val shuffled: Boolean,
) {
    /** [upcoming] in runs of one batch: the groups that move as a whole. */
    val runs: List<List<QueueEntry>> = buildList {
        for (e in upcoming) {
            val last = lastOrNull()
            if (last != null && last.first().batchId == e.batchId) set(lastIndex, last + e) else add(listOf(e))
        }
    }

    val rows: List<QueueRow> = buildList {
        val cur = current ?: return@buildList
        add(QueueTrackRow(cur, isCurrent = true, showArtist = false, showAlbum = false))
        if (shuffled) {
            for (e in upcoming) add(QueueTrackRow(e, isCurrent = false, showArtist = false, showAlbum = true))
            return@buildList
        }
        runs.forEachIndexed { i, run ->
            add(QueueHeaderRow(run.first().group, run.map { it.id }, run.sumOf { it.track.durationSeconds }, i))
            for (e in run) {
                add(QueueTrackRow(e, isCurrent = false, showArtist = e.track.artist.isNotEmpty() && e.track.artist != e.group.artist, showAlbum = false))
            }
        }
    }

    /** Whether a group can be dragged or moved: not while shuffled (no groups). */
    fun runCanMove(runIndex: Int): Boolean = !shuffled && runIndex in runs.indices

    /** The slot for "Move up" on an upcoming entry; null at the top. */
    fun trackUpSlot(entryId: String): Int? = upcoming.indexOfFirst { it.id == entryId }.takeIf { it > 0 }?.let { it - 1 }

    /** The slot for "Move down" on an upcoming entry; null at the bottom. */
    fun trackDownSlot(entryId: String): Int? = upcoming.indexOfFirst { it.id == entryId }.takeIf { it >= 0 && it < upcoming.lastIndex }?.let { it + 1 }

    /** The slot for "Move up" on a group: before the group above it; null at the top. */
    fun groupUpSlot(runIndex: Int): Int? {
        if (!runCanMove(runIndex) || runIndex == 0) return null
        return runs.take(runIndex - 1).sumOf { it.size }
    }

    /** The slot for "Move down" on a group: after the group below it; null at the bottom. */
    fun groupDownSlot(runIndex: Int): Int? {
        if (!runCanMove(runIndex) || runIndex >= runs.lastIndex) return null
        return runs.take(runIndex).sumOf { it.size } + runs[runIndex + 1].size
    }

    /** A dragged group dropped with `unitSlot` of the other groups above it (the dragged one left out): its slot. */
    fun groupSlot(runIndex: Int, unitSlot: Int): Int {
        val others = runs.filterIndexed { i, _ -> i != runIndex }
        return others.take(unitSlot.coerceIn(0, others.size)).sumOf { it.size }
    }

    companion object {
        fun from(queue: PlayQueue): QueueEditor {
            val index = queue.currentPlayIndex
            val upcoming = if (index < 0) emptyList() else queue.entriesInPlayOrder.drop(index + 1)
            return QueueEditor(queue.currentEntry, upcoming, queue.shuffle)
        }

        /** A slot as `moveQueue`'s `toIndex` (the place in the whole queue, less what's moving): after the playing entry. */
        fun toIndex(currentPlayIndex: Int, slot: Int): Int = currentPlayIndex + 1 + slot

        /** Where a dragged row lands: how many of the other rows (or groups) have their middle above its middle. */
        fun dropSlot(otherCentres: List<Float>, draggedCentre: Float): Int = otherCentres.count { it < draggedCentre }

        /**
         * Where a dragged group lands, snapping between groups: how many of
         * the other groups go above it. Going down, it goes after a group
         * once its middle passes that group's top; going up, before a group
         * once its middle passes that group's bottom.
         */
        fun groupDropSlot(otherTops: List<Float>, otherBottoms: List<Float>, draggedCentre: Float, movingDown: Boolean): Int =
            if (movingDown) otherTops.count { it < draggedCentre } else otherBottoms.count { it < draggedCentre }

        /** Maniac Mode: the queue is read-only (no dragging, removing, clearing or tapping to play). */
        fun isReadOnly(maniacMode: Boolean): Boolean = !PlayQueue.canEdit(QueueEdit.MOVE, maniacMode)

        const val MANIAC_NOTE = "Maniac Mode: the queue plays in order."
        const val EMPTY_NOTE = "Nothing queued. Use Play Next or Add to Queue on any album."
        const val CLEAR_TITLE = "Clear the queue?"
        const val CLEAR_MESSAGE = "The current track keeps playing."
        const val NOW_PLAYING = "Now playing"
        const val REPEATING_TRACK = "Repeating this track"
        const val THEN_REPEATS = "Then repeats from the start"
    }
}
