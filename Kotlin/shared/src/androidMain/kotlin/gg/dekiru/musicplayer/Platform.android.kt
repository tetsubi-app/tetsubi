package gg.dekiru.musicplayer

import android.util.Log

actual val platformName: String = "Android"

// runCatching: android.util.Log isn't there in the host (JVM) tests.
internal actual fun logLine(tag: String, message: String) {
    runCatching { Log.i(tag, message) }
}
