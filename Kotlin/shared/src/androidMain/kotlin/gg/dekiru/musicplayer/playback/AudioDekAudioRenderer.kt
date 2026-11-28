package gg.dekiru.musicplayer.playback

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer
import gg.dekiru.audiodek.AudioDekAlacConfig
import gg.dekiru.audiodek.AudioDekAlacDecoder
import gg.dekiru.audiodek.AudioDekAlacException
import gg.dekiru.audiodek.AudioDekChannels
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The pure parts of audioDEK's Media3 integration (no Android types besides
 * Media3 constants), so they run in JVM tests.
 */
object AudioDekFormats {
    /** Whether audioDEK decodes this sample MIME type (ALAC only). */
    fun handles(sampleMimeType: String?): Boolean = sampleMimeType == MimeTypes.AUDIO_ALAC

    /**
     * The PCM encoding audioDEK outputs for a file of [bitDepth] bits: 16, 24 and 32 as they are,
     * 20-bit audio as 24-bit left-justified (each value shifted left 4: the values are kept, the
     * extra low bits are zero).
     */
    fun pcmEncoding(bitDepth: Int): Int = when (bitDepth) {
        16 -> C.ENCODING_PCM_16BIT
        20, 24 -> C.ENCODING_PCM_24BIT
        32 -> C.ENCODING_PCM_32BIT
        else -> throw AudioDekAlacException("ALAC bit depth $bitDepth is not supported")
    }

    fun bytesPerSample(bitDepth: Int): Int = when (bitDepth) {
        16 -> 2
        20, 24 -> 3
        32 -> 4
        else -> throw AudioDekAlacException("ALAC bit depth $bitDepth is not supported")
    }

    /** The cookie of an ALAC [Format] (Media3's Mp4Extractor puts it in `initializationData[0]`). */
    fun config(initializationData: List<ByteArray>): AudioDekAlacConfig {
        val cookie = initializationData.firstOrNull() ?: throw AudioDekAlacException("ALAC stream has no cookie")
        return AudioDekAlacConfig.parse(cookie)
    }

    /**
     * Writes [count] samples as little-endian PCM of [bitDepth] bits into [out] (20-bit as 24-bit,
     * value shifted left 4). The buffer's position moves; the caller flips it.
     */
    fun writePcm(samples: IntArray, count: Int, bitDepth: Int, out: ByteBuffer) {
        val order = out.order()
        out.order(ByteOrder.LITTLE_ENDIAN)
        when (bitDepth) {
            16 -> for (i in 0 until count) out.putShort(samples[i].toShort())
            20, 24 -> {
                val up = if (bitDepth == 20) 4 else 0
                for (i in 0 until count) {
                    val v = samples[i] shl up
                    out.put(v.toByte()).put((v shr 8).toByte()).put((v shr 16).toByte())
                }
            }
            32 -> for (i in 0 until count) out.putInt(samples[i])
            else -> throw AudioDekAlacException("ALAC bit depth $bitDepth is not supported")
        }
        out.order(order)
    }

    /** Whether a decoder made for [old] can decode [new] as it is: the same cookie. */
    fun canReuse(old: List<ByteArray>, new: List<ByteArray>): Boolean =
        old.size == new.size && old.isNotEmpty() && old.indices.all { old[it].contentEquals(new[it]) }

    /**
     * The speaker mask the output [Format] carries (`Format.channelMask`, Android `CHANNEL_OUT_*`, which
     * `DefaultAudioSink` hands to the audio track as it is), or [Format.NO_VALUE] for mono and stereo, where
     * ExoPlayer's count-based layout is already right. Without it 4 channels become QUAD (FL FR BL BR),
     * which would put the centre and the rear centre in the back speakers.
     */
    fun channelMask(channels: Int): Int =
        AudioDekChannels.layout(channels).let { if (it.explicitMask) it.androidMask else Format.NO_VALUE }

    /** The `audioDEK output:` line logged at each ALAC track start, next to `alac decode:`. */
    fun outputLine(channels: Int): String {
        val layout = AudioDekChannels.layout(channels)
        val mask = if (layout.explicitMask) "${layout.maskName}(0x${layout.androidMask.toString(16)})" else "default(${layout.maskName})"
        return "audioDEK output: channels=$channels mask=$mask layout=${layout.alacOrder.replace(' ', '-')}"
    }

    /** The `alac decode:` line logged at each ALAC track start. */
    fun logLine(itemId: String, bits: Int, channels: Int, rate: Int): String =
        "alac decode: item=$itemId bits=$bits channels=$channels rate=$rate mode=audioDEK"
}

/** What can go wrong in the Media3 shell: a frame audioDEK could not decode. */
class AudioDekDecoderException(message: String, cause: Throwable? = null) : androidx.media3.decoder.DecoderException(message, cause)

/**
 * Frame bytes in, interleaved little-endian PCM out, channels in WAVE order. Holds the scratch
 * buffers; no Media3 types, so it is tested directly.
 */
class AudioDekFrameDecoder(val config: AudioDekAlacConfig) {
    private val decoder = AudioDekAlacDecoder(config)
    private val alacOrder = IntArray(decoder.maxSamples)
    private val waveOrder = IntArray(decoder.maxSamples)
    private var frame = ByteArray(0)

    val channels: Int get() = config.channels
    val sampleRate: Int get() = config.sampleRate.toInt()
    val pcmEncoding: Int = AudioDekFormats.pcmEncoding(config.bitDepth)

