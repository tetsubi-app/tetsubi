package gg.dekiru.musicplayer.playback.effects

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** The visualiser's layout, as iOS `SpectrumBars` and `AudioEffects.barCount`. */
object Spectrum {
    /** Number of bars. */
    const val BAR_COUNT = 32

    /** Samples per analysis. */
    const val FFT_SIZE = 1024
    const val LOW_HZ = 40f
    const val HIGH_HZ = 16_000f

    /** Bars below this level read as silence. */
    const val FLOOR_DB = -60f

    /** Upper edge (Hz) of bar [i] of [count], log-spaced between 40 Hz and 16 kHz. */
    fun edge(i: Int, count: Int): Float = LOW_HZ * (HIGH_HZ / LOW_HZ).pow(i.toFloat() / count)

    /**
     * FFT magnitudes → `out.size` bar levels (0…1), averaging the bins in
     * each log-spaced band, as iOS. Writes into [out] (no allocation).
     */
    fun levels(magnitudes: FloatArray, sampleRate: Float, fftSize: Int, out: FloatArray) {
        val count = out.size
        val binHz = sampleRate / fftSize
        for (i in 0 until count) {
            val lo = max(1, (edge(i, count) / binHz).toInt())
            val hi = min(magnitudes.size - 1, max(lo, (edge(i + 1, count) / binHz).toInt()))
            var sum = 0f
            for (bin in lo..hi) sum += magnitudes[bin]
            val mean = sum / (hi - lo + 1)
            // Normalise: a full-scale sine gives about fftSize / 4 (iOS's comment, kept with its scaling below).
            val db = 20 * log10(max(mean / (fftSize / 4f), 1e-9f))
            out[i] = ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
        }
    }
}

/**
 * Turns [Spectrum.FFT_SIZE] samples into bar levels: Hann window, FFT,
 * magnitudes, then [Spectrum.levels]. Plain Kotlin (a radix-2 FFT). The
 * scaling matches iOS's Accelerate code exactly (its "normalised" Hann
 * window peaks at 2·√(2/3), and its real FFT gives twice the textbook
 * values), so the bars stand as tall as on the iPhone. Reuses its arrays:
 * one instance per screen, not thread-safe.
 */
class SpectrumAnalyser(private val fftSize: Int = Spectrum.FFT_SIZE) {
    init {
        require(fftSize > 1 && fftSize and (fftSize - 1) == 0) { "fftSize must be a power of two" }
    }

    private val window = FloatArray(fftSize) { k -> (sqrt(2.0 / 3.0) * (1 - cos(2 * PI * k / fftSize))).toFloat() }
    private val real = FloatArray(fftSize)
    private val imag = FloatArray(fftSize)
    private val magnitudes = FloatArray(fftSize / 2)

    /** Bar levels for the first [fftSize] values of [samples] (mono, −1…1), into [out]. */
    fun analyse(samples: FloatArray, sampleRate: Float, out: FloatArray) {
        for (k in 0 until fftSize) {
            real[k] = samples[k] * window[k]
            imag[k] = 0f
        }
        fft(real, imag)
        for (k in magnitudes.indices) {
            magnitudes[k] = 2 * sqrt(real[k] * real[k] + imag[k] * imag[k])
        }
        Spectrum.levels(magnitudes, sampleRate, fftSize, out)
    }

    /** In-place iterative radix-2 FFT. */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        // Bit-reversal reordering.
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            val wr = cos(angle).toFloat()
            val wi = sin(angle).toFloat()
            var start = 0
            while (start < n) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val a = start + k
                    val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                    val next = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = next
                }
                start += len
            }
            len = len shl 1
        }
    }
}

/**
 * The bar levels between frames, as iOS `SmoothedLevels`: a bar jumps up
 * to a new peak at once and falls back at most 1.5 of its full height per
 * second.
 */
class SmoothedLevels(count: Int = Spectrum.BAR_COUNT) {
    val values = FloatArray(count)

    /** True when every bar is (nearly) down. */
    val isSilent: Boolean get() = values.all { it < 0.01f }

    /** Moves towards [target] (missing bars count as 0) over [seconds] since the last frame. */
    fun update(target: FloatArray?, seconds: Float): FloatArray {
        val dt = seconds.coerceIn(0f, 0.1f)
        for (i in values.indices) {
            val t = target?.getOrNull(i) ?: 0f
            values[i] = if (t >= values[i]) t else max(t, values[i] - 1.5f * dt)
        }
        return values
    }
}
