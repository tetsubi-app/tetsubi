package gg.dekiru.musicplayer.ui.nowplaying

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import gg.dekiru.musicplayer.data.settings.Preferences
import gg.dekiru.musicplayer.playback.PlaybackTrack

/**
 * Now Playing's quality text: the sample rate ("44.1kHz/16-bit") by
 * default; a tap shows the format and bitrate (the quality label) instead,
 * and another tap goes back. The choice is saved as `nowPlaying.qualityDetail`
 * (default false: the sample rate). Without [preferences] it lasts as long as this.
 */
class QualityDetailSetting(private val preferences: Preferences?) {
    var showsDetail by mutableStateOf(preferences?.getString(KEY)?.toBooleanStrictOrNull() ?: false)
        private set

    fun toggle() {
        showsDetail = !showsDetail
        preferences?.putString(KEY, showsDetail.toString())
    }

    companion object {
        const val KEY = "nowPlaying.qualityDetail"
    }
}

/** What the quality text shows, and whether a tap switches it (only when there are both texts). */
data class QualityText(val text: String, val switches: Boolean) {
    companion object {
        /**
         * The sample rate or the quality label, as [showsDetail] chooses. A
         * track without a sample rate shows its quality label (and the
         * reverse), not switchable; null when it has neither.
         */
        fun of(track: PlaybackTrack, showsDetail: Boolean): QualityText? {
            val rate = track.sampleRateLabel
            val detail = track.qualityLabel
            return when {
                rate != null && detail != null -> QualityText(if (showsDetail) detail else rate, switches = true)
                else -> (detail ?: rate)?.let { QualityText(it, switches = false) }
            }
        }

        /** What a tap does, for screen readers. */
        fun hint(showsDetail: Boolean): String =
            if (showsDetail) "Shows the sample rate and bit depth" else "Shows the format and bitrate"
    }
}
