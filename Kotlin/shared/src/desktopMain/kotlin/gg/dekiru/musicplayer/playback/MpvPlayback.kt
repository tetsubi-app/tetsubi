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
    /** mpv began playing playlist entry `position`. */
    data class StartFile(val position: Int) : MpvEvent

    /** mpv finished the playing entry. `error`: libmpv's error text for [MpvEndReason.ERROR]. */
    data class EndFile(val reason: MpvEndReason, val error: String? = null) : MpvEvent

    data class Paused(val paused: Boolean) : MpvEvent

    /** `idle-active`: no file loaded (the queue ended, failed or was stopped). */
    data class Idle(val idle: Boolean) : MpvEvent

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

/** Commands that replace the entries after `current` (of `size`) with `urls`, leaving the playing one alone. */
internal fun replaceAfterCommands(size: Int, current: Int, urls: List<String>): List<List<String>> = buildList {
    // From the end, so each index is still right when it's removed.
    for (i in size - 1 downTo current + 1) add(listOf("playlist-remove", "$i"))
    urls.forEach { add(listOf("loadfile", mpvTarget(it), "append")) }
}

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

    /** Per queue index: where a restarted converted stream begins, in ms (absent: 0). */
    private val startOffsets = mutableMapOf<Int, Long>()

    private var paused = true
    private var idle = true
    private var reportedPlaying = false

    val isPlaying: Boolean get() = reportedPlaying

    /** Where the playing entry's stream begins in its track (a restarted converted stream), in ms. */
    val currentOffsetMillis: Long get() = startOffsets[current] ?: 0L

    val currentItem: EngineItem? get() = items.getOrNull(current)

    fun setQueue(newItems: List<EngineItem>, startIndex: Int) {
        items.clear()
        items += newItems
        startOffsets.clear()
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

    fun replaceAfterCurrent(newItems: List<EngineItem>) {
        replaceAfterCommands(items.size, current, newItems.map { it.url }).forEach(send)
        while (items.size > current + 1) items.removeAt(items.lastIndex)
        items += newItems
        startOffsets.keys.removeAll { it > current }
    }

    fun play() {
        if (items.isEmpty()) return
        send(listOf("set", "pause", "no"))
        // Ended or failed: load the track again (a failed one is retried).
        if (unloaded) load(current)
    }

    fun pause() = send(listOf("set", "pause", "yes"))

    /** Plays entry `index` from its start (Media3's `seekTo(index, 0)`). */
    fun skipTo(index: Int) {
        if (index !in items.indices) return
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
            if (unloaded) return false
            send(listOf("seek", formatSeconds(millis), "absolute+exact"))
            return true
        }
        val target = millis.coerceIn(0, (item.durationMillis - 1_000).coerceAtLeast(0))
        if (target == 0L && currentOffsetMillis == 0L) {
            // Its own stream, from the start.
            if (!unloaded) load(current)
            return false
        }
        if (target == 0L) startOffsets.remove(current) else startOffsets[current] = target
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
        current = 0
        pending = null
        unloaded = true
    }

    /** One event from mpv, in the order mpv sent them. */
    fun handle(event: MpvEvent) {
        when (event) {
            is MpvEvent.StartFile -> started(event.position)
            is MpvEvent.EndFile -> ended(event)
            is MpvEvent.Paused -> {
                paused = event.paused
                updatePlaying()
            }
            is MpvEvent.Idle -> {
                idle = event.idle
                updatePlaying()
            }
            MpvEvent.Repeated -> if (!unloaded && pending == null && repeat == RepeatMode.ONE) {
                listener()?.onIndexChanged(current, automatic = true)
            }
            MpvEvent.MoveFailed -> pending = null
        }
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
                listener()?.onEnded()
            }
            MpvEndReason.ERROR -> {
                // mpv goes on to the next entry by itself: stop there, keeping the playlist, so Play retries this one.
                unloaded = true
                send(listOf("set", "pause", "yes"))
                send(listOf("stop", "keep-playlist"))
                listener()?.onError("Playback failed (${event.error ?: "error"}).")
            }
            else -> Unit
        }
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
        if (startOffsets.remove(index) == null) return false
        replaceEntryCommands(items.size, index, items[index].url, play = false).forEach(send)
        return true
    }

    /** Puts every other track's own stream back, so Previous or Repeat start it from 0:00. */
    private fun restoreOtherTracks() {
        for (index in startOffsets.keys.filter { it != current }) restoreOwnStream(index)
    }

    private fun formatSeconds(millis: Long): String = (millis.coerceAtLeast(0) / 1000.0).toString()
}
