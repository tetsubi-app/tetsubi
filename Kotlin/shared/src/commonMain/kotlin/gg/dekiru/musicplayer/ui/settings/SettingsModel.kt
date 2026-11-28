package gg.dekiru.musicplayer.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import gg.dekiru.musicplayer.AppInfo
import gg.dekiru.musicplayer.data.update.UpdateCheck
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.downloads.DownloadSettings
import gg.dekiru.musicplayer.playback.AudioFormat
import gg.dekiru.musicplayer.playback.PlaybackSettings
import gg.dekiru.musicplayer.playback.PlayerController
import gg.dekiru.musicplayer.playback.StreamingQuality

/** The Settings tab's texts, worded as iOS `SettingsView`. */
object SettingsText {
    const val MANIAC_TITLE = "Maniac Mode"
    const val MANIAC_CAPTION = "No skips or shuffles, a front to back listen. Tettō tetsubi."

    const val SERVER = "Server"
    const val NO_SERVER = "No server configured."
    const val SIGN_OUT = "Sign Out"

    const val DOWNLOADS = "Downloads"
    const val DEFAULT_FORMAT = "Default format"
    const val ORIGINAL_FORMAT = "Original (as served)"
    const val DOWNLOAD_OVER_MOBILE = "Download over mobile data"
    const val DOWNLOADS_FOOTER = "When off, downloads wait for Wi‑Fi."
    const val DISK_USAGE = "Disk usage"

    const val PLAYBACK = "Playback"
    const val STREAMING_OVER_MOBILE = "Streaming over mobile data"
    const val NORMALISE = "Normalise volume"
    const val EQUALISER = "Equaliser"
    const val PLAYBACK_MOBILE_FOOTER =
        "On mobile data, tracks stream at the chosen quality (from the next track); Original (as served) streams " +
            "the files as they are. "
    const val PLAYBACK_FOOTER =
        "Normalise volume evens out loud and quiet tracks using Jellyfin's loudness data; it can " +
            "only turn tracks down, and downloaded tracks play at full volume."

    /** The Playback footer: the mobile-data sentence only where there is mobile data. */
    fun playbackFooter(platformName: String): String =
        if (showsMobileData(platformName)) PLAYBACK_MOBILE_FOOTER + PLAYBACK_FOOTER else PLAYBACK_FOOTER

    // iOS adds "Gapless playback is on unless the equaliser or visualiser is in use." Not on Android:
    // the effects run inside ExoPlayer's audio output, so gapless stays on with them.

    const val STORAGE = "Storage"
    const val ART_CACHE = "Album art cache"
    const val CLEAR_IMAGE_CACHE = "Clear image cache"

    // Android and Linux only (iOS and the Mac are updated through the App Store).
    const val UPDATES = "Updates"
    const val CHECK_FOR_UPDATES = "Check for updates when Tetsubi opens"
    const val CHECK_NOW = "Check now"
    const val UPDATES_FOOTER =
        "Once a day Tetsubi asks GitHub whether a newer release exists. Nothing about you is sent and nothing is downloaded."

    const val ABOUT = "About"
    const val APP_NAME = "Tetsubi"
    const val TAGLINE = "A music player for Jellyfin"
    const val VERSION = "Version"
    const val ARTIST_INFORMATION = "Artist information"
    const val ARTIST_SOURCES = "MusicBrainz, Wikipedia"
    const val COPYRIGHT = "© 2026 DEKIRU Ltd. All rights reserved."

    /** Shown above the copyright in About: the header, then the names on one line (as iOS). */
    const val QA_TEAM = "QA team"
    val QA_TEAM_NAMES = listOf("Matt Evans", "Eloy Smith Rodriguez", "Adam Buchanan")
    /** The names on one line, separated by bullets (as iOS). */
    val QA_TEAM_LINE: String get() = QA_TEAM_NAMES.joinToString(" \u2022 ")

    // "Made with AI": Jellyfin's community rules ask apps built mainly with an AI to say so clearly.
    val ABOUT_FOOTER = """
        $COPYRIGHT
        Made with AI.

        Tetsubi isn't made by, or affiliated with, the Jellyfin project. Jellyfin is free software from jellyfin.org.

        Artist facts come from MusicBrainz and Wikidata (CC0). Artist introductions come from Wikipedia and are available under the Creative Commons Attribution-ShareAlike 4.0 licence; each album page links to its source.

        DEKIRU Sans — based on IBM Plex Sans by IBM Corp., SIL Open Font License 1.1.
    """.trimIndent()

    /** "Download over mobile data", "Streaming over mobile data" and their footer sentences: Android only (the desktop has no metered networks). */
    fun showsMobileData(platformName: String): Boolean = platformName == "Android"

    /** The version row: the version name and build number, "1.0 (5)". */
    val version: String get() = UpdateCheck.displayVersion(AppInfo.VERSION, AppInfo.BUILD)

    /** Where the login is kept, under the server address (iOS names the keychain). */
    fun savedIn(platformName: String): String =
        if (platformName == "Android") "Saved encrypted with the Android Keystore" else "Saved in a private file"

    /** The default-format menu: the original first (null), then every format, as iOS. */
    val formatOptions: List<Pair<AudioFormat?, String>> =
        listOf<Pair<AudioFormat?, String>>(null to ORIGINAL_FORMAT) + AudioFormat.entries.map { it to it.displayName }

    fun formatLabel(format: AudioFormat?): String = format?.displayName ?: ORIGINAL_FORMAT

    /** Qualities on offer while "Streaming over mobile data" is on (never the original). */
    val qualityOptions: List<StreamingQuality> = StreamingQuality.entries
}

/**
 * The Settings tab's state and actions (as iOS `SettingsView`'s bindings).
 * Values are read once and kept in Compose state; each change writes the
 * same setting iOS writes. `player`/`downloads` are null when signed out.
 */
class SettingsModel(
    private val playback: PlaybackSettings,
    private val downloadSettings: DownloadSettings,
    private val player: () -> PlayerController?,
    private val downloads: () -> DownloadManager?,
) {
    var maniacMode by mutableStateOf(player()?.maniacMode ?: playback.maniacMode)
        private set
    var cellularQuality by mutableStateOf(playback.cellularQuality)
        private set
    var normalizeVolume by mutableStateOf(playback.normalizeVolume)
        private set
    var downloadOverCellular by mutableStateOf(downloadSettings.allowCellular)
        private set
    var defaultFormat by mutableStateOf(downloadSettings.defaultFormat)
        private set

    /** On: shuffle and repeat go off (through the player, as iOS `setManiacMode`). */
    fun changeManiacMode(on: Boolean) {
        maniacMode = on
        player()?.changeManiacMode(on) ?: run { playback.maniacMode = on }
    }

    fun changeCellularQuality(quality: StreamingQuality) {
        cellularQuality = quality
        playback.cellularQuality = quality
    }

    fun changeNormalizeVolume(on: Boolean) {
        normalizeVolume = on
        playback.normalizeVolume = on
        player()?.applyNormalization()
    }

    /** Waiting downloads start (or stop) at once through the manager. */
    fun changeDownloadOverCellular(on: Boolean) {
        downloadOverCellular = on
        downloads()?.setAllowCellular(on) ?: run { downloadSettings.allowCellular = on }
    }

    fun changeDefaultFormat(format: AudioFormat?) {
        defaultFormat = format
        downloadSettings.defaultFormat = format
    }
}
