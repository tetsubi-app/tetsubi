package gg.dekiru.musicplayer.playback.effects

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Built-in equaliser curves (gains in dB for [Equalizer.frequencies]), as
 * iOS `EqualizerPreset`: the same names, gains and order (the order of the
 * preset list). [key] is what iOS saves (`playback.eqPreset`).
 */
enum class EqualizerPreset(val key: String, val displayName: String, gains: List<Float>?) {
    FLAT("flat", "Flat", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),

    // Rock: a "smiley" curve. Bass and treble up, low mids scooped out so
    // guitars and drums punch through (as iOS, which explains where it comes from).
    ROCK("rock", "Rock", listOf(5f, 4f, 3f, 0f, -2f, -2f, 0f, 3f, 4f, 5f)),
    BASS_BOOST("bassBoost", "Bass Boost", listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f)),
    TREBLE_BOOST("trebleBoost", "Treble Boost", listOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 6f)),
    VOCAL("vocal", "Vocal", listOf(-2f, -2f, -1f, 1f, 3f, 4f, 3f, 1f, 0f, -1f)),
    LOUDNESS("loudness", "Loudness", listOf(5f, 4f, 2f, 0f, -1f, -1f, 0f, 2f, 4f, 5f)),

    /** The user's own sliders: no gains of its own. */
    CUSTOM("custom", "Custom", null);

    /** The preset's band gains, or null for [CUSTOM]. */
    val gains: List<Float>? = gains

    companion object {
        /** The preset saved under [key], or null if it isn't one. */
        fun fromKey(key: String?): EqualizerPreset? = entries.firstOrNull { it.key == key }
    }
}

/**
 * One second-order (biquad) filter: RBJ "peaking EQ" coefficients,
 * normalised so a0 = 1. The same maths as iOS `Biquad`.
 */
data class Biquad(
    val b0: Float = 1f,
    val b1: Float = 0f,
    val b2: Float = 0f,
    val a1: Float = 0f,
    val a2: Float = 0f,
) {
    /** True when it leaves the sound as it is (0 dB, or a band too high for the sample rate). */
    val isPassThrough: Boolean get() = this == PASS_THROUGH

    /** Gain (linear) of this filter at [frequency]. */
    fun magnitude(frequency: Float, sampleRate: Float): Float {
        val w = 2 * PI * frequency / sampleRate
        // H(e^jw) = (b0 + b1 e^-jw + b2 e^-2jw) / (1 + a1 e^-jw + a2 e^-2jw)
        fun real(c0: Double, c1: Double, c2: Double) = c0 + c1 * cos(w) + c2 * cos(2 * w)
        fun imag(c1: Double, c2: Double) = -(c1 * sin(w) + c2 * sin(2 * w))
        val nr = real(b0.toDouble(), b1.toDouble(), b2.toDouble())
        val ni = imag(b1.toDouble(), b2.toDouble())
        val dr = real(1.0, a1.toDouble(), a2.toDouble())
        val di = imag(a1.toDouble(), a2.toDouble())
        return sqrt((nr * nr + ni * ni) / (dr * dr + di * di)).toFloat()
    }

    companion object {
        val PASS_THROUGH = Biquad()

        /** Boost or cut of [gainDb] centred on [frequency], bandwidth set by [q]. */
        fun peaking(frequency: Float, gainDb: Float, q: Float, sampleRate: Float): Biquad {
            if (gainDb == 0f || frequency >= sampleRate * 0.45f) return PASS_THROUGH
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2 * PI * frequency / sampleRate
            val alpha = sin(w0) / (2 * q)
            val cosw = cos(w0)
            val a0 = 1 + alpha / a
            return Biquad(
                b0 = ((1 + alpha * a) / a0).toFloat(),
                b1 = ((-2 * cosw) / a0).toFloat(),
                b2 = ((1 - alpha * a) / a0).toFloat(),
                a1 = ((-2 * cosw) / a0).toFloat(),
                a2 = ((1 - alpha / a) / a0).toFloat(),
            )
        }
    }
}

/** The equaliser's fixed layout, as iOS `AudioEffects`. */
object Equalizer {
    /** Centre frequencies (Hz) of the 10 bands. */
    val frequencies: List<Float> = listOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    val gainRange: ClosedFloatingPointRange<Float> = -12f..12f

