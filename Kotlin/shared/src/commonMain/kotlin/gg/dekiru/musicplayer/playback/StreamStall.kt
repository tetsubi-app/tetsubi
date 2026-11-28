package gg.dekiru.musicplayer.playback

/**
 * Noticing a converted stream whose connection has gone quiet, so it can be
 * restarted before the buffer runs dry (Android, `Media3Engine`).
 *
 * A stream the server converts on the fly (`/universal`) can stall behind a
 * reverse proxy: the buffered position stops growing, the buffer ahead
 * shrinks as the track plays, and ~30 s later the proxy resets the stream
 * (an HTTP/2 `PROTOCOL_ERROR`). ExoPlayer only raises that error once the
 * buffer is empty, so the listener hears the underrun, the error and the
 * reconnect (about 1.4 s of silence). Restarting the stream at the position
 * as soon as the stall is certain costs only the new connection's start
 * (about 0.5 s). It can't be gapless: ExoPlayer can't splice a second stream
 * onto the end of the buffered audio (a replaced item flushes its buffer),
 * so a stream started at the buffered end would still have to wait for the
 * buffer to play out, and the item that is stalled never ends to make way
 * for it.
 *
 * ExoPlayer's load control keeps about 50 s ahead, so a healthy stream sits
 * at 44–56 s ahead and grows in steps of a few seconds. A stalled one falls
 * below [BUFFER_LOW_MILLIS] about 12 s after it went quiet.
 */
object StreamStall {
    /** How often the engine looks (main thread; a few property reads). */
    const val POLL_MILLIS = 2_000L

    /**
     * No growth in the buffered position for this long counts as stalled. A
     * healthy stream grows in steps at most a few seconds apart; 12 s allows
     * a hiccup the network recovers from by itself.
     */
    const val STALL_AFTER_MILLIS = 12_000L

    /**
     * Only when less than this much audio is buffered ahead of the playing
     * point. Below ExoPlayer's ~50 s target, above what a healthy stream
     * dips to between loads (~44 s), so a full buffer that has simply
     * stopped loading is never taken for a stall.
     */
    const val BUFFER_LOW_MILLIS = 40_000L

    /**
     * Within this of the track's end the stream is done, not stalled (the
     * converted stream's length is the server's, a little off the track's).
     */
    const val END_MARGIN_MILLIS = 3_000L

    /** At most this many watchdog restarts within [RESTART_WINDOW_MILLIS]; after that the underrun and error path ([StreamReconnect]) takes over, so it can't loop. */
    const val MAX_RESTARTS = 3
    const val RESTART_WINDOW_MILLIS = 180_000L

    enum class Decision { WAIT, RESTART }

    /**
     * Whether to restart the stream now.
     *
     * @param playing play is wanted and the track has made a sound (playing,
     *   or buffering after an underrun): not a pause, not the start of a track.
     * @param loading the player is still loading the stream (false once it has
     *   been read to its end, or while the buffer is full).
     * @param converted the playing item is a converted stream (a direct
     *   stream resumes by byte range and needs no watchdog).
     * @param bufferedAheadMillis audio buffered ahead of the playing point.
     * @param sinceGrowthMillis how long the buffered position hasn't grown.
     * @param reachedEnd the buffered position is at the track's end.
     * @param reconnectBusy a reconnect is waiting or running, or gave up.
     * @param recentRestarts watchdog restarts within [RESTART_WINDOW_MILLIS].
     */
    fun decide(
        playing: Boolean,
        loading: Boolean,
        converted: Boolean,
        bufferedAheadMillis: Long,
        sinceGrowthMillis: Long,
        reachedEnd: Boolean,
        reconnectBusy: Boolean,
        recentRestarts: Int,
    ): Decision = when {
        !playing || !converted || !loading || reachedEnd || reconnectBusy -> Decision.WAIT
        recentRestarts >= MAX_RESTARTS -> Decision.WAIT
        bufferedAheadMillis >= BUFFER_LOW_MILLIS -> Decision.WAIT
        sinceGrowthMillis < STALL_AFTER_MILLIS -> Decision.WAIT
        else -> Decision.RESTART
    }

    /** Whether a buffered position (absolute, in the track) is at its end. */
    fun reachedEnd(bufferedEndMillis: Long, durationMillis: Long): Boolean =
        durationMillis > 0 && bufferedEndMillis >= durationMillis - END_MARGIN_MILLIS
}

/**
 * Time since the buffered position of one stream last grew. `key` names the
 * stream (the item and where it started); a new key, or a position that
 * moved backwards, starts the count again, so a skip, a seek or a restart
 * never counts as a stall.
 */
class BufferGrowth {
    private var key: String? = null
    private var buffered = 0L
    private var grewAt = 0L

    /** ms since `bufferedMillis` last grew, at time `now` (a clock in ms). */
    fun sinceGrowth(key: String, bufferedMillis: Long, now: Long): Long {
        if (this.key == null || key != this.key || bufferedMillis < buffered) {
            this.key = key
            buffered = bufferedMillis
            grewAt = now
        } else if (bufferedMillis > buffered) {
            buffered = bufferedMillis
            grewAt = now
        }
        return now - grewAt
    }

    fun reset() {
        key = null
    }
}

/** The watchdog's recent restarts per queue entry, for [StreamStall.MAX_RESTARTS]. */
class StallRestarts {
    private val restarts = ArrayDeque<Pair<String, Long>>()

    /** Restarts of `entryId` within [StreamStall.RESTART_WINDOW_MILLIS] of `now`. */
    fun recent(entryId: String, now: Long): Int {
        while (restarts.isNotEmpty() && now - restarts.first().second > StreamStall.RESTART_WINDOW_MILLIS) restarts.removeFirst()
        return restarts.count { it.first == entryId }
    }

    fun record(entryId: String, now: Long) {
        restarts.addLast(entryId to now)
    }

    fun clear() = restarts.clear()
}
