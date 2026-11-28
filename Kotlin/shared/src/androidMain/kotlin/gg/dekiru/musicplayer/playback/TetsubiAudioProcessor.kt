package gg.dekiru.musicplayer.playback

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import gg.dekiru.musicplayer.playback.effects.AudioEffects
import gg.dekiru.musicplayer.playback.effects.EqualizerChain
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.Volatile

/**
 * The equaliser and visualiser inside ExoPlayer (as iOS's audio tap): runs
 * on the decoded audio just before it goes to the speaker. With the
 * equaliser on it filters the sound; while a visualiser is showing it
 * copies the first channel into [AudioEffects.samples] for the bars.
 * Otherwise the audio passes through untouched (Flat counts as off).
 *
 * It stays switched in whatever the settings, so turning effects on or off
 * never rebuilds the audio output, and gapless playback keeps working.
 * 16-bit and float audio are handled; anything else passes by it.
 * Runs on ExoPlayer's playback thread: reads the settings from
 * [AudioEffects] without locks, and allocates only when a bigger buffer
 * than before arrives.
 */
@UnstableApi
class TetsubiAudioProcessor(private val effects: AudioEffects) : BaseAudioProcessor() {

    private val chain = EqualizerChain()

    /** The equaliser version the filters were built for (−1: rebuild). */
    private var eqVersion = -1
    private var eqOn = false

    /** Whether the last buffer was filtered (so the filters start clean when filtering starts). */
    private var equalising = false

    /** Float copy of a buffer while equalising. */
    private var scratch = FloatArray(0)

    /** Presentation time (µs) of the buffer being played in, set by [EffectsAudioSink]. */
    @Volatile
    var inputTimeUs: Long = C.TIME_UNSET

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> inputAudioFormat
            // Not ours to touch: switched out, the audio goes round it.
            else -> AudioFormat.NOT_SET
        }

    /** New stream or seek: rebuild the filters for the (maybe new) format and forget the old sound. */
    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        eqVersion = -1
        chain.reset()
    }

    override fun onReset() {
        eqVersion = -1
        eqOn = false
        equalising = false
        scratch = FloatArray(0)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        // PCM is in the device's byte order; make sure reads see it that way.
        if (inputBuffer.order() != ByteOrder.nativeOrder()) inputBuffer.order(ByteOrder.nativeOrder())
        val format = inputAudioFormat
        val channels = format.channelCount
        val frames = size / format.bytesPerFrame
        val isFloat = format.encoding == C.ENCODING_PCM_FLOAT
        val sampleBytes = if (isFloat) 4 else 2

        // Pick up equaliser changes (one volatile read).
        val state = effects.equalizer
        if (state.version != eqVersion) {
            eqVersion = state.version
            eqOn = state.enabled
            chain.configure(state.gains, format.sampleRate, channels)
        }
        val equalise = eqOn && !chain.isFlat
        // Old filter memory would pop when filtering starts again.
        if (equalise && !equalising) chain.reset()
        equalising = equalise
        val visualise = effects.visualizerActive
        val output = replaceOutputBuffer(size)
        val start = inputBuffer.position()

        if (!equalise) {
            if (visualise) {
                val ring = effects.samples
                ring.beginBlock(format.sampleRate, inputTimeUs.takeIf { it != C.TIME_UNSET })
                val step = format.bytesPerFrame
                for (f in 0 until frames) {
                    val at = start + f * step
                    ring.put(if (isFloat) inputBuffer.getFloat(at) else inputBuffer.getShort(at) / 32768f)
                }
                ring.endBlock()
            }
            output.put(inputBuffer)
            output.flip()
            return
        }

        val count = frames * channels
        if (scratch.size < count) scratch = FloatArray(count)
        for (i in 0 until count) {
            val at = start + i * sampleBytes
            scratch[i] = if (isFloat) inputBuffer.getFloat(at) else inputBuffer.getShort(at) / 32768f
        }
        chain.process(scratch, frames)
        for (i in 0 until count) {
            if (isFloat) output.putFloat(scratch[i]) else output.putShort(EqualizerChain.toPcm16(scratch[i]))
        }
        if (visualise) {
            val ring = effects.samples
            ring.beginBlock(format.sampleRate, inputTimeUs.takeIf { it != C.TIME_UNSET })
            for (f in 0 until frames) ring.put(scratch[f * channels])
            ring.endBlock()
        }
        // Any odd bytes past the last whole frame go through as they are.
        for (i in start + count * sampleBytes until inputBuffer.limit()) output.put(inputBuffer.get(i))
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }
}

/**
 * The audio output with timing for the visualiser: notes the presentation
 * time of each buffer going in (so [TetsubiAudioProcessor] can stamp the
 * samples it copies) and the position being heard, so the bars follow the
 * music rather than the audio buffered ahead of it.
 */
@UnstableApi
private class EffectsAudioSink(
    sink: AudioSink,
    private val processor: TetsubiAudioProcessor,
    private val effects: AudioEffects,
) : ForwardingAudioSink(sink) {

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        processor.inputTimeUs = presentationTimeUs
        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val position = super.getCurrentPositionUs(sourceEnded)
        if (position != AudioSink.CURRENT_POSITION_NOT_SET && effects.visualizerActive) effects.reportPosition(position)
        return position
    }
}

/**
 * ExoPlayer's usual renderers, with [TetsubiAudioProcessor] in the audio
 * output (`DefaultAudioSink`), working with [effects]. ALAC is decoded by
 * audioDEK ([AudioDekAudioRenderer]), which comes before the MediaCodec
 * audio renderer, and MediaCodec is given no ALAC decoders at all (a device
 * may list one that fails, or none), so ALAC is never handed to it.
 */
@UnstableApi
fun effectsRenderersFactory(context: Context, effects: AudioEffects): DefaultRenderersFactory =
    object : DefaultRenderersFactory(context) {
        override fun buildAudioRenderers(
            context: Context,
            extensionRendererMode: Int,
            mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean,
            audioSink: AudioSink,
            eventHandler: Handler,
            eventListener: AudioRendererEventListener,
            out: ArrayList<Renderer>,
        ) {
            // First, so for ALAC it wins even if another renderer also claimed it.
            out.add(AudioDekAudioRenderer(eventHandler, eventListener, audioSink))
            super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
        }

        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink {
            val processor = TetsubiAudioProcessor(effects)
            val sink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                .setAudioProcessors(arrayOf(processor))
                .build()
            return EffectsAudioSink(sink, processor, effects)
        }
    }.setMediaCodecSelector(withoutAlac(MediaCodecSelector.DEFAULT))
        // 24- and 32-bit PCM (audioDEK's 24-bit ALAC, 24-bit FLAC) reaches the audio track as float: a float32 holds
        // every 24-bit sample exactly, whereas ExoPlayer's default would cut it to 16 bits. 16-bit stays 16-bit.
        .setEnableAudioFloatOutput(true)

/** [base] without any decoder for ALAC: the MediaCodec renderer then cannot claim it. */
@UnstableApi
fun withoutAlac(base: MediaCodecSelector): MediaCodecSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
    if (mimeType.equals(MimeTypes.AUDIO_ALAC, ignoreCase = true)) emptyList() else base.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
}
