package gg.dekiru.musicplayer.downloads

import gg.dekiru.musicplayer.data.settings.Preferences
import gg.dekiru.musicplayer.playback.AudioFormat

/** Download settings, same keys and defaults as iOS. Settings screen: A5. */
class DownloadSettings(private val preferences: Preferences) {

    /** Format for new downloads; null = the original (the default). Stored as "flac", … or "original". */
    var defaultFormat: AudioFormat?
        get() = DownloadManifest.formatFromName(preferences.getString("downloads.defaultFormat"))
        set(value) = preferences.putString("downloads.defaultFormat", DownloadManifest.formatName(value))

    /** "Download over mobile data". Default on; when off, downloads wait for Wi‑Fi. */
    var allowCellular: Boolean
        get() = preferences.getString("downloads.allowCellular")?.toBooleanStrictOrNull() ?: true
        set(value) = preferences.putString("downloads.allowCellular", value.toString())
}
