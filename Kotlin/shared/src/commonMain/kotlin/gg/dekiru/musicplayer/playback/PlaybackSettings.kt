package gg.dekiru.musicplayer.playback

import gg.dekiru.musicplayer.data.settings.Preferences
import kotlin.math.pow

/** Bitrate cap for streaming on mobile data. `ORIGINAL` streams the file as is. */
enum class StreamingQuality(val bitrate: Int?, val displayName: String) {
    ORIGINAL(null, "Original (as served)"),
    KBPS320(320_000, "High (320 kbps AAC)"),
    KBPS256(256_000, "Normal (256 kbps AAC)"),
    KBPS128(128_000, "Data saver (128 kbps AAC)"),
}

/** Playback settings (as `PlaybackSettings` on iOS, same keys and defaults). Settings screen: A5. */
class PlaybackSettings(private val preferences: Preferences) {

    private fun bool(key: String, default: Boolean) = preferences.getString(key)?.toBooleanStrictOrNull() ?: default
    private fun setBool(key: String, value: Boolean) = preferences.putString(key, value.toString())

    /** Maniac Mode: albums play start to finish, in order, once. No skip, shuffle, repeat or scrubbing. Default off. */
    var maniacMode: Boolean
        get() = bool("playback.maniacMode", false)
        set(value) = setBool("playback.maniacMode", value)

    /**
     * "Streaming over mobile data": the quality on metered networks. Default
     * Normal (256 kbps). The old on/off switch (`playback.reduceOnMobileData`)
     * is gone; switched off, it reads as Original (as served), which is what it did.
     */
    var cellularQuality: StreamingQuality
        get() {
            if (preferences.getString(LEGACY_SWITCH) == "false") return StreamingQuality.ORIGINAL
            return preferences.getString("playback.cellularQuality")?.let { name -> StreamingQuality.entries.firstOrNull { it.name == name } }
                ?: StreamingQuality.KBPS256
        }
        set(value) {
            preferences.putString("playback.cellularQuality", value.name)
            preferences.putString(LEGACY_SWITCH, "true")
        }

    /** Bitrate cap on mobile data, or null for the original file. */
    val mobileDataBitrate: Int? get() = cellularQuality.bitrate

    /** Level loud and quiet tracks with Jellyfin's `NormalizationGain`. Default off. */
    var normalizeVolume: Boolean
        get() = bool("playback.normalizeVolume", false)
        set(value) = setBool("playback.normalizeVolume", value)

    companion object {
        /** The old "Streaming over mobile data" switch; only read to carry "off" over as Original. */
        private const val LEGACY_SWITCH = "playback.reduceOnMobileData"

        /** Player volume (0…1) for a gain in dB. Gains above 0 dB can't be applied (no louder than full). */
        fun volumeForGain(gain: Double?): Float {
            if (gain == null || !gain.isFinite()) return 1f
            return 10.0.pow(gain / 20).coerceIn(0.0, 1.0).toFloat()
        }
    }
}
