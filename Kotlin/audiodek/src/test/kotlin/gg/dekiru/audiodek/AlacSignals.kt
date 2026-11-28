package gg.dekiru.audiodek

/**
 * Deterministic test signals for the audioDEK bit-exact decode tests. Integer
 * arithmetic only (no floating point, no platform differences), so the same
 * samples come out on every JVM; `tools/make-fixtures.sh` and the README say how the fixtures
 * were made from them.
 */
enum class AlacKind { SILENCE, SQUARE, IMPULSE, SINE, NOISE, MUSIC, STEREO_CORRELATED, STEREO_UNCORRELATED }

/** One fixture: [name] is also the file name stem (`<name>.<encoder>.m4a`). */
data class AlacSignal(
    val name: String,
    val kind: AlacKind,
    val channels: Int,
    val bits: Int,
    val rate: Int,
    val frames: Int,
    /** WAVE_FORMAT_EXTENSIBLE speaker mask for the generated WAV (multichannel only; 0 for mono/stereo). */
    val channelMask: Int = 0,
) {
    /** Interleaved samples, channels in WAVE order, each in the signed range of [bits]. */
    fun samples(): IntArray = AlacSignals.generate(this)
}

object AlacSignals {

    val all: List<AlacSignal> = listOf(
        AlacSignal("silence_16_st_44k", AlacKind.SILENCE, 2, 16, 44100, 4500),
        AlacSignal("square_16_st_44k", AlacKind.SQUARE, 2, 16, 44100, 3000),
        AlacSignal("impulse_16_st_44k", AlacKind.IMPULSE, 2, 16, 44100, 3000),
        AlacSignal("sine_16_st_44k", AlacKind.SINE, 2, 16, 44100, 3000),
        AlacSignal("noise_16_st_44k", AlacKind.NOISE, 2, 16, 44100, 1500),
        AlacSignal("music_16_st_44k", AlacKind.MUSIC, 2, 16, 44100, 4300),
        AlacSignal("music_16_mono_44k", AlacKind.MUSIC, 1, 16, 44100, 3000),
        AlacSignal("corr_16_st_44k", AlacKind.STEREO_CORRELATED, 2, 16, 44100, 3000),
        AlacSignal("uncorr_16_st_44k", AlacKind.STEREO_UNCORRELATED, 2, 16, 44100, 3000),
        AlacSignal("music_16_st_48k", AlacKind.MUSIC, 2, 16, 48000, 3000),
        AlacSignal("music_24_st_96k", AlacKind.MUSIC, 2, 24, 96000, 3000),
        AlacSignal("short_16_st_44k", AlacKind.MUSIC, 2, 16, 44100, 3),
        AlacSignal("odd_16_st_44k", AlacKind.MUSIC, 2, 16, 44100, 4097),
        AlacSignal("music_20_st_44k", AlacKind.MUSIC, 2, 20, 44100, 3000),
        AlacSignal("music_24_st_44k", AlacKind.MUSIC, 2, 24, 44100, 3000),
        AlacSignal("noise_24_st_44k", AlacKind.NOISE, 2, 24, 44100, 800),
        AlacSignal("square_24_st_44k", AlacKind.SQUARE, 2, 24, 44100, 2200),
        AlacSignal("music_32_st_44k", AlacKind.MUSIC, 2, 32, 44100, 2200),
        AlacSignal("noise_32_st_44k", AlacKind.NOISE, 2, 32, 44100, 600),
        AlacSignal("music_24_mono_44k", AlacKind.MUSIC, 1, 24, 44100, 3000),
        AlacSignal("music_16_51_48k", AlacKind.MUSIC, 6, 16, 48000, 1500, 0x3F),
        AlacSignal("music_24_51_48k", AlacKind.MUSIC, 6, 24, 48000, 1200, 0x3F),
        AlacSignal("music_16_71_48k", AlacKind.MUSIC, 8, 16, 48000, 1500, 0xFF),
    )