    /** About one octave per band. */
    const val BAND_Q = 1.41f

    /** All bands at 0 dB. */
    val flatGains: List<Float> get() = List(frequencies.size) { 0f }

    /** Exactly 10 gains, each kept within ±12 dB (missing bands are 0 dB). */
    fun clamp(gains: List<Float>): List<Float> =
        List(frequencies.size) { i -> (gains.getOrNull(i) ?: 0f).coerceIn(gainRange.start, gainRange.endInclusive) }

    /** A band's label: "31", "500", "1k", "16k". */
    fun label(hz: Float): String = if (hz >= 1000) "${(hz / 1000).toInt()}k" else "${hz.toInt()}"
}

/**
 * The equaliser for one stream: a peaking filter per band, run over every
 * channel of interleaved PCM (16-bit or float). Keeps each channel's
 * filter memory between buffers, so audio flows on without clicks.
 * Not thread-safe: one audio thread uses it. Nothing here allocates while
 * processing (only [configure] does, when the channel count changes).
 */
class EqualizerChain {
    private var filters: Array<Biquad> = Array(Equalizer.frequencies.size) { Biquad.PASS_THROUGH }

    /** Per channel, per band: the two filter memory values (z1, z2). */
    private var memory = FloatArray(0)
    private var channels = 0

    /** Lowers the level by the biggest boost so peaks don't clip. */
    var preGain = 1f
        private set

    /** Scratch for 16-bit audio, grown as needed. */
    private var scratch = FloatArray(0)

    /** True when every band is at 0 dB: processing changes nothing. */
    val isFlat: Boolean get() = filters.all { it.isPassThrough } && preGain == 1f

    /** Sets the band gains for [sampleRate] and [channelCount]. Filter memory is kept if the channel count is unchanged. */
    fun configure(gains: List<Float>, sampleRate: Int, channelCount: Int) {
        val clamped = Equalizer.clamp(gains)
        filters = Array(Equalizer.frequencies.size) { i ->
            Biquad.peaking(Equalizer.frequencies[i], clamped[i], Equalizer.BAND_Q, sampleRate.toFloat())
        }
        preGain = 10.0.pow(-max(0f, clamped.max()) / 20.0).toFloat()
        if (channelCount != channels) {
            channels = channelCount
            memory = FloatArray(channelCount * filters.size * 2)
        }
    }

    /** Clears the filter memory (after a seek, so the old sound doesn't ring on). */
    fun reset() {
        memory.fill(0f)
    }

    /** Filters [frames] frames of interleaved float samples in place, from [offset]. */
    fun process(samples: FloatArray, frames: Int, offset: Int = 0) {
        val ch = channels
        if (ch == 0) return
        for (c in 0 until ch) {
            for (band in filters.indices) {
                val f = filters[band]
                if (f.isPassThrough) continue
                val m = (c * filters.size + band) * 2
                var z1 = memory[m]
                var z2 = memory[m + 1]
                var i = offset + c
                val end = offset + frames * ch
                while (i < end) {
                    val x = samples[i]
                    val y = f.b0 * x + z1
                    z1 = f.b1 * x - f.a1 * y + z2
                    z2 = f.b2 * x - f.a2 * y
                    samples[i] = y
                    i += ch
                }
                // A value that ran away (NaN, infinity) would silence the rest of the track.
                memory[m] = if (z1.isFinite()) z1 else 0f
                memory[m + 1] = if (z2.isFinite()) z2 else 0f
            }
        }
        if (preGain != 1f) {
            for (i in offset until offset + frames * ch) samples[i] *= preGain
        }
    }

    /** Filters [frames] frames of interleaved 16-bit samples in place (clipped to the 16-bit range). */
    fun process16(samples: ShortArray, frames: Int) {
        val count = frames * channels
        if (scratch.size < count) scratch = FloatArray(count)
        for (i in 0 until count) scratch[i] = samples[i] / 32768f
        process(scratch, frames)
        for (i in 0 until count) samples[i] = toPcm16(scratch[i])
    }

    companion object {
        /** A float sample (−1…1) as 16-bit, clipped. */
        fun toPcm16(sample: Float): Short = round((sample * 32768f).coerceIn(-32768f, 32767f)).toInt().toShort()
    }
}
