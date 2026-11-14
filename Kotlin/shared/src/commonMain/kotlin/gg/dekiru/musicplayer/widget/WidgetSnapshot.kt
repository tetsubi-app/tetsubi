package gg.dekiru.musicplayer.widget

import gg.dekiru.musicplayer.playback.PlaybackTrack
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * What the home-screen widget shows, as the app last saw it (the same
 * fields as iOS's widget snapshot). The widget never talks to the server:
 * the app writes this whenever the track, play/pause, Maniac Mode or the
 * sign-in changes, with the art as a small picture next to it.
 *
 * After the app was closed the last track stays, so Play can start its
 * album again ([discIds]).
 */
@Serializable
data class WidgetSnapshot(
    /** Signed out: the widget asks to open Tetsubi and sign in. */
    val signedIn: Boolean = false,
    val trackTitle: String? = null,
    val artist: String? = null,
    val album: String? = null,
    /** The album shown (the merged entry for a multi-disc album). */
    val albumId: String? = null,
    /** Every disc of the album, in order: what Play starts again after the app was closed. */
    val discIds: List<String> = emptyList(),
    val isPlaying: Boolean = false,
    /** Maniac Mode: no previous or next. */
    val maniacMode: Boolean = false,
    /** The art's key colour as 0xRRGGBB; null for Tetsubi's usual blue. */
    val accentRGB: Int? = null,
    /** The saved art's file name, next to the snapshot; null when there's no picture. */
    val artFile: String? = null,
    /** The whole album or playlist, in seconds (Now Playing's `albumLength`); 0 when not known. */
    val queueSeconds: Double = 0.0,
    /** What's left of the queue at [updatedAt], in seconds (Now Playing's `albumRemaining`). */
    val remainingSeconds: Double = 0.0,
    /** When it was written, in milliseconds since 1970. */
    val updatedAt: Long = 0,
) {
    /** The discs Play starts again: the album's, or the track's own album when that's all there is. */
    val replayDiscIds: List<String>
        get() = discIds.ifEmpty { listOfNotNull(albumId?.takeIf { it.isNotEmpty() }) }

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        /** Reads a saved snapshot; null when there's none or it can't be read (the widget then shows "Nothing playing"). */
        fun decode(text: String?): WidgetSnapshot? {
            if (text.isNullOrBlank()) return null
            return try {
                json.decodeFromString(serializer(), text)
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        /**
         * The snapshot to write for what the app is doing now. With nothing
         * loaded (the app just started, or the queue was cleared) the last
         * track stays, paused, so the widget can still offer it; signing out
         * forgets it. [accentRGB] and [artFile] belong to [live]'s track and
         * are ignored when there's none.
         *
         * The time left is worked out for `now`: while playing, what was
         * left when [live] was read less the time since. A kept track has
         * its whole album left, as Play starts it again from the start.
         */
        fun next(previous: WidgetSnapshot?, live: WidgetLive, accentRGB: Int?, artFile: String?, now: Long): WidgetSnapshot {
            val track = live.track
            return when {
                !live.signedIn -> WidgetSnapshot(signedIn = false, maniacMode = live.maniacMode, updatedAt = now)
                track == null -> (previous?.takeIf { it.signedIn } ?: WidgetSnapshot(signedIn = true)).let {
                    it.copy(isPlaying = false, maniacMode = live.maniacMode, remainingSeconds = it.queueSeconds, updatedAt = now)
                }
                else -> WidgetSnapshot(
                    signedIn = true,
                    trackTitle = track.title,
                    artist = track.artist,
                    album = track.album,
                    albumId = track.albumId,
                    discIds = live.discIds,
                    isPlaying = live.isPlaying,
                    maniacMode = live.maniacMode,
                    accentRGB = accentRGB,
                    artFile = artFile,
                    queueSeconds = live.queueSeconds,
                    remainingSeconds = live.remainingAt(now),
                    updatedAt = now,
                )
            }
        }
    }
}

/**
 * What the app is doing, as far as the widget cares. The time left changes
 * with every tick of the play position, so values are compared with
 * [sameForWidget] rather than `==`.
 */
data class WidgetLive(
    val signedIn: Boolean,
    val track: PlaybackTrack? = null,
    /** The playing album's discs (empty for a playlist's queue). */
    val discIds: List<String> = emptyList(),
    val isPlaying: Boolean = false,
    val maniacMode: Boolean = false,
    /** The whole queue, in seconds (Now Playing's `albumLength`). */
    val queueSeconds: Double = 0.0,
    /** What was left of the queue at [readAt], in seconds (Now Playing's `albumRemaining`). */
    val remainingSeconds: Double = 0.0,
    /** When [remainingSeconds] was read, in milliseconds since 1970 (0: not known). */
    val readAt: Long = 0,
) {
    /** The picture to show for [track]: the downloaded file when there is one, else the server's item. */
    val artKey: String? get() = track?.let { it.localArtPath ?: it.artItemId }

    /** What's left at `now`: less the time since [readAt] while playing. */
    fun remainingAt(now: Long): Double {
        val elapsed = if (isPlaying && readAt > 0) (now - readAt).coerceAtLeast(0) / 1000.0 else 0.0
        return (remainingSeconds - elapsed).coerceAtLeast(0.0)
    }

    /**
     * Whether the widget would look the same for both: everything equal
     * except the time left, and that only moved the finish time by less
     * than [FINISH_SLACK_MILLIS] (playing: as the clock goes on; paused:
     * not at all, unless seeking). A seek, play/pause or a new track redraws it.
     */
    fun sameForWidget(other: WidgetLive): Boolean {
        if (copy(remainingSeconds = 0.0, readAt = 0) != other.copy(remainingSeconds = 0.0, readAt = 0)) return false
        val moved = if (isPlaying) {
            (readAt + remainingSeconds * 1000) - (other.readAt + other.remainingSeconds * 1000)
        } else {
            (remainingSeconds - other.remainingSeconds) * 1000
        }
        return kotlin.math.abs(moved) < FINISH_SLACK_MILLIS
    }

    companion object {
        /** A finish time that moved less than this isn't worth redrawing the widget. */
        const val FINISH_SLACK_MILLIS = 5_000.0
    }
}
