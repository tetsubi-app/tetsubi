package gg.dekiru.musicplayer

/** How the app names itself to the Jellyfin server. */
object AppInfo {
    /** `Client="…"` in the server header; the Jellyfin dashboard shows it. */
    const val CLIENT_NAME = "Tetsubi"

    /** Kept in step with `versionName` in androidApp and the desktop package. */
    const val VERSION = "0.1.0"
}
