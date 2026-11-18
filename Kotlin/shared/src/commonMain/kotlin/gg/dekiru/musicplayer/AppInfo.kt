package gg.dekiru.musicplayer

/** How the app names itself to the Jellyfin server. */
object AppInfo {
    /** `Client="…"` in the server header; the Jellyfin dashboard shows it. */
    const val CLIENT_NAME = "Tetsubi"

    /**
     * The version name, e.g. "1.0" (`versionName` on Android). It comes from
     * `tetsubi.versionName` in Kotlin/gradle.properties through the generated
     * [AppVersion], so Android, Linux and the shared code always agree.
     */
    const val VERSION = AppVersion.NAME

    /** The build number, e.g. 5 (`versionCode` on Android; `tetsubi.versionCode`). */
    const val BUILD = AppVersion.BUILD
}
