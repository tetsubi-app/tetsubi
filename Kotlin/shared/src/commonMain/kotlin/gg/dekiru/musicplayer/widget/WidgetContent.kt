package gg.dekiru.musicplayer.widget

import gg.dekiru.musicplayer.playback.formatAlbumDuration

/**
 * What the home-screen widget shows, worked out from the saved
 * [WidgetSnapshot] (the same choices as the iOS widget).
 */
sealed interface WidgetContent {

    /** No track to show: the Tetsubi mark and [text]; tapping opens the app. */
    data class Message(val text: String) : WidgetContent

    /** A track: art, title, artist, album and the buttons. */
    data class Track(
        val title: String,
        val artist: String,
        val album: String,
        /** Shows Pause (rather than Play). */
        val isPlaying: Boolean,
        /** Previous and Next (not in Maniac Mode). */
        val showSkips: Boolean,
        /** The art's key colour as 0xRRGGBB, or Tetsubi's usual blue. */
        val accentRGB: Int,
        val artFile: String?,
        /** The whole album or playlist, in seconds; 0 when not known (no times shown). */
        val queueSeconds: Double = 0.0,
        /** When the queue finishes, in milliseconds since 1970; null when it can't be said (the app was closed). */
        val finishAt: Long? = null,
    ) : WidgetContent {
        /** What a screen reader says for the widget: "<title> by <artist>". */
        val description: String get() = if (artist.isBlank()) title else "$title by $artist"

        /**
         * The line of times, in Now Playing's words, with the finish time
         * written by `clock` (the device's 12/24-hour format). Medium:
         * "Duration: 51m 06s   Ends: 13:42"; small: only
         * "Ends: 13:42". Without a finish time, the duration alone; null when
         * the length isn't known.
         */
        fun times(medium: Boolean, clock: (Long) -> String): String? {
            if (queueSeconds <= 0) return null
            val duration = "Duration: ${formatAlbumDuration(queueSeconds)}"
            val finish = finishAt?.let { "Ends: ${clock(it)}" } ?: return duration
            return if (medium) "$duration   $finish" else finish
        }

        /** The small widget's two lines, when it has room: "Duration: …" (null when the length isn't known) and the finish time (null when out of date or unknown). */
        fun timeLines(clock: (Long) -> String): Pair<String?, String?> {
            if (queueSeconds <= 0) return null to null
            return "Duration: ${formatAlbumDuration(queueSeconds)}" to finishAt?.let { "Ends: ${clock(it)}" }
        }
    }

    companion object {
        const val NOTHING_PLAYING = "Nothing playing"
        const val SIGN_IN = "Open Tetsubi to sign in"

        /** Tetsubi's usual blue (0xRRGGBB), for art with no key colour. */
        const val TETSUBI_BLUE = 0x039FD6

        /**
         * What to show for `snapshot` at `now` (milliseconds since 1970).
         * `playerAlive`: whether the app's player is running in this process;
         * when it isn't (the app was closed), a snapshot that says "playing"
         * is out of date and shows Play, and no finish time.
         */
        fun of(snapshot: WidgetSnapshot?, playerAlive: Boolean, now: Long): WidgetContent {
            if (snapshot == null) return Message(NOTHING_PLAYING)
            if (!snapshot.signedIn) return Message(SIGN_IN)
            val title = snapshot.trackTitle?.takeIf { it.isNotBlank() } ?: return Message(NOTHING_PLAYING)
            return Track(
                title = title,
                artist = snapshot.artist.orEmpty(),
                album = snapshot.album.orEmpty(),
                isPlaying = snapshot.isPlaying && playerAlive,
                showSkips = !snapshot.maniacMode,
                accentRGB = snapshot.accentRGB ?: TETSUBI_BLUE,
                artFile = snapshot.artFile,
                queueSeconds = snapshot.queueSeconds.coerceAtLeast(0.0),
                finishAt = finishAt(snapshot, playerAlive, now),
            )
        }

        /**
         * When the queue finishes. Playing: the time left from when the
         * snapshot was written. Paused: from now, so it moves with the clock
         * (the app redraws the widget once a minute while paused). Not said
         * when the snapshot is out of date (the app was closed) or the length
         * isn't known.
         */
        fun finishAt(snapshot: WidgetSnapshot, playerAlive: Boolean, now: Long): Long? {
            if (!playerAlive || snapshot.queueSeconds <= 0) return null
            val left = (snapshot.remainingSeconds.coerceAtLeast(0.0) * 1000).toLong()
            return (if (snapshot.isPlaying) snapshot.updatedAt else now) + left
        }

        /** How long until the clock's next minute (and a little over), for redrawing a paused widget's finish time. */
        fun millisToNextMinute(now: Long): Long = 60_000 - now.mod(60_000L) + 250
    }
}

/** What the widget's Play/Pause button does. */
sealed interface WidgetPlay {
    /** The app has a queue: play or pause it. */
    data object Toggle : WidgetPlay

    /** No queue (the app was closed): start the last album again from the start. */
    data class Replay(val discIds: List<String>) : WidgetPlay

    /** Nothing to play: open the app. */
    data object OpenApp : WidgetPlay

    companion object {
        fun of(hasQueue: Boolean, snapshot: WidgetSnapshot?): WidgetPlay = when {
            hasQueue -> Toggle
            snapshot == null || !snapshot.signedIn -> OpenApp
            else -> snapshot.replayDiscIds.takeIf { it.isNotEmpty() }?.let(::Replay) ?: OpenApp
        }
    }
}
