package gg.dekiru.musicplayer.playback

/**
 * Play Next / Add to Queue applied in the order they were tapped (as iOS's
 * tap-order queue). Each add takes a [Turn] when tapped; when its tracks
 * are ready it [finish]es with its change, which applies once every earlier
 * turn has applied or been dropped. A failed fetch drops its turn (later
 * adds still apply). Adds that need no fetch take a turn too, so they don't
 * jump ahead. Play (replace) [cancelAll]s what's still waiting and takes a
 * turn of its own, so adds tapped after it land in the new queue; a later
 * Play supersedes an earlier one still fetching.
 */
class AddTurns {
    class Turn internal constructor() {
        internal var finished = false
        internal var change: (() -> Unit)? = null

        /** Dropped by [cancelAll] (a newer Play): its fetch's outcome, success or failure, stays silent. */
        var isCancelled = false
            internal set
    }

    private val waiting = ArrayDeque<Turn>()

    /** Adds still waiting for their tracks (or for an earlier one). */
    val pending: Int get() = waiting.size

    fun take(): Turn = Turn().also { waiting.addLast(it) }

    /**
     * `turn`'s tracks are ready (`change`) or it failed (null): applies it
     * and every finished turn after it once nothing earlier waits. A second
     * finish, or one for a cancelled turn, is ignored.
     */
    fun finish(turn: Turn, change: (() -> Unit)?) {
        if (turn.finished) return
        turn.finished = true
        turn.change = change
        while (waiting.firstOrNull()?.finished == true) waiting.removeFirst().change?.invoke()
    }

    fun drop(turn: Turn) = finish(turn, null)

    /** Drops every waiting add silently (a new Play, sign-out); later adds take new turns. */
    fun cancelAll() {
        waiting.forEach {
            it.finished = true
            it.isCancelled = true
        }
        waiting.clear()
    }
}
