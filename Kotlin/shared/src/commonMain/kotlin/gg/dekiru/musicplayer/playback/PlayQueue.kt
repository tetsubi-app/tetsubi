package gg.dekiru.musicplayer.playback

import gg.dekiru.musicplayer.data.api.randomUuid
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinPlaylist
import gg.dekiru.musicplayer.data.models.JellyfinTrack
import gg.dekiru.musicplayer.data.models.MediaSource
import gg.dekiru.musicplayer.data.models.PlaylistEntry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.random.Random

/*
 * The listening queue (as `PlayQueue` on iOS, same rules and the same saved
 * JSON): albums, playlists and single tracks queued one after another, each
 * entry remembering what it was queued from and which add it came in
 * (its batch), so the queue can be shown grouped by album. Pure logic, no
 * playing: the player feeds its engine from it.
 */

/** What an entry was queued from. */
@Serializable
enum class QueueGroupKind {
    @SerialName("album")
    ALBUM,

    @SerialName("playlist")
    PLAYLIST,

    /** Single tracks (a song's own Play Next / Add to Queue, search results). */
    @SerialName("tracks")
    TRACKS,
}

/** The album or playlist an entry was queued from, for the queue's group headers. */
@Serializable
data class QueueGroup(
    /** The album's or playlist's ID; for [QueueGroupKind.TRACKS], the first track's. */
    val id: String,
    val kind: QueueGroupKind,
    val title: String,
    val artist: String? = null,
    /** The item whose picture heads the group; null: none. */
    val artItemId: String? = null,
) {
    companion object {
        fun album(album: JellyfinAlbum) =
            QueueGroup(album.id, QueueGroupKind.ALBUM, album.displayName, album.artistName.ifEmpty { null }, album.id)

        fun playlist(playlist: JellyfinPlaylist) =
            QueueGroup(playlist.id, QueueGroupKind.PLAYLIST, playlist.displayName, null, playlist.id.takeIf { playlist.hasArt })

        /** Single tracks: titled and pictured by the first. */
        fun tracks(tracks: List<QueueTrack>): QueueGroup? {
            val first = tracks.firstOrNull() ?: return null
            return QueueGroup(first.id, QueueGroupKind.TRACKS, first.title, first.artist.ifEmpty { null }, first.artItemId)
        }
    }
}

/**
 * What the queue keeps about a track: enough to show it offline and to build
 * its stream URL again after a restart ([toJellyfinTrack] for `StreamUrls`).
 * No URLs or file paths are kept: the player looks them up again.
 */
@Serializable
data class QueueTrack(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: String,
    val artItemId: String = albumId,
    val durationSeconds: Double,
    val trackNumber: Int = 0,
    /** The album artist's ID (Now Playing's artist link). */
    val artistId: String? = null,
    val discNumber: Int? = null,
    /** The server file's container ("flac", "m4a"…), for the stream URL. */
    val container: String? = null,
    /** The server file's audio codec, lower-case ("aac", "alac"…): tells AAC from ALAC in an m4a. */
    val codec: String? = null,
    val bitrate: Int? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val normalizationGain: Double? = null,
) {
    /** As far as the stream URL and the format label need it (container and codec decide direct play). */
    fun toJellyfinTrack() = JellyfinTrack(
        id = id,
        name = title,
        indexNumber = trackNumber.takeIf { it > 0 },
        runTimeTicks = (durationSeconds * 10_000_000).toLong(),
        album = album,
        albumId = albumId,
        mediaSource = if (container == null && codec == null && bitrate == null && sampleRate == null && bitDepth == null) {
            null
        } else {
            MediaSource(container = container, codecs = codec, bitrate = bitrate, sampleRate = sampleRate, bitDepth = bitDepth)
        },
        normalizationGain = normalizationGain,
        discNumber = discNumber,
        hasOwnArt = artItemId != albumId,
    )

    /** For Now Playing (the player adjusts the quality label for a cap, conversion or download). */
    fun toPlaybackTrack() = PlaybackTrack(
        id = id,
        trackNumber = trackNumber,
        title = title,
        artist = artist,
        album = album,
        albumId = albumId,
        durationSeconds = durationSeconds,
        normalizationGain = normalizationGain,
        qualityLabel = PlaybackTrack.qualityLabel(AudioFormat.source(toJellyfinTrack()), bitrate),
        artItemId = artItemId,
        sampleRate = sampleRate,
        bitDepth = bitDepth,
        albumArtistId = artistId,
    )

    companion object {
        /** `described`: the track as Now Playing shows it; `source`: the server file. */
        fun from(described: PlaybackTrack, source: MediaSource?, discNumber: Int? = null) = QueueTrack(
            id = described.id,
            title = described.title,
            artist = described.artist,
            album = described.album,
            albumId = described.albumId,
            artItemId = described.artItemId,
            durationSeconds = described.durationSeconds,
            trackNumber = described.trackNumber,
            artistId = described.albumArtistId,
            discNumber = discNumber,
            container = source?.container,
            codec = source?.codecs?.lowercase(),
            bitrate = source?.bitrate,
            sampleRate = source?.sampleRate ?: described.sampleRate,
            bitDepth = source?.bitDepth ?: described.bitDepth,
            normalizationGain = described.normalizationGain,
        )

        fun from(track: JellyfinTrack, album: JellyfinAlbum, position: Int) =
            from(PlaybackTrack.from(track, album, position), track.mediaSource, track.discNumber)

        fun from(entry: PlaylistEntry, position: Int) =
            from(PlaybackTrack.from(entry, position), entry.track.mediaSource, entry.track.discNumber)
    }
}

