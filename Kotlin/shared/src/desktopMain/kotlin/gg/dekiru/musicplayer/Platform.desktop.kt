package gg.dekiru.musicplayer

actual val platformName: String = System.getProperty("os.name")?.takeIf { it.isNotBlank() } ?: "Desktop"
