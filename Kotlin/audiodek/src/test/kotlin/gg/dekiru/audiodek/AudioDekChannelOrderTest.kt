package gg.dekiru.audiodek

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Channel order for every ALAC channel count 1..8. Fixtures in `src/test/resources/alac/channels`
 * (made by `tools/make-channel-fixtures.sh`): 50 ms at 48 kHz/16-bit where channel c is the constant
 * (c+1)*1000 plus a sine of (c+1)*100 Hz, encoded by FFmpeg's `alac` encoder (`.ff`) and by `afconvert` (`.afc`).
 * `channels-manifest.txt` holds the SHA-256 of what FFmpeg's decoder gives for each file as interleaved s16le:
 * that is the reference order. audioDEK's decode + [AudioDekChannels.toWave] must equal it sample for sample.
 */
class AudioDekChannelOrderTest {

    private fun resource(name: String): ByteArray =
        javaClass.getResourceAsStream("/alac/channels/$name")?.use { it.readBytes() } ?: fail("missing fixture $name")

    private val manifest: Map<String, Pair<String, Int>> =
        String(resource("channels-manifest.txt")).lines().filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line -> line.split(" ").let { it[0] to (it[1] to it[2].toInt()) } }

    /** audioDEK's output for a whole file, channels in WAVE order, as interleaved s16le bytes. */
    private fun decodeToS16le(name: String): Pair<IntArray, ByteArray> {
        val mp4 = Mp4AlacSamples(resource("$name.m4a"))
        val config = AudioDekAlacConfig.parse(mp4.cookie)
        assertEquals(16, config.bitDepth)
        val decoder = AudioDekAlacDecoder(config)
        val frame = IntArray(decoder.maxSamples)
        val wave = IntArray(decoder.maxSamples)
        val samples = ArrayList<Int>()
        for (i in mp4.samples.indices) {
            val frames = decoder.decode(mp4.frame(i), 0, mp4.samples[i].second, frame)
            AudioDekChannels.toWave(frame, wave, frames, config.channels)
            for (s in 0 until frames * config.channels) samples += wave[s]
        }
        val out = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, v -> out[2 * i] = v.toByte(); out[2 * i + 1] = (v shr 8).toByte() }
        return samples.toIntArray() to out
    }

    @Test
    fun everyChannelCountDecodesToFfmpegsOrderForBothEncoders() {
        assertEquals((1..8).flatMap { n -> listOf("ch${n}_16_48k.ff", "ch${n}_16_48k.afc") }.toSet(), manifest.keys)
        for ((name, expected) in manifest) {
            val (sha, channels) = expected
            val (samples, bytes) = decodeToS16le(name)
            assertEquals(0, samples.size % channels, name)
            assertEquals(sha, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, "$name: not FFmpeg's decode")
        }
    }

    @Test
    fun eachDecodedChannelIsTheOneThatWasEncodedAtThatPosition() {
        // The source channel c (WAVE order) is (c+1)*1000 + a 500-peak sine of whole cycles: its mean is (c+1)*1000.
        for (n in 1..8) for (e in listOf("ff", "afc")) {
            val (samples, _) = decodeToS16le("ch${n}_16_48k.$e")
            for (c in 0 until n) {
                val mean = (c until samples.size step n).map { samples[it] }.average()
                assertEquals((c + 1) * 1000.0, mean, 2.0, "ch$n.$e: output channel $c")
            }
        }
    }

    // The layout table (pure)

    private val bit = mapOf("FL" to 0x4, "FR" to 0x8, "FC" to 0x10, "LFE" to 0x20, "BL" to 0x40, "BR" to 0x80, "BC" to 0x400, "SL" to 0x800, "SR" to 0x1000)

    @Test
    fun monoAndStereoAreUnchanged() {
        for (n in 1..2) {
            assertEquals((0 until n).toList(), AudioDekChannels.waveOrder(n).toList())
            assertFalse(AudioDekChannels.layout(n).explicitMask, "no mask set: ExoPlayer's own MONO/STEREO")
        }
        assertEquals("C", AudioDekChannels.layout(1).alacOrder)
        assertEquals("L R", AudioDekChannels.layout(2).alacOrder)
    }

    @Test
    fun theMaskHasExactlyTheSpeakersOfTheChannelsInTheOrderTheyAreWritten() {
        for (n in 1..8) {
            val layout = AudioDekChannels.layout(n)
            assertEquals(n, layout.channels)
            assertEquals(n, layout.alacOrder.split(" ").size, "alac names for $n")
            assertEquals(n, layout.speakers.size)
            assertEquals(n, Integer.bitCount(layout.androidMask), "$n: bits in the mask (Format insists on it)")
            // The audio track reads the data in ascending speaker-bit order; audioDEK writes ALAC channel i at waveOrder(n)[i].
            val byPosition = arrayOfNulls<String>(n)
            layout.speakers.forEachIndexed { alacIndex, speaker -> byPosition[AudioDekChannels.waveOrder(n)[alacIndex]] = speaker }
            val maskOrder = bit.entries.filter { layout.androidMask and it.value != 0 }.sortedBy { it.value }.map { it.key }
            assertEquals(maskOrder, byPosition.toList(), "$n channels: speaker at each output position")
            assertEquals(n, AudioDekChannels.waveOrder(n).toSet().size, "a permutation")
        }
    }

    @Test
    fun theMasksAreTheAndroidLayoutsTheyAreNamedAfter() {
        val l = AudioDekChannels::layout
        assertEquals(0x41C, l(4).androidMask, "CHANNEL_OUT_SURROUND = FL FR FC BC (not QUAD)")
        assertEquals("SURROUND", l(4).maskName)
        assertEquals(0xFC, l(6).androidMask, "CHANNEL_OUT_5POINT1")
        assertEquals(0x4FC, l(7).androidMask, "5POINT1 + BACK_CENTER")
        assertEquals(0x18FC, l(8).androidMask, "CHANNEL_OUT_7POINT1_SURROUND")
        assertEquals(0x1C, l(3).androidMask)
        assertEquals(0xDC, l(5).androidMask)
        // 4 channels is the one ExoPlayer's count-based default gets wrong (QUAD = FL FR BL BR = 0xCC).
        assertTrue(l(4).androidMask != 0xCC)
        assertEquals("C L R Cs", l(4).alacOrder)
        assertEquals("C L R Ls Rs Cs LFE", l(7).alacOrder)
        assertEquals("C Lc Rc L R Ls Rs LFE", l(8).alacOrder)
    }
}