/**
 * One place in the queue. [id] is unique (a UUID), so the same track can be
 * queued twice; [batchId] is shared by the entries one operation added.
 */
@Serializable
data class QueueEntry(
    val id: String,
    val batchId: String,
    val track: QueueTrack,
    val group: QueueGroup,
)

/** A header and its entries, as the queue editor shows them: a run of one batch in play order. */
data class QueueDisplayGroup(val batchId: String, val group: QueueGroup, val entries: List<QueueEntry>) {
    val id: String get() = entries.first().id
    val durationSeconds: Double get() = entries.sumOf { it.track.durationSeconds }
}

/** The kinds of change, for [PlayQueue.canEdit]. */
enum class QueueEdit { REPLACE, PLAY_NEXT, ADD_TO_QUEUE, MOVE, REMOVE, CLEAR_UPCOMING, SKIP_TO, SHUFFLE, REPEAT_MODE }

/** What a removal did to the current entry. */
enum class QueueCurrentChange {
    /** The current entry is still there. */
    UNCHANGED,

    /** It was removed and a later (or, with Repeat All, wrapped) playable entry is current now: play it. */
    MOVED,

    /** It was removed and nothing playable follows: current is the first remaining entry (null if none); stop. */
    ENDED,
}

/**
 * The listening queue. A value like Swift's struct: change a [copy] and
 * publish it (the player keeps it in Compose state).
 *
 * - [entries]: natural order (as queued and moved with shuffle off).
 * - [playOrder]: every entry ID exactly once, in the order they play; the
 *   same as [entries] with shuffle off.
 * - [currentEntryId]: null only when the queue is empty.
 */
