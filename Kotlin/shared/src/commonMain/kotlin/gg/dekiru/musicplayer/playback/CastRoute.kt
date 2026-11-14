package gg.dekiru.musicplayer.playback

import kotlinx.coroutines.flow.StateFlow

/**
 * Google Cast as the screens see it (Android with Play services). The
 * player itself moves to the Cast device and back inside the engine; this
 * is what Now Playing and Settings show.
 */
interface CastRoute {
    /** Whether there are Cast devices on the network (Cast's `NO_DEVICES_AVAILABLE` is false). */
    val devicesAvailable: StateFlow<Boolean>

    /** The Cast device playing, e.g. "Living Room TV"; null when not casting. */
    val deviceName: StateFlow<String?>

    companion object {
        /**
         * Whether Now Playing shows the Cast button: online (the TV streams
         * from the server), and either casting already or with a Cast device
         * to pick.
         */
        fun showsButton(online: Boolean, devicesAvailable: Boolean, casting: Boolean): Boolean =
            online && (casting || devicesAvailable)

        /** The line under the album while casting (as iOS's AirPlay route name). */
        fun playingOn(deviceName: String): String = "Playing on $deviceName"
    }
}
