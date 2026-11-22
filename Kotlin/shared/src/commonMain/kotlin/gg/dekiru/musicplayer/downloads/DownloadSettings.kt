package gg.dekiru.musicplayer.downloads

import gg.dekiru.musicplayer.data.settings.Preferences
import gg.dekiru.musicplayer.playback.AudioFormat

/** Download settings, same keys and defaults as iOS. Settings screen: A5. */
class DownloadSettings(private val preferences: Preferences) {

    /** Format for new downloads; null = the original (the default). Stored as "flac", … or "original". */
    var defaultFormat: AudioFormat?
        get() = DownloadManifest.formatFromName(preferences.getString("downloads.defaultFormat"))
        set(value) = preferences.putString("downloads.defaultFormat", DownloadManifest.formatName(value))

    /**
     * "Download over mobile data". Default off (a saved choice is kept); when
     * off, downloads wait for Wi‑Fi (an unmetered network) and start by themselves.
     */
    var allowCellular: Boolean
        get() = preferences.getString("downloads.allowCellular")?.toBooleanStrictOrNull() ?: false
        set(value) = preferences.putString("downloads.allowCellular", value.toString())

    /**
     * Whether downloaded FLAC files from before [FlacRepair] have been looked
     * at once (Android and Linux only; not a user setting).
     */
    var flacTotalsRepaired: Boolean
        get() = preferences.getString("downloads.flacTotalsRepaired") == "true"
        set(value) = preferences.putString("downloads.flacTotalsRepaired", value.toString())
}
