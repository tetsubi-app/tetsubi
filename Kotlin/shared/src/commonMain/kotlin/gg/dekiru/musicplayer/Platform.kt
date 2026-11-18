package gg.dekiru.musicplayer

/** Which app is running: "Android" or "Linux" (or another desktop). */
expect val platformName: String

/** One line in the platform's log (Android: logcat at info level; desktop: standard error). */
internal expect fun logLine(tag: String, message: String)
