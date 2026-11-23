package gg.dekiru.musicplayer.playback

/** Why libmpv ended a file (`mpv_end_file_reason`). */
internal enum class MpvEndReason {
    /** Played to its end. */
    EOF,

    /** Stopped by a command (`stop`, `playlist-play-index`, a new queue). */
    STOP,
    QUIT,

    /** It couldn't be opened or decoded. */
    ERROR,

    /** A playlist file was expanded into its entries. */
    REDIRECT,
    UNKNOWN,
    ;

    companion object {
        fun of(code: Int): MpvEndReason = when (code) {
            0 -> EOF
            2 -> STOP
            3 -> QUIT
            4 -> ERROR
            5 -> REDIRECT
            else -> UNKNOWN
        }
    }
}

/** What the event thread passes on to the main thread. */
internal sealed interface MpvEvent {
    /**
     * mpv began playing playlist entry `position`. `path`: what that entry
     * was loaded from (`playlist/N/filename`), to check the position against
     * the queue when entries before it were moved meanwhile; null: unknown.
     */
    data class StartFile(val position: Int, val path: String? = null) : MpvEvent

    /** mpv finished the playing entry. `error`: libmpv's error text for [MpvEndReason.ERROR]. */
    data class EndFile(val reason: MpvEndReason, val error: String? = null) : MpvEvent

    data class Paused(val paused: Boolean) : MpvEvent

    /** `idle-active`: no file loaded (the queue ended, failed or was stopped). */
    data class Idle(val idle: Boolean) : MpvEvent

    /**
     * `core-idle`: no audio is moving, for whatever reason: paused, opening
     * a file, waiting for the cache (`paused-for-cache`), seeking, or idle.
     */
    data class CoreIdle(val coreIdle: Boolean) : MpvEvent

    /** Repeat One went round: mpv's `loop-file` seeks back to 0:00 without a new file. */
    data object Repeated : MpvEvent

    /** A move we asked for (`playlist-play-index`) was refused: stop waiting for its file. */
    data object MoveFailed : MpvEvent
}

/** `loop-file` and `loop-playlist` for a repeat mode. */
internal fun mpvLoopOptions(mode: RepeatMode): Pair<String, String> = when (mode) {
    RepeatMode.OFF -> "no" to "no"
    RepeatMode.ALL -> "no" to "inf"
    RepeatMode.ONE -> "inf" to "no"
}

/** What `loadfile` is given: a downloaded file's path (`file://` URIs here aren't percent-encoded), else the URL. */
internal fun mpvTarget(url: String): String = if (url.startsWith("file://")) url.removePrefix("file://") else url

/**
 * Commands that replace the entries after `current` (of `size`) with `urls`,
 * leaving the playing one alone, and the first `keep` entries after it too
 * (the same as before: the next stays opened ahead, for a gapless join).
 */
internal fun replaceAfterCommands(size: Int, current: Int, urls: List<String>, keep: Int = 0): List<List<String>> = buildList {
    // From the end, so each index is still right when it's removed.
    for (i in size - 1 downTo current + 1 + keep) add(listOf("playlist-remove", "$i"))
    urls.forEach { add(listOf("loadfile", mpvTarget(it), "append")) }
}

/**
 * Commands that replace the `current` entries before the playing one with
 * `urls` (in a playlist of `size`), leaving the playing one alone: removed
 * from the front, then each new one appended and moved into place.
 */
internal fun replaceBeforeCommands(size: Int, current: Int, urls: List<String>): List<List<String>> = buildList {
    repeat(current) { add(listOf("playlist-remove", "0")) }
    val rest = size - current
    urls.forEachIndexed { i, url ->
        add(listOf("loadfile", mpvTarget(url), "append"))
        // playlist-move puts the entry before the target: the new one at `i`.
        add(listOf("playlist-move", "${rest + i}", "$i"))
    }
}

/** Whether mpv's `path` is `url`'s stream (also when restarted at a point, `StartTimeTicks`). */
internal fun isStreamOf(path: String, url: String): Boolean =
    StreamUrls.startingAt(path, 0) == StreamUrls.startingAt(mpvTarget(url), 0)

/**
 * Commands that put `url` in place of entry `index` of a playlist of `size`
 * (a converted stream restarted at a point, or its own stream back), and
 * play it if `play`. The new entry is appended and moved in front of the old
 * one; the old one is removed only once it isn't playing, as removing the
 * playing entry would start the next.
 */
