package gg.dekiru.musicplayer.data.settings

import android.content.Context
import gg.dekiru.musicplayer.data.network.AndroidNetworkMonitor
import gg.dekiru.musicplayer.downloads.WorkManagerDownloadRunner
import gg.dekiru.musicplayer.playback.PlayerEngine

/** Plain settings in a private SharedPreferences file. */
class AndroidPreferences(context: Context) : Preferences {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String?) {
        prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    }
}

fun androidEnvironment(context: Context, engine: PlayerEngine) = AppEnvironment(
    cacheDir = context.cacheDir.absolutePath,
    downloadsDir = java.io.File(context.filesDir, "Downloads").absolutePath,
    preferences = AndroidPreferences(context),
    playerEngine = engine,
    network = AndroidNetworkMonitor(context),
    downloadRunner = WorkManagerDownloadRunner(context),
)