    fun generate(signal: AlacSignal): IntArray {
        val ch = signal.channels
        val out = IntArray(signal.frames * ch)
        val bits = signal.bits
        val rate = signal.rate
        val max = if (bits == 32) Int.MAX_VALUE else (1 shl (bits - 1)) - 1
        val min = if (bits == 32) Int.MIN_VALUE else -(1 shl (bits - 1))
        val noise = Noise(0x1234567 + bits * 31 + ch)
        var left = 0L
        for (f in 0 until signal.frames) {
            for (c in 0 until ch) {
                val v: Long = when (signal.kind) {
                    AlacKind.SILENCE -> 0
                    AlacKind.SQUARE -> if (((f + c * 7) / 50) % 2 == 0) max.toLong() else min.toLong()
                    AlacKind.IMPULSE -> if ((f + c * 3) % 997 == 0) max.toLong() else 0L
                    AlacKind.SINE -> scale(sine(f, 441 + 111 * c, rate), 0.8, bits)
                    AlacKind.NOISE -> noise.next().toLong() shr (32 - bits)
                    AlacKind.MUSIC -> music(f, c, bits, noise, rate)
                    AlacKind.STEREO_CORRELATED ->
                        if (c == 0) music(f, 0, bits, noise, rate).also { left = it } else left * 7 / 8 + (noise.next().toLong() shr (32 - bits + 6))
                    AlacKind.STEREO_UNCORRELATED ->
                        if (c == 0) scale(sine(f, 330, rate), 0.7, bits) else noise.next().toLong() shr (32 - bits + 3)
                }
                out[f * ch + c] = v.coerceIn(min.toLong(), max.toLong()).toInt()
            }
        }
        return out
    }

    /** Three tones per channel with a slow swell and a little noise: smooth enough to predict, not trivial. */
    private fun music(f: Int, c: Int, bits: Int, noise: Noise, rate: Int): Long {
        val a = sine(f, 220 + 37 * c, rate)
        val b = sine(f, 659 + 53 * c, rate)
        val d = sine(f, 1760 + 101 * c, rate)
        val swell = 600 + sine(f, 3, rate) / 64 // 88..1112
        val mix = (a * 5 + b * 3 + d) / 12 // about +-1.0 in Q15
        val unit = mix * swell / 1000 // Q15
        val base = scale(unit.toInt(), 0.6, bits)
        val hiss = (noise.next().toLong() shr (32 - bits)) shr (bits - 3)
        return base + hiss
    }

    /** Parabolic "sine" in Q15 (-32768..32768): [freq] Hz at [rate] Hz, [f] samples in. */
    private fun sine(f: Int, freq: Int, rate: Int): Int {
        val phase = (f.toLong() * freq * 65536 / rate % 65536).toInt()
        val x = phase and 32767
        val p = (x.toLong() * (32768 - x) * 4 / 32768).toInt()
        return if (phase < 32768) p else -p
    }

    /** [q15] (-32768..32768 means -1..1) scaled to [bits] at [gain]. */
    private fun scale(q15: Int, gain: Double, bits: Int): Long {
        val peak = if (bits == 32) 2147483647L else (1L shl (bits - 1)) - 1
        val gainQ = (gain * 1024).toLong()
        return q15.toLong() * peak / 32768 * gainQ / 1024
    }

    /** xorshift32: the same sequence everywhere. */
    private class Noise(seed: Int) {
        private var s = if (seed == 0) 1 else seed
        fun next(): Int {
            s = s xor (s shl 13)
            s = s xor (s ushr 17)
            s = s xor (s shl 5)
            return s
        }
    }

    /** Samples as little-endian PCM of [bits] bits (rounded up to whole bytes), as a WAV `data` chunk holds them. */
    fun littleEndianPcm(samples: IntArray, bits: Int): ByteArray {
        val bytes = (bits + 7) / 8
        val out = ByteArray(samples.size * bytes)
        var o = 0
        for (s in samples) {
            for (b in 0 until bytes) out[o++] = (s shr (8 * b)).toByte()
        }
        return out
    }
}
