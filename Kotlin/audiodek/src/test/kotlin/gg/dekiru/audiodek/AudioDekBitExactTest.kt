package gg.dekiru.audiodek

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * audioDEK bit-exact decode. Every fixture in `src/test/resources/alac` is an
 * ALAC `.m4a` made by Apple's `afconvert` (`.afc`) or FFmpeg's `alac` encoder
 * (`.ff`) from a signal in [AlacSignals]. Each is decoded and every sample is
 * compared with the source (exact equality). The source PCM's SHA-256 is in
 * `manifest.txt`; [sourceSignalsMatchTheManifest] proves the generator still
 * produces what the encoders were given. Names say `<signal>_<encoder>_exact_container<bits>`:
 * the container is the bit depth the encoder stored (afconvert keeps 20-bit,
 * ffmpeg stores 20-bit audio in 24 bits, left-justified, and 32-bit in 24 bits).
 * See README.md and `tools/make-fixtures.sh` in this module.
 */
class AudioDekBitExactTest {

    private fun resource(name: String): ByteArray =
        javaClass.getResourceAsStream("/alac/$name")?.use { it.readBytes() } ?: fail("missing fixture $name")

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun signal(name: String) = AlacSignals.all.first { it.name == name }

    /** Decodes a whole file: (bit depth stored in the file, interleaved samples in WAVE channel order). */
    private fun decodeFile(name: String): Pair<Int, IntArray> {
        val mp4 = Mp4AlacSamples(resource(name))
        val config = AudioDekAlacConfig.parse(mp4.cookie)
        val decoder = AudioDekAlacDecoder(config)
        val frame = IntArray(decoder.maxSamples)
        val wave = IntArray(decoder.maxSamples)
        val out = ArrayList<Int>()
        for (i in mp4.samples.indices) {
            val frames = decoder.decode(mp4.frame(i), 0, mp4.samples[i].second, frame)
            AudioDekChannels.toWave(frame, wave, frames, config.channels)
            for (s in 0 until frames * config.channels) out += wave[s]
        }
        return config.bitDepth to out.toIntArray()
    }

    private fun check(signal: AlacSignal, encoder: String, container: Int) {
        val name = "${signal.name}.$encoder.m4a"
        val source = signal.samples()
        val (depth, decoded) = decodeFile(name)
        assertEquals(container, depth, "$name: container bit depth")
        assertEquals(source.size, decoded.size, "$name: sample count")
        val up = depth - signal.bits // 20-bit audio in a 24-bit container is left-justified
        for (i in source.indices) {
            val expected = source[i] shl up
            if (decoded[i] != expected) fail("$name: sample $i (frame ${i / signal.channels}, channel ${i % signal.channels}) is ${decoded[i]}, expected $expected")
        }
    }

    private fun checkTruncated(signal: AlacSignal, encoder: String) {
        val name = "${signal.name}.$encoder.m4a"
        val (depth, decoded) = decodeFile(name)
        assertEquals(24, depth)
        val source = signal.samples()
        assertEquals(source.size, decoded.size)
        for (i in source.indices) assertEquals(source[i] shr 8, decoded[i], "$name sample $i")
    }

