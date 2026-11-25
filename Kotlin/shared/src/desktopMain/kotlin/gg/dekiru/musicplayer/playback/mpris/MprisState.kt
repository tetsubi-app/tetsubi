package gg.dekiru.musicplayer.playback.mpris

import gg.dekiru.musicplayer.playback.RepeatMode
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.types.Variant

/** The playing track as MPRIS shows it (media keys, the desktop's now-playing panel). */
data class MprisTrack(
    /** The queue entry (unique in the queue, where a track may be there twice). */
    val entryId: String,
    val title: String,
    val artist: String,
    val album: String,
    val lengthMicros: Long,
    /** A `file://` URI of the cover, once it's on disk; null until then. */
    val artUrl: String? = null,
)

enum class MprisStatus(val wire: String) { PLAYING("Playing"), PAUSED("Paused"), STOPPED("Stopped") }

/** What the player looks like to MPRIS. Position is asked for separately (it changes all the time and isn't signalled). */
data class MprisState(
    val track: MprisTrack? = null,
    val status: MprisStatus = MprisStatus.STOPPED,
    val repeat: RepeatMode = RepeatMode.OFF,
    val shuffle: Boolean = false,
    /** Off in Maniac Mode: no skipping, seeking, shuffle or repeat from outside the app either. */
    val canSkip: Boolean = true,
    /** Whether there's an entry after this one (Repeat All counts). */
    val hasNext: Boolean = false,
    /** Whether Play would do anything: a track is queued and libmpv is there. */
    val canPlay: Boolean = false,
)

/** The MPRIS names and values, kept apart from D-Bus plumbing so they can be tested. */
object Mpris {
    const val ROOT_INTERFACE = "org.mpris.MediaPlayer2"
    const val PLAYER_INTERFACE = "org.mpris.MediaPlayer2.Player"
    const val OBJECT_PATH = "/org/mpris/MediaPlayer2"

    /**
     * The bus name. MPRIS names players by app, so this is the one name here
     * that says "tetsubi" (what `playerctl -p` takes); nothing is stored
     * under it, so a rename would only change what playerctl shows.
     */
    const val BUS_NAME = "org.mpris.MediaPlayer2.tetsubi"

    /** "No track" in the spec. */
    const val NO_TRACK = "/org/mpris/MediaPlayer2/TrackList/NoTrack"

    /** A D-Bus object path for a queue entry (path elements allow only `[A-Za-z0-9_]`). */
    fun trackPath(entryId: String): String =
        "/gg/dekiru/musicplayer/entry/" + entryId.map { if (it.isLetterOrDigit() && it.code < 128) it else '_' }.joinToString("").ifEmpty { "_" }

    fun loopStatus(mode: RepeatMode): String = when (mode) {
        RepeatMode.OFF -> "None"
        RepeatMode.ONE -> "Track"
        RepeatMode.ALL -> "Playlist"
    }

    fun repeatMode(loopStatus: String): RepeatMode? = when (loopStatus) {
        "None" -> RepeatMode.OFF
        "Track" -> RepeatMode.ONE
        "Playlist" -> RepeatMode.ALL
        else -> null
    }

    /** `Metadata`: empty with no track (the spec allows that), else track ID, length, title, artist, album and cover. */
    fun metadata(track: MprisTrack?): Map<String, Variant<*>> {
        if (track == null) return emptyMap()
        return buildMap {
            put("mpris:trackid", Variant(DBusPath(trackPath(track.entryId))))
            if (track.lengthMicros > 0) put("mpris:length", Variant(track.lengthMicros))
            put("xesam:title", Variant(track.title))
            if (track.artist.isNotEmpty()) {
                put("xesam:artist", Variant(listOf(track.artist), "as"))
                put("xesam:albumArtist", Variant(listOf(track.artist), "as"))
            }
            if (track.album.isNotEmpty()) put("xesam:album", Variant(track.album))
            track.artUrl?.let { put("mpris:artUrl", Variant(it)) }
        }
    }

    /** Every `org.mpris.MediaPlayer2.Player` property but `Position`. */
    fun playerProperties(state: MprisState): Map<String, Variant<*>> {
        val hasTrack = state.track != null
        val skip = hasTrack && state.canSkip
        return linkedMapOf(
            "PlaybackStatus" to Variant(state.status.wire),
            "LoopStatus" to Variant(loopStatus(state.repeat)),
            "Rate" to Variant(1.0),
            "Shuffle" to Variant(state.shuffle),
            "Metadata" to Variant(metadata(state.track), "a{sv}"),
            // The app's volume is normalisation's; the system's own volume is the one to change.
            "Volume" to Variant(1.0),
            "MinimumRate" to Variant(1.0),
            "MaximumRate" to Variant(1.0),
            "CanGoNext" to Variant(skip && state.hasNext),
            "CanGoPrevious" to Variant(skip),
            "CanPlay" to Variant(state.canPlay),
            "CanPause" to Variant(state.canPlay),
            "CanSeek" to Variant(skip && (state.track?.lengthMicros ?: 0) > 0),
            "CanControl" to Variant(true),
        )
    }

    /** The `org.mpris.MediaPlayer2` properties. `desktopEntry`: the .desktop file's name without `.desktop`, if known. */
    fun rootProperties(identity: String, desktopEntry: String?): Map<String, Variant<*>> = buildMap {
        put("CanQuit", Variant(true))
        put("CanRaise", Variant(true))
        put("HasTrackList", Variant(false))
        put("Identity", Variant(identity))
        desktopEntry?.let { put("DesktopEntry", Variant(it)) }
        put("SupportedUriSchemes", Variant(emptyList<String>(), "as"))
        put("SupportedMimeTypes", Variant(emptyList<String>(), "as"))
    }

    /** The properties that differ between `old` and `new` (for `PropertiesChanged`). */
    fun changed(old: Map<String, Variant<*>>, new: Map<String, Variant<*>>): Map<String, Variant<*>> =
        new.filter { (name, value) -> old[name] != value }
}