internal fun replaceEntryCommands(size: Int, index: Int, url: String, play: Boolean): List<List<String>> = buildList {
    add(listOf("loadfile", mpvTarget(url), "append"))
    // playlist-move puts the entry before the target: the new one at `index`, the old one at `index + 1`.
    add(listOf("playlist-move", "$size", "$index"))
    if (play) add(listOf("playlist-play-index", "$index"))
    add(listOf("playlist-remove", "${index + 1}"))
}

/** The `Authorization` header for every stream mpv opens, prefetched ones too (a header list: clear, then add). */
internal fun authorizationCommands(header: String?): List<List<String>> = buildList {
    add(listOf("change-list", "http-header-fields", "clr", ""))
    // `append` takes the value as it is: the header's commas aren't list separators.
    if (header != null) add(listOf("change-list", "http-header-fields", "append", "Authorization: $header"))
}

/** mpv's `volume` (0…100) for the engine's 0…1. */
internal fun mpvVolume(volume: Float): String = (volume.coerceIn(0f, 1f) * 100.0).toString()

/**
 * The queue as mpv holds it, and what its events mean for [PlayerController],
 * mirroring `Media3Engine`. Main thread only. mpv's playlist is the queue,
 * entry for entry, so mpv joins tracks gaplessly and opens the next one
 * ahead; this keeps the same list and the playing index, sends the commands
 * (`send`, run in order off the main thread) and turns events into
 * [EngineListener] calls:
 *
 * - A track change we asked for (a new queue, a skip, a restarted stream)
 *   is waited for ([pending]); mpv's own (the next track, Repeat All going
 *   round) is reported as automatic. A new queue isn't reported (the
 *   controller knows where it starts); a skip is, straight away.
 * - The last track ending (Repeat off) is [EngineListener.onEnded]; mpv is
 *   then idle with the playlist kept, and Play or a skip loads a track again.
 * - A track that fails stops playback there ([EngineListener.onError]); Play
 *   tries it again. (mpv would go on to the next; this stops it.)
 * - A stream the server converts on the fly is seeked by restarting it at
 *   the point (`StartTimeTicks`), as Media3Engine does.
 */
