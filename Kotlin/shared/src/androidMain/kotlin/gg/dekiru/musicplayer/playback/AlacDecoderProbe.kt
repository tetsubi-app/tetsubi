package gg.dekiru.musicplayer.playback

import android.media.MediaCodecList
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * Whether this device's MediaCodec has an ALAC decoder, asked once (the codec
 * list is the same for the life of the process). Qualcomm devices list
 * `c2.qti.alac.sw.decoder` / `.hw.decoder`; MediaTek ones don't, and Media3
 * plays an ALAC file silently there. [DirectPlayCapabilities.forDevice] adds
 * ALAC to what the player plays as it is only when this is true.
 */
@UnstableApi
object AlacDecoderProbe {
    /** Names of the decoders MediaCodec lists for `audio/alac` (for the start-up log line). */
    val decoders: List<String> by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(MimeTypes.AUDIO_ALAC, ignoreCase = true) } }
                .map { it.name }
        }.getOrDefault(emptyList())
    }

    /**
     * ExoPlayer's own answer: the decoders its default selector offers for
     * ALAC (the renderer uses exactly these). Empty or failing counts as none.
     */
    private val selected: List<String> by lazy {
        runCatching { MediaCodecSelector.DEFAULT.getDecoderInfos(MimeTypes.AUDIO_ALAC, false, false).map { it.name } }
            .getOrDefault(emptyList())
    }

    /** The player can decode ALAC: the codec list has a decoder (and ExoPlayer's selector would pick one). */
    val available: Boolean by lazy {
        val listed = runCatching {
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.map {
                DirectPlayCapabilities.Codec(it.isEncoder, it.supportedTypes.toList())
            }
            DirectPlayCapabilities.hasDecoder(MimeTypes.AUDIO_ALAC, codecs)
        }.getOrDefault(false)
        listed && selected.isNotEmpty()
    }

    /** Decoder names ExoPlayer's selector offers (a difference from [decoders] explains an unexpected "false"). */
    val selectedDecoders: List<String> get() = selected
}
