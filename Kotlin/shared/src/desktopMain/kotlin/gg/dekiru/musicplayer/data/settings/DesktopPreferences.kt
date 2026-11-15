package gg.dekiru.musicplayer.data.settings

import gg.dekiru.musicplayer.data.auth.FileSecretStore
import java.io.File

/** Plain settings in `$XDG_CONFIG_HOME/gg.dekiru.musicplayer/settings.properties` (same file format as the login store). */
class DesktopPreferences(file: File) : Preferences {
    private val store = FileSecretStore(file)
    override fun getString(key: String): String? = store.get(key)
    override fun putString(key: String, value: String?) = store.set(key, value)
}

/**
 * The folder name under each XDG directory: the app ID, not the app's name, so
 * a rename never moves a user's login, settings or downloads.
 */
internal const val DESKTOP_DIR_NAME = "gg.dekiru.musicplayer"

internal fun xdg(variable: String, fallback: String): File =
    File(System.getenv(variable)?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/" + fallback), DESKTOP_DIR_NAME)

fun desktopEnvironment() = AppEnvironment(
    cacheDir = xdg("XDG_CACHE_HOME", ".cache").absolutePath,
    downloadsDir = File(xdg("XDG_DATA_HOME", ".local/share"), "Downloads").absolutePath,
    preferences = DesktopPreferences(File(xdg("XDG_CONFIG_HOME", ".config"), "settings.properties")),
)
