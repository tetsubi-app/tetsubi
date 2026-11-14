package gg.dekiru.musicplayer.data.settings

import gg.dekiru.musicplayer.data.auth.FileSecretStore
import java.io.File

/** Plain settings in `$XDG_CONFIG_HOME/tetsubi/settings.properties` (same file format as the login store). */
class DesktopPreferences(file: File) : Preferences {
    private val store = FileSecretStore(file)
    override fun getString(key: String): String? = store.get(key)
    override fun putString(key: String, value: String?) = store.set(key, value)
}

private fun xdg(variable: String, fallback: String): File =
    File(System.getenv(variable)?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/" + fallback), "tetsubi")

fun desktopEnvironment() = AppEnvironment(
    cacheDir = xdg("XDG_CACHE_HOME", ".cache").absolutePath,
    downloadsDir = File(xdg("XDG_DATA_HOME", ".local/share"), "Downloads").absolutePath,
    preferences = DesktopPreferences(File(xdg("XDG_CONFIG_HOME", ".config"), "settings.properties")),
)