    /** Largest PCM output of one frame, in bytes. */
    val maxOutputBytes: Int get() = config.frameLength * config.channels * AudioDekFormats.bytesPerSample(config.bitDepth)

    /** Nothing is kept between frames; here for the decoder's flush. */
    fun reset() = decoder.reset()

    /** Decodes the [size] bytes at [data]'s position into [out] (not flipped); returns the frames decoded. */
    fun decode(data: ByteBuffer, size: Int, out: (Int) -> ByteBuffer): Int {
        if (frame.size < size) frame = ByteArray(size)
        data.get(frame, 0, size)
        val frames = decoder.decode(frame, 0, size, alacOrder)
        val count = frames * config.channels
        AudioDekChannels.toWave(alacOrder, waveOrder, frames, config.channels)
        AudioDekFormats.writePcm(waveOrder, count, config.bitDepth, out(count * AudioDekFormats.bytesPerSample(config.bitDepth)))
        return frames
    }
}

/** Media3's decoder shell around [AudioDekFrameDecoder]: one ALAC sample in, one PCM buffer out. */
@UnstableApi
class AudioDekSimpleDecoder(initializationData: List<ByteArray>) :
    SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, AudioDekDecoderException>(
        Array(INPUT_BUFFERS) { DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL) },
        Array(OUTPUT_BUFFERS) { SimpleDecoderOutputBuffer { } },
    ) {

    val frameDecoder: AudioDekFrameDecoder = AudioDekFrameDecoder(AudioDekFormats.config(initializationData))

    override fun getName(): String = NAME

    override fun createInputBuffer() = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)

    override fun createOutputBuffer() = SimpleDecoderOutputBuffer(this::releaseOutputBuffer)

    override fun createUnexpectedDecodeException(error: Throwable) = AudioDekDecoderException("Unexpected decode error", error)

    override fun decode(inputBuffer: DecoderInputBuffer, outputBuffer: SimpleDecoderOutputBuffer, reset: Boolean): AudioDekDecoderException? {
        // The decoder keeps nothing between frames; a flush (seek) still clears it.
        if (reset) frameDecoder.reset()
        val data = inputBuffer.data ?: return AudioDekDecoderException("ALAC sample has no data")
        return try {
            frameDecoder.decode(data, data.remaining()) { bytes ->
                outputBuffer.init(inputBuffer.timeUs, bytes).also { it.order(ByteOrder.LITTLE_ENDIAN) }
            }
            outputBuffer.data?.flip()
            null
        } catch (e: AudioDekAlacException) {
            AudioDekDecoderException(e.message ?: "ALAC decode failed", e)
        }
    }

    private companion object {
        const val NAME = "audioDEK-alac"
        const val INPUT_BUFFERS = 4
        const val OUTPUT_BUFFERS = 4
    }
}

/**
 * ExoPlayer audio renderer for ALAC using audioDEK, the built-in decoder: ALAC is decoded in
 * the app on every device, instead of by the device's MediaCodec (absent on some tablets, failing
 * on some phones). Output is PCM: 16-bit as it is, 20 and 24-bit as 24-bit, 32-bit as 32-bit,
 * channels in WAVE order.
 */
@UnstableApi
class AudioDekAudioRenderer(
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    audioSink: AudioSink,
) : DecoderAudioRenderer<AudioDekSimpleDecoder>(eventHandler, eventListener, audioSink) {

    override fun getName(): String = "AudioDekAudioRenderer"

    override fun supportsFormatInternal(format: Format): Int {
        if (!AudioDekFormats.handles(format.sampleMimeType)) return C.FORMAT_UNSUPPORTED_TYPE
        val config = try {
            AudioDekFormats.config(format.initializationData)
        } catch (e: AudioDekAlacException) {
            return C.FORMAT_UNSUPPORTED_SUBTYPE
        }
        if (format.cryptoType != C.CRYPTO_TYPE_NONE) return C.FORMAT_UNSUPPORTED_DRM
        val output = outputFormat(config)
        return if (sinkSupportsFormat(output)) C.FORMAT_HANDLED else C.FORMAT_UNSUPPORTED_SUBTYPE
    }

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): AudioDekSimpleDecoder {
        try {
            return AudioDekSimpleDecoder(format.initializationData)
        } catch (e: AudioDekAlacException) {
            throw AudioDekDecoderException(e.message ?: "ALAC cookie is not valid", e)
        }
    }

    override fun getOutputFormat(decoder: AudioDekSimpleDecoder): Format = outputFormat(decoder.frameDecoder.config)

    /** A decoder is reused for another stream only when its cookie is identical (so nothing needs reconfiguring). */
    override fun canReuseDecoder(decoderName: String, oldFormat: Format, newFormat: Format): DecoderReuseEvaluation =
        if (AudioDekFormats.handles(newFormat.sampleMimeType) && AudioDekFormats.canReuse(oldFormat.initializationData, newFormat.initializationData)) {
            DecoderReuseEvaluation(decoderName, oldFormat, newFormat, DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION, 0)
        } else {
            DecoderReuseEvaluation(decoderName, oldFormat, newFormat, DecoderReuseEvaluation.REUSE_RESULT_NO, DecoderReuseEvaluation.DISCARD_REASON_INITIALIZATION_DATA_CHANGED)
        }

    private fun outputFormat(config: AudioDekAlacConfig): Format =
        Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(config.channels)
            .setChannelMask(AudioDekFormats.channelMask(config.channels))
            .setSampleRate(config.sampleRate.toInt())
            .setPcmEncoding(AudioDekFormats.pcmEncoding(config.bitDepth))
            .build()
}
