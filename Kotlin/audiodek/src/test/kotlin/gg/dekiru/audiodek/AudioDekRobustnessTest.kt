package gg.dekiru.audiodek

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Corrupt and truncated frames: the only allowed outcomes are a decoded frame
 * or an [AudioDekAlacException]; never another exception (out of bounds,
 * negative size...), a hang, or a crash.
 */
class AudioDekRobustnessTest {

    private fun fixture(name: String): Mp4AlacSamples =
        Mp4AlacSamples(javaClass.getResourceAsStream("/alac/$name")!!.use { it.readBytes() })

    private val names = listOf(
        "music_16_st_44k.afc.m4a", "music_16_st_44k.ff.m4a", "noise_16_st_44k.afc.m4a", "music_24_st_44k.afc.m4a",
        "music_32_st_44k.afc.m4a", "music_16_mono_44k.ff.m4a", "music_16_51_48k.afc.m4a", "music_16_71_48k.ff.m4a",
        "silence_16_st_44k.afc.m4a", "noise_32_st_44k.afc.m4a", "music_20_st_44k.afc.m4a",
    )

    private fun tryDecode(decoder: AudioDekAlacDecoder, frame: ByteArray, out: IntArray): Boolean = try {
        decoder.decode(frame, 0, frame.size, out)
        true
    } catch (e: AudioDekAlacException) {
        false
    } catch (e: Throwable) {
        fail("unexpected ${e::class.simpleName}: ${e.message}")
    }

    @Test
    fun everyTruncationOfEveryFrameEitherDecodesOrThrowsTheDecoderException() {
        var rejected = 0
        for (name in names) {
            val mp4 = fixture(name)
            val decoder = AudioDekAlacDecoder(AudioDekAlacConfig.parse(mp4.cookie))
            val out = IntArray(decoder.maxSamples)
            val frame = mp4.frame(0)
            // Every length for short frames, a spread for long ones.
            val lengths = if (frame.size < 3000) (0..frame.size).toList() else (0..frame.size step 7).toList() + frame.size
            for (n in lengths) if (!tryDecode(decoder, frame.copyOf(n), out)) rejected++
            // The untouched frame still decodes afterwards.
            assertTrue(tryDecode(decoder, frame, out), "$name: the full frame no longer decodes")
        }
        assertTrue(rejected > 1000, "truncations should mostly be rejected ($rejected)")
    }

    @Test
    fun randomByteCorruptionNeverEscapesTheDecoderException() {
        var seed = 12345
        fun next(): Int { seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5); return seed }
        for (name in names) {
            val mp4 = fixture(name)
            val decoder = AudioDekAlacDecoder(AudioDekAlacConfig.parse(mp4.cookie))
            val out = IntArray(decoder.maxSamples)
            val original = mp4.frame(0)
            repeat(400) {
                val frame = original.copyOf()
                repeat(1 + (next() ushr 1) % 4) {
                    val at = if (it == 0) (next() ushr 1) % minOf(frame.size, 40) else (next() ushr 1) % frame.size
                    frame[at] = (frame[at].toInt() xor next()).toByte()
                }
                tryDecode(decoder, frame, out)
            }
        }
    }

    @Test
    fun garbageNeverEscapesTheDecoderException() {
        var seed = 987654
        fun next(): Int { seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5); return seed }
        for (channels in listOf(1, 2, 6)) for (bits in listOf(16, 24, 32)) {
            val decoder = AudioDekAlacDecoder(AudioDekAlacConfig.parse(cookie(bits, channels, 4096)))
            val out = IntArray(decoder.maxSamples)
            repeat(300) {
                val frame = ByteArray(1 + (next() ushr 1) % 600) { next().toByte() }
                // Make the element tag valid often, so the parser goes deeper.
                if (it % 2 == 0) frame[0] = (frame[0].toInt() and 0x1F).toByte()
                tryDecode(decoder, frame, out)
            }
        }
    }
}
