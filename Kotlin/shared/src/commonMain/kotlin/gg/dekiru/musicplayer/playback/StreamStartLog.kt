package gg.dekiru.musicplayer.playback

import gg.dekiru.musicplayer.logLine

/**
 * A diagnostic line per track start on Android, for "it took ages to start
 * playing" reports: read it with `adb logcat -s Tetsubi`.
 *
 * The engine calls [begin] whenever it asks the player for an item's audio
 * from a fresh load (a new queue, moving to the next track, a skip, a seek,
 * a reconnect, Play after an error), then feeds it the player's events. Once
 * sound starts it logs one line, the times in ms from [begin]:
 *
 *     stream start: item=<id> kind=converted reason=skip response=412 firstAudio=880 sound=1034
 *
 * - `response`: the item's connection opened (the HTTP response arrived, or
 *   the downloaded file opened); `buffered` if that happened before [begin]
 *   (the player loaded the next track ahead), `-` if it never did (a seek
 *   inside audio already loaded). A load after [begin] replaces `buffered`
 *   (the player dropped what it had loaded ahead and asked again).
 * - `firstAudio`: the first audio of the item reached the decoder (its
 *   format was read); `buffered` or `-` likewise.
 * - `sound`: the audio output started moving; `paused` if the item was ready
 *   but the player paused.
 *
 * While sound hasn't started a `stream waiting:` line says what has happened,
 * [WAIT_EVERY_MILLIS] after [begin] and again every [WAIT_EVERY_MILLIS]; a
 * failure ends the run with `stream failed:`. Only the item's id and the kind
 * of stream are logged, never its address. `source` (the stream's address)
 * only tells this item's events from the others' (the next track loading).
 *
 * Pure bookkeeping over [now]; the engine calls it on one thread.
 */