internal class MpvPlayback(
    private val send: (List<String>) -> Unit,
    private val listener: () -> EngineListener?,
) {
    /** The queue as given: each item's own stream, from 0:00. */
    val items = mutableListOf<EngineItem>()

    /** The playing (or loaded, or last) entry. */
    var current = 0
        private set

    /** A track change we asked for and haven't seen start yet: other starts until then are stale. */
    var pending: Int? = null
        private set

    /** No file loaded: nothing queued yet, the queue ended, a track failed or it was stopped. */
    var unloaded = true
        private set

    var repeat = RepeatMode.OFF
        private set

    /** Per queue entry: where a restarted converted stream begins, in ms (absent: 0). */
    private val startOffsets = mutableMapOf<String, Long>()

    private var paused = true
    private var idle = true
    private var coreIdle = true
    private var reportedPlaying = false
    private var reportedStarting = false

    /**
     * Where a direct stream starts when Play loads it (a cued queue, or a
     * seek while nothing is loaded), in ms; 0: from the start. A converted
     * stream is swapped for one started there instead ([startOffsets]).
     */
    var heldStartMillis = 0L
        private set

    val isPlaying: Boolean get() = reportedPlaying

    /** Where the playing entry's stream begins in its track (a restarted converted stream), in ms. */
    val currentOffsetMillis: Long get() = currentItem?.let { startOffsets[it.entryId] } ?: 0L

    val currentItem: EngineItem? get() = items.getOrNull(current)

    fun setQueue(newItems: List<EngineItem>, startIndex: Int) {
        items.clear()
        items += newItems
        startOffsets.clear()
        heldStartMillis = 0
        // `stop` empties mpv's playlist; `append` doesn't start anything.
        send(listOf("stop"))
        newItems.forEach { send(listOf("loadfile", mpvTarget(it.url), "append")) }
        if (newItems.isEmpty()) {
            current = 0
            pending = null
            unloaded = true
            return
        }
        current = startIndex.coerceIn(0, newItems.lastIndex)
        load(current)
    }

    /**
     * Holds `newItems` in mpv's playlist, paused at `startMillis` into item
     * `startIndex`, without opening anything (`append` doesn't start a
     * file): [play] loads it there.
     */
    fun cue(newItems: List<EngineItem>, startIndex: Int, startMillis: Long) {
        items.clear()
        items += newItems
        startOffsets.clear()
        heldStartMillis = 0
        send(listOf("set", "pause", "yes"))
        send(listOf("stop"))
        newItems.forEach { send(listOf("loadfile", mpvTarget(it.url), "append")) }
        pending = null
        unloaded = true
        current = if (newItems.isEmpty()) 0 else startIndex.coerceIn(0, newItems.lastIndex)
        if (newItems.isNotEmpty() && startMillis > 0) seekTo(startMillis)
        updateStarting()
    }

    fun replaceAfterCurrent(newItems: List<EngineItem>) {
        if (items.isEmpty()) return
        replaceAroundCurrent(items.take(current), newItems)
    }

    /**
     * The entries before and after the playing one become `before` and
     * `after`; the playing one isn't touched (mpv carries on with it), nor
     * are the entries after it that stay the same. mpv's positions of the
     * entries shift meanwhile, so starts are checked by path ([started]).
     */
    fun replaceAroundCurrent(before: List<EngineItem>, after: List<EngineItem>) {
        val playing = items.getOrNull(current) ?: return
        val existingAfter = items.drop(current + 1)
        val keep = CastWindow.sharedStart(existingAfter, after)
        replaceAfterCommands(items.size, current, after.drop(keep).map { it.url }, keep).forEach(send)
        if (items.take(current).map { it.entryId } != before.map { it.entryId }) {
            replaceBeforeCommands(current + 1 + after.size, current, before.map { it.url }).forEach(send)
        }
        items.clear()
        items += before + playing + after
        val moved = before.size - current
        current = before.size
        pending = pending?.let { it + moved }
        val queued = items.mapTo(HashSet()) { it.entryId }
        startOffsets.keys.retainAll(queued)
    }

    fun play() {
        if (items.isEmpty()) return
        send(listOf("set", "pause", "no"))
        // Cued, ended or failed: load the track (a failed one is retried), at the held point.
        if (unloaded) {
            load(current)
            if (heldStartMillis > 0) send(listOf("seek", formatSeconds(heldStartMillis), "absolute+exact"))
            heldStartMillis = 0
        }
        updateStarting()
    }

    fun pause() = send(listOf("set", "pause", "yes"))

    /** Plays entry `index` from its start (Media3's `seekTo(index, 0)`). */
    fun skipTo(index: Int) {
        if (index !in items.indices) return
        heldStartMillis = 0
        val restored = restoreOwnStream(index)
        if (index == current) {
            // The same track from 0:00: no change to report.
            if (restored || unloaded || items[index].seeksByRestart) load(index) else send(listOf("seek", "0", "absolute+exact"))
            return
        }
        current = index
        load(index)
        restoreOtherTracks()
        listener()?.onIndexChanged(index, automatic = false)
    }

    /**
     * Seeks in the playing track. Returns true for an ordinary mpv seek (the
     * engine then knows the next seek event is its own), false when the
     * stream was restarted or nothing could be seeked.
     */
    fun seekTo(millis: Long): Boolean {
        val item = items.getOrNull(current) ?: return false
        if (!item.seeksByRestart) {
            if (unloaded) {
                // Nothing loaded: Play loads it there.
                heldStartMillis = millis.coerceAtLeast(0)
                return false
            }
            send(listOf("seek", formatSeconds(millis), "absolute+exact"))
            return true
        }
        val target = millis.coerceIn(0, (item.durationMillis - 1_000).coerceAtLeast(0))
        if (target == 0L && currentOffsetMillis == 0L) {
            // Its own stream, from the start.
            if (!unloaded) load(current)
            return false
        }
        if (target == 0L) startOffsets.remove(item.entryId) else startOffsets[item.entryId] = target
        // Ended or failed: the new stream waits for Play.
        val play = !unloaded
        replaceEntryCommands(items.size, current, StreamUrls.startingAt(item.url, target), play).forEach(send)
        if (play) pending = current
        return false
    }

    fun setRepeatMode(mode: RepeatMode) {
        repeat = mode
        val (file, playlist) = mpvLoopOptions(mode)
        send(listOf("set", "loop-file", file))
        send(listOf("set", "loop-playlist", playlist))
    }

    fun stop() {
        send(listOf("stop"))
        items.clear()
        startOffsets.clear()
        heldStartMillis = 0
        current = 0
        pending = null
        unloaded = true
        updateStarting()
    }

    /** One event from mpv, in the order mpv sent them. */
    fun handle(event: MpvEvent) {
        when (event) {
            is MpvEvent.StartFile -> started(checked(event.position, event.path))
            is MpvEvent.EndFile -> ended(event)
            is MpvEvent.Paused -> {
                paused = event.paused
                updatePlaying()
                updateStarting()
            }
            is MpvEvent.Idle -> {
                idle = event.idle
                updatePlaying()
            }
            is MpvEvent.CoreIdle -> {
                coreIdle = event.coreIdle
                updateStarting()
            }
            MpvEvent.Repeated -> if (!unloaded && pending == null && repeat == RepeatMode.ONE) {
                listener()?.onIndexChanged(current, automatic = true)
            }
            MpvEvent.MoveFailed -> pending = null
        }
    }

    /**
     * The queue position of the entry mpv started: `position`, unless its
     * path says another entry (entries before it moved after mpv read its
     * position): then the entry with that stream nearest after the playing
     * one. Unknown path, or none matches: `position` as given.
     */
    private fun checked(position: Int, path: String?): Int {
        if (path == null || position < 0) return position
        if (items.getOrNull(position)?.let { isStreamOf(path, it.url) } == true) return position
        val matches = items.indices.filter { isStreamOf(path, items[it].url) }
        if (matches.isEmpty()) return position
        val expected = pending ?: (current + 1)
        return matches.minBy { kotlin.math.abs(it - expected) }
    }

    private fun started(position: Int) {
        if (position < 0) return
        pending?.let { wanted ->
            // Ours (already reported), or stale: a start from before the last move.
            if (position == wanted) pending = null
            return
        }
        if (unloaded) return
        // The next track, Repeat All going round, or a lone track repeating.
        current = position.coerceIn(0, (items.size - 1).coerceAtLeast(0))
        restoreOtherTracks()
        listener()?.onIndexChanged(current, automatic = true)
    }

    private fun ended(event: MpvEvent.EndFile) {
        if (pending != null || unloaded) return
        when (event.reason) {
            MpvEndReason.EOF -> if (current >= items.lastIndex && repeat == RepeatMode.OFF) {
                // mpv goes idle and keeps the playlist.
                unloaded = true
                updateStarting()
                listener()?.onEnded()
            }
            MpvEndReason.ERROR -> {
                // mpv goes on to the next entry by itself: stop there, keeping the playlist, so Play retries this one.
                unloaded = true
                send(listOf("set", "pause", "yes"))
                send(listOf("stop", "keep-playlist"))
                updateStarting()
                listener()?.onError("Playback failed (${event.error ?: "error"}).")
            }
            else -> Unit
        }
    }

    /** Play is wanted and a file is loading or playing, but no audio moves yet (opening, the cache, a seek). */
    private fun updateStarting() {
        val starting = !paused && !unloaded && coreIdle
        if (starting == reportedStarting) return
        reportedStarting = starting
        listener()?.onStartingChanged(starting)
    }

    private fun updatePlaying() {
        val playing = !paused && !idle
        if (playing == reportedPlaying) return
        reportedPlaying = playing
        listener()?.onIsPlayingChanged(playing)
    }

    /** Starts entry `index` and waits for it. */
    private fun load(index: Int) {
        pending = index
        unloaded = false
        send(listOf("playlist-play-index", "$index"))
    }

    /** Puts entry `index`'s own stream back if it was restarted at a point. Returns whether it did. */
    private fun restoreOwnStream(index: Int): Boolean {
        val item = items.getOrNull(index) ?: return false
        if (startOffsets.remove(item.entryId) == null) return false
        replaceEntryCommands(items.size, index, item.url, play = false).forEach(send)
        return true
    }

    /** Puts every other track's own stream back, so Previous or Repeat start it from 0:00. */
    private fun restoreOtherTracks() {
        val playing = currentItem?.entryId
        for (entry in startOffsets.keys.filter { it != playing }) {
            val index = items.indexOfFirst { it.entryId == entry }
            if (index >= 0) restoreOwnStream(index) else startOffsets.remove(entry)
        }
    }

    private fun formatSeconds(millis: Long): String = (millis.coerceAtLeast(0) / 1000.0).toString()
}
