package gg.dekiru.musicplayer

actual val platformName: String = System.getProperty("os.name")?.takeIf { it.isNotBlank() } ?: "Desktop"

internal actual fun logLine(tag: String, message: String) {
    System.err.println("$tag: $message")
}
