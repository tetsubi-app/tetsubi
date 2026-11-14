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