class StreamStartLog(
    private val now: () -> Long,
    private val log: (String) -> Unit = { logLine("Tetsubi", it) },
) {
    enum class Kind(val label: String) { CONVERTED("converted"), DIRECT("direct"), LOCAL("local") }

    /** Why the item is loaded: a new queue, the queue moving on, a skip or seek by the user, a reconnect, Play after an error. */
    enum class Reason(val label: String) { PLAY("play"), NEXT("next"), SKIP("skip"), SEEK("seek"), RECONNECT("reconnect"), RESUME("resume") }

    private var itemId = ""
    private var kind = Kind.DIRECT
    private var reason = Reason.PLAY

    /** The item last begun, while pending or after: its later events (a reopen mid-track) are no start of anything. */
    private var source: String? = null

    /** Between [begin] and the line that ends the run. */
    var pending = false
        private set

    private var beganAt = 0L
    private var responseAt = UNSET
    private var firstAudioAt = UNSET
    private var playingAt = UNSET
    private var nextWaitAt = 0L

    /** Milestones of other items before they're begun (the next track loading ahead), by source; a few at most. */
    private val early = LinkedHashMap<String, Early>()

    private class Early(var response: Boolean = false, var firstAudio: Boolean = false)

    /**
     * The engine asked for `source`'s audio (item `itemId`). A run still
     * pending is dropped. `alreadyPlaying`: the player moved into it while
     * playing (the queue moving on). If its audio had already reached the
     * decoder the sound is gapless: logged now with `sound=0`. If not, the
     * player may still stop to load it: playing counts from now unless it
     * stops ([stoppedPlaying]). Another reconnect try for the same item
     * carries on the run: the times count from the first try.
     */
    fun begin(itemId: String, kind: Kind, reason: Reason, source: String, alreadyPlaying: Boolean = false) {
        if (pending && reason == Reason.RECONNECT && this.reason == Reason.RECONNECT && itemId == this.itemId) {
            this.source = source
            return
        }
        this.itemId = itemId
        this.kind = kind
        this.reason = reason
        this.source = source
        pending = true
        beganAt = now()
        nextWaitAt = beganAt + WAIT_EVERY_MILLIS
        val before = early.remove(source)
        responseAt = if (before?.response == true) BUFFERED else UNSET
        firstAudioAt = if (before?.firstAudio == true) BUFFERED else UNSET
        playingAt = UNSET
        if (alreadyPlaying) {
            if (firstAudioAt == BUFFERED) finish(sound(beganAt)) else playingAt = beganAt
        }
    }

    /** The connection for `source` opened (HTTP response, or file open) at `at`. */
    fun responded(source: String, at: Long = now()) {
        if (isPending(source)) {
            if (responseAt == UNSET || responseAt == BUFFERED) responseAt = at
        } else if (source != this.source) {
            earlyFor(source).response = true
        }
    }

    /** `source`'s first audio reached the decoder (its format was read). */
    fun firstAudio(source: String, at: Long = now()) {
        if (isPending(source)) {
            if (firstAudioAt == UNSET || firstAudioAt == BUFFERED) firstAudioAt = at
        } else if (source != this.source) {
            earlyFor(source).firstAudio = true
        }
    }

    /** The player says it's playing; sound should follow within moments ([soundStarted]). */
    fun playing(at: Long = now()) {
        if (pending && playingAt == UNSET) playingAt = at
    }

    /** The player stopped playing (to load, or paused) before sound was confirmed. */
    fun stoppedPlaying() {
        if (pending) playingAt = UNSET
    }

    /** The audio output started moving: the line. */
    fun soundStarted(at: Long = now()) {
        if (pending) finish(sound(at))
    }

    /** The item is ready but the player is paused: there's no wait to measure past this. */
    fun readyWhilePaused() {
        if (pending) finish("paused")
    }

    /** Playback failed (`error`: the player's error code name) and won't be tried again by itself. */
    fun failed(error: String) {
        if (!pending) return
        pending = false
        log("stream failed: ${fields()} after=${now() - beganAt} error=$error")
    }

    /** Stopped, or playback moved to a Cast device: nothing to report. */
    fun cancel() {
        pending = false
        source = null
        early.clear()
    }

    /**
     * Called when [millisUntilCheck] has passed: logs a `stream waiting:`
     * line when one is due (not while `paused`; `state` is the player's,
     * e.g. buffering), and the start line if the player has been playing a
     * while with no word from the audio output. Returns [millisUntilCheck].
     */
    fun check(paused: Boolean, state: String): Long? {
        if (!pending) return null
        val t = now()
        if (playingAt != UNSET && t - playingAt >= SOUND_GRACE_MILLIS) {
            finish(sound(playingAt))
            return null
        }
        if (t >= nextWaitAt) {
            if (!paused) log("stream waiting: ${fields()} after=${t - beganAt} state=$state")
            while (nextWaitAt <= t) nextWaitAt += WAIT_EVERY_MILLIS
        }
        return millisUntilCheck()
    }

    /** How long until [check] should be called, or null when nothing is pending. */
    fun millisUntilCheck(): Long? {
        if (!pending) return null
        val due = if (playingAt != UNSET) minOf(nextWaitAt, playingAt + SOUND_GRACE_MILLIS) else nextWaitAt
        return (due - now()).coerceAtLeast(0)
    }

    private fun isPending(source: String) = pending && source == this.source

    private fun earlyFor(source: String): Early = early.getOrPut(source) {
        if (early.size >= EARLY_LIMIT) early.remove(early.keys.first())
        Early()
    }

    private fun sound(at: Long) = (at - beganAt).coerceAtLeast(0).toString()

    private fun finish(sound: String) {
        pending = false
        log("stream start: ${fields()} sound=$sound")
    }

    private fun fields() =
        "item=$itemId kind=${kind.label} reason=${reason.label} response=${milestone(responseAt)} firstAudio=${milestone(firstAudioAt)}"

    private fun milestone(at: Long) = when (at) {
        UNSET -> "-"
        BUFFERED -> "buffered"
        else -> (at - beganAt).coerceAtLeast(0).toString()
    }

    companion object {
        /** The first `stream waiting:` line comes this long after [begin], and again as often. */
        const val WAIT_EVERY_MILLIS = 10_000L

        /** How long after "playing" the audio output may take to say it moved before that time counts as the sound. */
        const val SOUND_GRACE_MILLIS = 1_000L

        private const val EARLY_LIMIT = 4
        private const val UNSET = Long.MIN_VALUE
        private const val BUFFERED = Long.MIN_VALUE + 1

        /** The kind of stream at `url`: a downloaded file, converted by the server, or the original file. */
        fun kindOf(url: String): Kind = when {
            url.startsWith("file:") || url.startsWith("content:") || url.startsWith("/") -> Kind.LOCAL
            StreamUrls.isConverted(url) -> Kind.CONVERTED
            else -> Kind.DIRECT
        }
    }
}
