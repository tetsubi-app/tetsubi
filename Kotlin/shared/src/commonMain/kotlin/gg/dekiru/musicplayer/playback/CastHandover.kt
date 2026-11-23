package gg.dekiru.musicplayer.playback

/** Where playback is, read from the player it's leaving. */
data class PlaybackSnapshot(
    val index: Int,
    /** Into the track, counting a restarted converted stream's starting point. */
    val positionMillis: Long,
    val playWhenReady: Boolean,
    val repeat: RepeatMode,
)

/**
 * How the other player picks up the queue (this device's ExoPlayer, or a
 * Cast device's): every item's stream for that player, where to start, and
 * whether the current item is a converted stream restarted part-way.
 */
data class HandoverPlan(
    /** Each queue item's stream for the new player, in queue order. */
    val urls: List<String>,
    val startIndex: Int,
    /** Where the new player starts the current stream (0 for a restarted one). */
    val startPositionMillis: Long,
    /** > 0: the current item is a converted stream started this far in (`StartTimeTicks`). */
    val startOffsetMillis: Long,
    val playWhenReady: Boolean,
    val repeat: RepeatMode,
)

/**
 * Moving playback between this device and a Cast device, and restarting
 * on the Cast device: the queue, the current item and the point in it,
 * play/pause and repeat carry over. A stream the server converts on the
 * fly can't be seeked, so the current one starts at that point instead
 * (as a seek does). Plain Kotlin, so it's tested on its own.
 */
object CastHandover {

    /** Null when there's nothing to carry over. `toCast`: the new player is the Cast device. */
    fun plan(items: List<EngineItem>, from: PlaybackSnapshot, toCast: Boolean): HandoverPlan? {
        if (items.isEmpty()) return null
        val index = from.index.coerceIn(0, items.lastIndex)
        val item = items[index]
        var position = from.positionMillis.coerceAtLeast(0)
        // Not past the end: a moment before it, so the track still plays out and moves on.
        if (item.durationMillis > 0) position = position.coerceAtMost((item.durationMillis - 1_000).coerceAtLeast(0))
        val urls = items.map { it.url(toCast) }.toMutableList()
        val restart = item.seeksByRestart(toCast) && position > 0
        if (restart) urls[index] = StreamUrls.startingAt(urls[index], position)
        return HandoverPlan(
            urls = urls,
            startIndex = index,
            startPositionMillis = if (restart) 0 else position,
            startOffsetMillis = if (restart) position else 0,
            playWhenReady = from.playWhenReady,
            repeat = from.repeat,
        )
    }
}

/**
 * Which queue items a Cast device holds: the whole queue when it's short,
 * else a window around the playing item (a few behind for Previous, the
 * next [AHEAD] ahead), moved along as it plays (`Media3Engine`). A long
 * queue (several albums) in one load could be more than the receiver
 * takes. Plain Kotlin, so it's tested on its own.
 */
object CastWindow {
    /** Items kept before the playing one (for Previous). */
    const val BEHIND = 5

    /** Items queued after the playing one. */
    const val AHEAD = 20

    /** A queue this long or shorter goes to the receiver whole. */
    const val WHOLE = BEHIND + 1 + AHEAD

    /** Whether a queue of `size` is sent in windows (the receiver's own Repeat All would only go round the window). */
    fun isPartial(size: Int): Boolean = size > WHOLE

    /**
     * The queue indices the receiver holds, in its order, with `current`
     * among them: all of them for a short queue; else up to [BEHIND] before
     * and [AHEAD] after, going round to the start with Repeat All.
     */
    fun indices(size: Int, current: Int, repeat: RepeatMode): List<Int> {
        if (size <= 0) return emptyList()
        if (!isPartial(size)) return (0 until size).toList()
        val at = current.coerceIn(0, size - 1)
        val ahead = (1..AHEAD).mapNotNull { step ->
            val i = at + step
            when {
                i < size -> i
                repeat == RepeatMode.ALL -> i - size
                else -> null
            }
        }
        return (maxOf(0, at - BEHIND)..at).toList() + ahead
    }

    /** How many items `existing` and `wanted` share at their start, by queue entry. */
    fun sharedStart(existing: List<EngineItem>, wanted: List<EngineItem>): Int {
        var n = 0
        while (n < existing.size && n < wanted.size && existing[n].entryId == wanted[n].entryId) n++
        return n
    }
}
