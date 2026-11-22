package gg.dekiru.musicplayer.data.settings

import gg.dekiru.musicplayer.data.network.NetworkMonitor
import gg.dekiru.musicplayer.downloads.DownloadRunner
import gg.dekiru.musicplayer.playback.PlayerEngine
import gg.dekiru.musicplayer.playback.UnsupportedPlayerEngine

/**
 * Small plain settings (the iOS app's UserDefaults). Android:
 * SharedPreferences; desktop: `$XDG_CONFIG_HOME/gg.dekiru.musicplayer/settings.properties`.
 * (DataStore may replace this when Settings arrives in A5.)
 */
interface Preferences {
    fun getString(key: String): String?

    /** `null` removes the key. */
    fun putString(key: String, value: String?)
}

class InMemoryPreferences : Preferences {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String): String? = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/** What the shared code needs from the platform. */
class AppEnvironment(
    /** A folder for caches the system may clear (album art, artist info). */
    val cacheDir: String,
    /** Downloaded albums: Android `filesDir/Downloads`, Linux `$XDG_DATA_HOME/gg.dekiru.musicplayer/Downloads`. */
    val downloadsDir: String,
    val preferences: Preferences,
    /** The app's one player (Media3 on Android, libmpv on Linux). */
    val playerEngine: PlayerEngine = UnsupportedPlayerEngine(),
    /** Online and metered (Android: `ConnectivityManager`; the desktop: always online, unmetered). */
    val network: NetworkMonitor = NetworkMonitor(),
    /** Keeps downloads going (Android: WorkManager). Null: they run in the app's scope. */
    val downloadRunner: DownloadRunner? = null,
    /**
     * The app's own data (the saved queue): Android `filesDir`, Linux
     * `$XDG_DATA_HOME/gg.dekiru.musicplayer`. Defaults to the downloads folder's parent.
     */
    val dataDir: String = downloadsDir.trimEnd('/').substringBeforeLast('/'),
) {
    /** Whether the network is metered (mobile data, hotspot). */
    val isExpensiveNetwork: () -> Boolean = { network.isMetered }

    /** Whether there's a network at all. */
    val isOnline: () -> Boolean = { network.isOnline }
}