    @Test
    fun sourceSignalsMatchTheManifest() {
        val manifest = String(resource("manifest.txt")).lines().filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line -> line.split(" ").let { it[0] to it[1] } }
        assertEquals(AlacSignals.all.map { it.name }.toSet(), manifest.keys)
        for (s in AlacSignals.all) {
            assertEquals(manifest[s.name], sha256(AlacSignals.littleEndianPcm(s.samples(), (s.bits + 7) / 8 * 8)), "${s.name}: source PCM changed")
        }
    }

    @Test
    fun everySignalHasBothEncodersFixtures() {
        for (s in AlacSignals.all) for (e in listOf("afc", "ff")) assertTrue(javaClass.getResource("/alac/${s.name}.$e.m4a") != null, "${s.name}.$e")
    }

    @Test
    fun silence_16_st_44k_afconvert_exact_container16() = check(signal("silence_16_st_44k"), "afc", 16)

    @Test
    fun silence_16_st_44k_ffmpeg_exact_container16() = check(signal("silence_16_st_44k"), "ff", 16)

    @Test
    fun square_16_st_44k_afconvert_exact_container16() = check(signal("square_16_st_44k"), "afc", 16)

    @Test
    fun square_16_st_44k_ffmpeg_exact_container16() = check(signal("square_16_st_44k"), "ff", 16)

    @Test
    fun impulse_16_st_44k_afconvert_exact_container16() = check(signal("impulse_16_st_44k"), "afc", 16)

    @Test
    fun impulse_16_st_44k_ffmpeg_exact_container16() = check(signal("impulse_16_st_44k"), "ff", 16)

    @Test
    fun sine_16_st_44k_afconvert_exact_container16() = check(signal("sine_16_st_44k"), "afc", 16)

    @Test
    fun sine_16_st_44k_ffmpeg_exact_container16() = check(signal("sine_16_st_44k"), "ff", 16)

    @Test
    fun noise_16_st_44k_afconvert_exact_container16() = check(signal("noise_16_st_44k"), "afc", 16)

    @Test
    fun noise_16_st_44k_ffmpeg_exact_container16() = check(signal("noise_16_st_44k"), "ff", 16)

    @Test
    fun music_16_st_44k_afconvert_exact_container16() = check(signal("music_16_st_44k"), "afc", 16)

    @Test
    fun music_16_st_44k_ffmpeg_exact_container16() = check(signal("music_16_st_44k"), "ff", 16)

    @Test
    fun music_16_mono_44k_afconvert_exact_container16() = check(signal("music_16_mono_44k"), "afc", 16)

    @Test
    fun music_16_mono_44k_ffmpeg_exact_container16() = check(signal("music_16_mono_44k"), "ff", 16)

    @Test
    fun corr_16_st_44k_afconvert_exact_container16() = check(signal("corr_16_st_44k"), "afc", 16)

    @Test
    fun corr_16_st_44k_ffmpeg_exact_container16() = check(signal("corr_16_st_44k"), "ff", 16)

    @Test
    fun uncorr_16_st_44k_afconvert_exact_container16() = check(signal("uncorr_16_st_44k"), "afc", 16)

    @Test
    fun uncorr_16_st_44k_ffmpeg_exact_container16() = check(signal("uncorr_16_st_44k"), "ff", 16)

    @Test
    fun music_16_st_48k_afconvert_exact_container16() = check(signal("music_16_st_48k"), "afc", 16)

    @Test
    fun music_16_st_48k_ffmpeg_exact_container16() = check(signal("music_16_st_48k"), "ff", 16)

    @Test
    fun music_24_st_96k_afconvert_exact_container24() = check(signal("music_24_st_96k"), "afc", 24)

    @Test
    fun music_24_st_96k_ffmpeg_exact_container24() = check(signal("music_24_st_96k"), "ff", 24)

    @Test
    fun short_16_st_44k_afconvert_exact_container16() = check(signal("short_16_st_44k"), "afc", 16)

    @Test
    fun short_16_st_44k_ffmpeg_exact_container16() = check(signal("short_16_st_44k"), "ff", 16)

    @Test
    fun odd_16_st_44k_afconvert_exact_container16() = check(signal("odd_16_st_44k"), "afc", 16)

    @Test
    fun odd_16_st_44k_ffmpeg_exact_container16() = check(signal("odd_16_st_44k"), "ff", 16)

    @Test
    fun music_20_st_44k_afconvert_exact_container20() = check(signal("music_20_st_44k"), "afc", 20)

    @Test
    fun music_20_st_44k_ffmpeg_exact_container24() = check(signal("music_20_st_44k"), "ff", 24)

    @Test
    fun music_24_st_44k_afconvert_exact_container24() = check(signal("music_24_st_44k"), "afc", 24)

    @Test
    fun music_24_st_44k_ffmpeg_exact_container24() = check(signal("music_24_st_44k"), "ff", 24)

    @Test
    fun noise_24_st_44k_afconvert_exact_container24() = check(signal("noise_24_st_44k"), "afc", 24)

    @Test
    fun noise_24_st_44k_ffmpeg_exact_container24() = check(signal("noise_24_st_44k"), "ff", 24)

    @Test
    fun square_24_st_44k_afconvert_exact_container24() = check(signal("square_24_st_44k"), "afc", 24)

    @Test
    fun square_24_st_44k_ffmpeg_exact_container24() = check(signal("square_24_st_44k"), "ff", 24)

    @Test
    fun music_32_st_44k_afconvert_exact_container32() = check(signal("music_32_st_44k"), "afc", 32)

    /** ffmpeg's encoder has no 32-bit mode: it stores 24 bits, so this pair is not an exact 32-bit comparison (skipped). It checks the 24-bit result is the source's top 24 bits. */
    @Test
    fun music_32_st_44k_ffmpeg_SKIPPED_ffmpeg_stores_32bit_as_24bit() = checkTruncated(signal("music_32_st_44k"), "ff")

    @Test
    fun noise_32_st_44k_afconvert_exact_container32() = check(signal("noise_32_st_44k"), "afc", 32)

    /** ffmpeg's encoder has no 32-bit mode: it stores 24 bits, so this pair is not an exact 32-bit comparison (skipped). It checks the 24-bit result is the source's top 24 bits. */
    @Test
    fun noise_32_st_44k_ffmpeg_SKIPPED_ffmpeg_stores_32bit_as_24bit() = checkTruncated(signal("noise_32_st_44k"), "ff")

    @Test
    fun music_24_mono_44k_afconvert_exact_container24() = check(signal("music_24_mono_44k"), "afc", 24)

    @Test
    fun music_24_mono_44k_ffmpeg_exact_container24() = check(signal("music_24_mono_44k"), "ff", 24)

    @Test
    fun music_16_51_48k_afconvert_exact_container16() = check(signal("music_16_51_48k"), "afc", 16)

    @Test
    fun music_16_51_48k_ffmpeg_exact_container16() = check(signal("music_16_51_48k"), "ff", 16)

    @Test
    fun music_24_51_48k_afconvert_exact_container24() = check(signal("music_24_51_48k"), "afc", 24)

    @Test
    fun music_24_51_48k_ffmpeg_exact_container24() = check(signal("music_24_51_48k"), "ff", 24)

    @Test
    fun music_16_71_48k_afconvert_exact_container16() = check(signal("music_16_71_48k"), "afc", 16)

    @Test
    fun music_16_71_48k_ffmpeg_exact_container16() = check(signal("music_16_71_48k"), "ff", 16)
}
