package gg.dekiru.musicplayer.playback

/**
 * Getting a converted stream back after its connection drops (the phone
 * moving between mobile data and Wi‑Fi, a tunnel), on Android.
 *
 * A stream the server converts on the fly (`/universal`) has no length and
 * can't be seeked, so a player that loses the connection can only ask for
 * it again from its start: ExoPlayer does that by itself, and the track
 * jumps back to 0:00. Instead the engine lets the error through, asks for
 * the same stream started where playback had got to (`StartTimeTicks`, as a
 * seek does) and tries again: at once, then after 1, 2 and 4 s, then every
 * 10 s, and at once whenever the network comes back. After about two
 * minutes it gives up and says playback failed; Play then starts from the
 * same point. Direct streams and downloaded files aren't affected: the
 * player picks those up where they stopped.
 */
object StreamReconnect {

    /**
     * Media3's `PlaybackException` codes for a lost or failed connection:
     * `ERROR_CODE_IO_UNSPECIFIED` (2000), `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED`
     * (2001, a dropped connection or no network) and
     * `ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT` (2002). Others (a refused
     * request, a bad file) fail at once, as before.
     */
    val NETWORK_ERROR_CODES: Set<Int> = setOf(2000, 2001, 2002)

    /** How long, from the first failure, the engine keeps trying before it says playback failed. */
    const val GIVE_UP_AFTER_MILLIS = 120_000L

    /** The waits between tries: at once, then 1, 2 and 4 s; every 10 s after that. */
    private val WAITS = longArrayOf(0, 1_000, 2_000, 4_000)
    private const val STEADY_WAIT = 10_000L

    /** Whether an error in `item`'s stream (on this device's player, not a Cast device's) is reconnected rather than reported. */
    fun shouldReconnect(errorCode: Int, item: EngineItem?, casting: Boolean): Boolean =
        !casting && item != null && item.seeksByRestart(casting = false) && errorCode in NETWORK_ERROR_CODES

    /**
     * Where the new stream starts: the old one's start in the track plus how
     * far it got, kept inside the track as a seek is (a second short of the
     * end, so a little of it still plays and the queue moves on as usual).
     */
    fun resumePosition(offsetMillis: Long, positionMillis: Long, durationMillis: Long): Long {
        val reached = offsetMillis.coerceAtLeast(0) + positionMillis.coerceAtLeast(0)
        return if (durationMillis > 0) reached.coerceIn(0, (durationMillis - 1_000).coerceAtLeast(0)) else reached
    }

    /**
     * The wait before try number `attempt` (0: the first, straight after the
     * error), `elapsedMillis` after the first failure; null when that try
     * would come after [GIVE_UP_AFTER_MILLIS]: give up.
     */
    fun waitBefore(attempt: Int, elapsedMillis: Long): Long? {
        val wait = WAITS.getOrElse(attempt) { STEADY_WAIT }
        return if (elapsedMillis + wait > GIVE_UP_AFTER_MILLIS) null else wait
    }
}

/**
 * One run of tries to get a stream back (see [StreamReconnect]). `now`: a
 * clock in ms that keeps counting while the device sleeps.
 */
class ReconnectState(private val now: () -> Long) {
    /** Between a failure and playback coming back (or giving up). */
    var active = false
        private set

    private var attempt = 0
    private var since = 0L

    /**
     * The stream failed (again): how long to wait before the next try, or
     * null to give up (the run is over; a later failure starts a new one).
     */
    fun failed(): Long? {
        if (!active) {
            active = true
            attempt = 0
            since = now()
        }
        val wait = StreamReconnect.waitBefore(attempt, now() - since)
        attempt++
        if (wait == null) reset()
        return wait
    }

    /** Playing again, a new queue, or Cast took over: the run is over. */
    fun reset() {
        active = false
        attempt = 0
    }
}