class PlayQueue(
    entries: List<QueueEntry> = emptyList(),
    currentEntryId: String? = entries.firstOrNull()?.id,
    shuffle: Boolean = false,
    playOrder: List<String> = entries.map { it.id },
    repeatMode: RepeatMode = RepeatMode.OFF,
) {
    var entries: List<QueueEntry> = entries
        private set
    var currentEntryId: String? = currentEntryId
        private set
    var shuffle: Boolean = shuffle
        private set
    var playOrder: List<String> = playOrder
        private set
    var repeatMode: RepeatMode = repeatMode

    fun copy(): PlayQueue = PlayQueue(entries, currentEntryId, shuffle, playOrder, repeatMode)

    override fun equals(other: Any?): Boolean =
        other is PlayQueue && entries == other.entries && currentEntryId == other.currentEntryId &&
            shuffle == other.shuffle && playOrder == other.playOrder && repeatMode == other.repeatMode

    override fun hashCode(): Int = listOf(entries, currentEntryId, shuffle, playOrder, repeatMode).hashCode()

    override fun toString(): String = "PlayQueue(current=$currentEntryId, shuffle=$shuffle, repeat=$repeatMode, order=$playOrder)"

    val isEmpty: Boolean get() = entries.isEmpty()

    fun entry(id: String): QueueEntry? = entries.firstOrNull { it.id == id }

    val currentEntry: QueueEntry? get() = currentEntryId?.let(::entry)

    /** The current entry's place in [playOrder]; -1 when there's none. */
    val currentPlayIndex: Int get() = currentEntryId?.let { playOrder.indexOf(it) } ?: -1

    /** Every entry in play order. */
    val entriesInPlayOrder: List<QueueEntry>
        get() {
            val byId = entries.associateBy { it.id }
            return playOrder.mapNotNull(byId::get)
        }

    /** What plays after the current entry, in play order; with Repeat All the earlier ones follow. */
    val upcomingEntries: List<QueueEntry>
        get() {
            val ordered = entriesInPlayOrder
            val index = currentPlayIndex
            if (index < 0) return emptyList()
            val after = ordered.drop(index + 1)
            return if (repeatMode == RepeatMode.ALL) after + ordered.take(index) else after
        }

    /** The editor's groups: runs of consecutive entries of one batch, in play order. */
    val displayGroups: List<QueueDisplayGroup>
        get() {
            val groups = mutableListOf<QueueDisplayGroup>()
            for (e in entriesInPlayOrder) {
                val last = groups.lastOrNull()
                if (last != null && last.batchId == e.batchId) {
                    groups[groups.lastIndex] = last.copy(entries = last.entries + e)
                } else {
                    groups += QueueDisplayGroup(e.batchId, e.group, listOf(e))
                }
            }
            return groups
        }

    // Replacing and adding

    /**
     * The queue becomes `tracks` from `group`, playing `tracks[startIndex]`
     * (an index, as a playlist may hold a song twice), else the first.
     * Shuffle on: the start first and the rest shuffled; no start, all
     * shuffled and the first in play order is current. Shuffle and repeat
     * are kept. No tracks: an empty queue. Returns the new entries.
     */
    fun replace(
        group: QueueGroup,
        tracks: List<QueueTrack>,
        startIndex: Int? = null,
        random: Random = Random,
        newId: () -> String = ::randomUuid,
    ): List<QueueEntry> {
        val added = makeEntries(group, tracks, newId)
        entries = added
        if (added.isEmpty()) {
            currentEntryId = null
            playOrder = emptyList()
            return added
        }
        val start = startIndex?.let { added.getOrNull(it) }
        if (!shuffle) {
            playOrder = added.map { it.id }
            currentEntryId = (start ?: added.first()).id
        } else {
            val order = if (start != null) listOf(start.id) + (added - start).map { it.id }.shuffled(random) else added.map { it.id }.shuffled(random)
            playOrder = order
            currentEntryId = order.first()
        }
        return added
    }

    /**
     * Plays `tracks` after the current batch: in natural order after the
     * last entry of the run of consecutive entries sharing the current
     * entry's batch (so a second Play Next goes before the first); in play
     * order (shuffled) right after the current entry, in the given order.
     * Empty queue: as [replace]. Returns the new entries.
     */
    fun playNext(group: QueueGroup, tracks: List<QueueTrack>, random: Random = Random, newId: () -> String = ::randomUuid): List<QueueEntry> {
        val current = currentEntry ?: return replace(group, tracks, null, random, newId)
        val added = makeEntries(group, tracks, newId)
        if (added.isEmpty()) return added
        var end = entries.indexOf(current)
        while (end + 1 < entries.size && entries[end + 1].batchId == current.batchId) end++
        entries = entries.take(end + 1) + added + entries.drop(end + 1)
        playOrder = if (shuffle) {
            val at = currentPlayIndex + 1
            playOrder.take(at) + added.map { it.id } + playOrder.drop(at)
        } else {
            entries.map { it.id }
        }
        return added
    }

    /**
     * Adds `tracks` at the end; while shuffled, each goes to a random place
     * in play order after the current entry. Empty queue: as [replace].
     * Returns the new entries.
     */
    fun addToQueue(group: QueueGroup, tracks: List<QueueTrack>, random: Random = Random, newId: () -> String = ::randomUuid): List<QueueEntry> {
        if (currentEntry == null) return replace(group, tracks, null, random, newId)
        val added = makeEntries(group, tracks, newId)
        if (added.isEmpty()) return added
        entries = entries + added
        if (shuffle) {
            val order = playOrder.toMutableList()
            val after = currentPlayIndex + 1
            for (e in added) order.add(random.nextInt(after, order.size + 1), e.id)
            playOrder = order
        } else {
            playOrder = entries.map { it.id }
        }
        return added
    }

    private fun makeEntries(group: QueueGroup, tracks: List<QueueTrack>, newId: () -> String): List<QueueEntry> {
        if (tracks.isEmpty()) return emptyList()
        val batch = newId()
        return tracks.map { QueueEntry(newId(), batch, it, group) }
    }

    // Editing

    /**
     * Moves `entryIds` (one entry, or a whole group's) in the displayed
     * list: play order while shuffled (natural order untouched), else
     * natural order. They keep their order in that list, whatever the order
     * of `entryIds`; `toIndex` is where the first of them lands, counted in
     * the list without them (clamped). Unknown IDs are ignored; the current
     * entry stays current. False if nothing changed.
     */
    fun move(entryIds: Collection<String>, toIndex: Int): Boolean {
        val ids = entryIds.toSet()
        val visible = if (shuffle) playOrder else entries.map { it.id }
        val moving = visible.filter { it in ids }
        if (moving.isEmpty()) return false
        val rest = visible.filter { it !in ids }
        val dest = toIndex.coerceIn(0, rest.size)
        val order = rest.take(dest) + moving + rest.drop(dest)
        if (order == visible) return false
        if (shuffle) {
            playOrder = order
        } else {
            val byId = entries.associateBy { it.id }
            entries = order.map { byId.getValue(it) }
            playOrder = order
        }
        return true
    }

    /**
     * Removes `entryIds` (unknown ones ignored). The current entry kept:
     * [QueueCurrentChange.UNCHANGED]. Removed: the next remaining playable
     * entry after it in play order (Repeat All may wrap) becomes current,
     * [QueueCurrentChange.MOVED]; none: [QueueCurrentChange.ENDED], with the
     * first remaining entry in play order current (null when empty).
     */
    fun remove(entryIds: Collection<String>, isPlayable: (QueueEntry) -> Boolean = { true }): QueueCurrentChange {
        val ids = entryIds.toSet()
        val oldOrder = playOrder
        val oldIndex = currentPlayIndex
        val byId = entries.associateBy { it.id }
        entries = entries.filter { it.id !in ids }
        playOrder = playOrder.filter { it !in ids }
        val current = currentEntryId
        if (current != null && current !in ids) return QueueCurrentChange.UNCHANGED
        if (current == null && entries.isEmpty()) return QueueCurrentChange.UNCHANGED
        val after = oldOrder.drop(oldIndex + 1)
        val candidates = if (repeatMode == RepeatMode.ALL) after + oldOrder.take(maxOf(oldIndex, 0)) else after
        val next = candidates.firstOrNull { it !in ids && isPlayable(byId.getValue(it)) }
        if (next != null) {
            currentEntryId = next
            return QueueCurrentChange.MOVED
        }
        currentEntryId = playOrder.firstOrNull()
        return QueueCurrentChange.ENDED
    }

    /** Removes everything but the current entry (played entries too). */
    fun clearUpcoming() {
        val current = currentEntryId ?: return
        remove(entries.map { it.id } - current)
    }

    /** Makes `entryId` current (tapped in the queue); play order unchanged. False if unknown. */
    fun skipTo(entryId: String): Boolean {
        if (entry(entryId) == null) return false
        currentEntryId = entryId
        return true
    }

    // Moving through

    /**
     * Moves to the next entry and returns its ID. Repeat One when the track
     * ended by itself: the same entry. Otherwise the next playable entry in
     * play order; past the end Repeat All wraps to the start (a one-entry
     * queue repeats). Nothing: null, current unchanged. A skip with Repeat
     * One is as with repeat off.
     */
    fun next(trackEnded: Boolean = false, isPlayable: (QueueEntry) -> Boolean = { true }): String? {
        val index = currentPlayIndex
        if (index < 0) return null
        if (repeatMode == RepeatMode.ONE && trackEnded) return currentEntryId
        val candidates = playOrder.drop(index + 1) + if (repeatMode == RepeatMode.ALL) playOrder.take(index + 1) else emptyList()
        return pick(candidates, isPlayable)
    }

    /**
     * Moves to the previous playable entry in play order and returns its ID;
     * Repeat All wraps to the end; else null, current unchanged. (Restarting
     * the track after 3 s is the player's.)
     */
    fun previous(isPlayable: (QueueEntry) -> Boolean = { true }): String? {
        val index = currentPlayIndex
        if (index < 0) return null
        val before = playOrder.take(index).reversed()
        val candidates = before + if (repeatMode == RepeatMode.ALL) playOrder.drop(index).reversed() else emptyList()
        return pick(candidates, isPlayable)
    }

    private fun pick(candidates: List<String>, isPlayable: (QueueEntry) -> Boolean): String? {
        val byId = entries.associateBy { it.id }
        val found = candidates.firstOrNull { id -> byId[id]?.let(isPlayable) == true } ?: return null
        currentEntryId = found
        return found
    }

    /**
     * On: the current entry first, then every other entry (the whole queue,
     * across groups, played ones too) shuffled. Off: natural order again,
     * the current entry kept. The same value: no change.
     */
    fun setShuffle(on: Boolean, random: Random = Random) {
        if (on == shuffle) return
        shuffle = on
        val natural = entries.map { it.id }
        val current = currentEntryId
        playOrder = if (on && current != null) listOf(current) + (natural - current).shuffled(random) else natural
    }

    // Times

    /** Seconds left in the current group: the rest of this track and the rest of its batch run in play order. */
    fun currentGroupRemaining(positionSeconds: Double): Double {
        val current = currentEntry ?: return 0.0
        val following = entriesInPlayOrder.drop(currentPlayIndex + 1).takeWhile { it.batchId == current.batchId }
        return maxOf(0.0, current.track.durationSeconds - positionSeconds) + following.sumOf { it.track.durationSeconds }
    }

    /** Seconds left in the whole queue: the rest of this track and every entry after it in play order (no wrap). */
    fun queueRemaining(positionSeconds: Double): Double {
        val current = currentEntry ?: return 0.0
        return maxOf(0.0, current.track.durationSeconds - positionSeconds) +
            entriesInPlayOrder.drop(currentPlayIndex + 1).sumOf { it.track.durationSeconds }
    }

    // Persistence

    /** `serverId` / `userId`: whose queue it is (the store won't restore it for another server or user). */
    fun snapshot(positionSeconds: Double, serverId: String? = null, userId: String? = null) = PlayQueueSnapshot(
        entries = entries,
        currentEntryId = currentEntryId,
        positionSeconds = positionSeconds.takeIf { it.isFinite() && it > 0 } ?: 0.0,
        shuffle = shuffle,
        playOrder = playOrder,
        repeatMode = repeatMode,
        serverId = serverId,
        userId = userId,
    )

    companion object {
        /** Maniac Mode allows only replacing and adding; otherwise everything. */
        fun canEdit(edit: QueueEdit, maniacMode: Boolean): Boolean =
            !maniacMode || edit == QueueEdit.REPLACE || edit == QueueEdit.PLAY_NEXT || edit == QueueEdit.ADD_TO_QUEUE

        /**
         * The queue a snapshot holds, repaired: later duplicate entry IDs
         * dropped; a play order that isn't exactly the entry IDs (or differs
         * from natural order with shuffle off) becomes natural order with
         * shuffle off; an unknown current, the first in play order.
         */
        fun from(snapshot: PlayQueueSnapshot): PlayQueue {
            val unique = snapshot.entries.distinctBy { it.id }
            val natural = unique.map { it.id }
            val orderOk = snapshot.playOrder.size == natural.size && snapshot.playOrder.toSet() == natural.toSet() &&
                (snapshot.shuffle || snapshot.playOrder == natural)
            val shuffle = snapshot.shuffle && orderOk
            val order = if (orderOk) snapshot.playOrder else natural
            val current = snapshot.currentEntryId?.takeIf { it in natural } ?: order.firstOrNull()
            return PlayQueue(unique, current, shuffle, order, snapshot.repeatMode)
        }
    }
}
