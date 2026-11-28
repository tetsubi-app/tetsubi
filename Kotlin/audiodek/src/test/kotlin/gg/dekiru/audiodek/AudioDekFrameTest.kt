package gg.dekiru.audiodek

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Bit writer for building ALAC frames by hand. */
class BitWriter {
    private val bytes = ArrayList<Byte>()
    private var cur = 0
    private var used = 0

    fun put(value: Int, n: Int) {
        for (i in n - 1 downTo 0) {
            cur = (cur shl 1) or ((value ushr i) and 1)
            if (++used == 8) { bytes += cur.toByte(); cur = 0; used = 0 }
        }
    }

    fun toBytes(): ByteArray {
        val copy = ArrayList(bytes)
        if (used > 0) copy += (cur shl (8 - used)).toByte()
        return copy.toByteArray()
    }
}

internal fun cookie(bits: Int = 16, channels: Int = 2, frameLength: Int = 4096, pb: Int = 40, mb: Int = 10, kb: Int = 14, rate: Int = 44100): ByteArray {
    val w = BitWriter()
    w.put(frameLength, 32); w.put(0, 8); w.put(bits, 8); w.put(pb, 8); w.put(mb, 8); w.put(kb, 8); w.put(channels, 8)
    w.put(255, 16); w.put(13927, 32); w.put(1000000, 32); w.put(rate, 32)
    return w.toBytes()
}

/**
 * Frames built bit by bit, to reach decoder paths real encoders rarely or never
 * take (no encoder is needed): every residual is coded with the Golomb escape
 * (nine ones, then the raw value), which is valid for any value.
 */
class AudioDekFrameTest {

    private fun zigzag(v: Int) = if (v >= 0) v shl 1 else ((-v) shl 1) - 1

    /** A mono compressed element with the given prediction mode/order/coefs; residuals escape-coded in 16 bits. */
    private fun mono(residuals: IntArray, mode: Int, order: Int, coefs: IntArray = IntArray(0), denShift: Int = 9, partial: Int = residuals.size, extra: (BitWriter) -> Unit = {}): ByteArray {
        val w = BitWriter()
        extra(w)
        w.put(0, 3); w.put(0, 4); w.put(0, 12)
        w.put(8, 4)
        w.put(0, 16); w.put(partial, 16)
        w.put(0, 8); w.put(0, 8) // mix bits/res (unused for mono)
        w.put(mode, 4); w.put(denShift, 4); w.put(4, 3); w.put(order, 5)
        for (i in 0 until order) w.put((if (i < coefs.size) coefs[i] else 0) and 0xFFFF, 16)
        for (r in residuals) { w.put(0x1FF, 9); w.put(zigzag(r), 16) }
        w.put(7, 3)
        return w.toBytes()
    }

    private val mono16 = AudioDekAlacDecoder(AudioDekAlacConfig.parse(cookie(16, 1, 64)))

    private fun decodeMono(frame: ByteArray, n: Int): IntArray {
        val out = IntArray(64)
        assertEquals(n, mono16.decode(frame, 0, frame.size, out))
        return out.copyOf(n)
    }

    // Residuals large enough to keep the adaptive rice history out of the zero-run mode.
    private val residuals = intArrayOf(300, -250, 400, 100, -500, 350, 200, -300, 600, -120)

    @Test
    fun orderZeroCopiesTheResiduals() {
        assertContentEquals(residuals, decodeMono(mono(residuals, 0, 0), 10))
    }

    @Test
    fun order31IsARunningSum() {
        val expected = IntArray(10).also { var s = 0; for (i in it.indices) { s += residuals[i]; it[i] = s } }
        assertContentEquals(expected, decodeMono(mono(residuals, 0, 31), 10))
    }

    @Test
    fun nonZeroModeRunsTheRunningSumFirstThenThePredictor() {
        val expected = IntArray(10).also { var s = 0; for (i in it.indices) { s += residuals[i]; it[i] = s } }
        assertContentEquals(expected, decodeMono(mono(residuals, 15, 0), 10))
    }

    @Test
    fun runningSumWrapsAtTheSampleWidth() {
        val big = intArrayOf(30000, 30000, 30000)
        // 16-bit mono: 30000, 60000 -> -5536, 90000 -> 24464
        assertContentEquals(intArrayOf(30000, -5536, 24464), decodeMono(mono(big, 0, 31), 3).copyOf(3))
    }

    @Test
    fun partialFrameHasTheStatedLength() {
        assertContentEquals(residuals.copyOf(4), decodeMono(mono(residuals.copyOf(4), 0, 0), 4))
    }

    @Test
    fun dataStreamAndFillElementsAreSkipped() {
        val frame = mono(residuals, 0, 0) { w ->
            w.put(4, 3); w.put(1, 4); w.put(0, 1); w.put(3, 8); w.put(0xABCDEF, 24) // DSE with 3 bytes
            w.put(6, 3); w.put(2, 4); w.put(0x1234, 16) // FIL with 2 bytes
        }
        assertContentEquals(residuals, decodeMono(frame, 10))
    }

    @Test
    fun predictorWithZeroCoefficientsAddsTheSampleBeforeTheWindow() {
        // Order 2, coefficients 0 and small sample differences (the adaptation rounds to nothing):
        // after the two warm-up samples, out[i] = out[i - 3] + residual[i].
        val r = intArrayOf(100, -50, 30, -20, 40, -10, 5, -5, 20, -15)
        val expected = IntArray(10)
        expected[0] = r[0]
        expected[1] = expected[0] + r[1]
        expected[2] = expected[1] + r[2]
        for (i in 3 until 10) expected[i] = expected[i - 3] + r[i]
        assertContentEquals(expected, decodeMono(mono(r, 0, 2), 10))
    }

    @Test
    fun stereoEscapeFrameIsUncompressed() {
        val dec = AudioDekAlacDecoder(AudioDekAlacConfig.parse(cookie(16, 2, 64)))
        val l = intArrayOf(1, -2, 32767, -32768)
        val r = intArrayOf(5, 6, -7, 8)
        val w = BitWriter()
        w.put(1, 3); w.put(0, 4); w.put(0, 12); w.put(8 or 1, 4); w.put(0, 16); w.put(4, 16)
        for (i in 0 until 4) { w.put(l[i] and 0xFFFF, 16); w.put(r[i] and 0xFFFF, 16) }
        w.put(7, 3)
        val out = IntArray(128)
        val frame = w.toBytes()
        assertEquals(4, dec.decode(frame, 0, frame.size, out))
        assertContentEquals(intArrayOf(1, 5, -2, 6, 32767, -7, -32768, 8), out.copyOf(8))
    }

    @Test
    fun stereoMixIsUndoneWithTheMixFields() {
        val dec = AudioDekAlacDecoder(AudioDekAlacConfig.parse(cookie(16, 2, 64)))
        val u = intArrayOf(500, 600, 700, 800, 400)
        val v = intArrayOf(100, -200, 300, -400, 250)
        val w = BitWriter()
        w.put(1, 3); w.put(0, 4); w.put(0, 12); w.put(8, 4); w.put(0, 16); w.put(5, 16)
        w.put(2, 8); w.put(1, 8) // mixBits 2, mixRes 1
        repeat(2) { w.put(0, 4); w.put(9, 4); w.put(4, 3); w.put(0, 5) }
        for (x in u) { w.put(0x1FF, 9); w.put(zigzag(x), 17) }
        for (x in v) { w.put(0x1FF, 9); w.put(zigzag(x), 17) }
        w.put(7, 3)
        val frame = w.toBytes()
        // Unpredicted (order 0) "difference" signals u and v, then l = u + v - ((1*v) >> 2), r = l - v.
        val expected = IntArray(10)
        for (i in 0 until 5) {
            val l = u[i] + v[i] - ((1 * v[i]) shr 2)
            expected[2 * i] = l
            expected[2 * i + 1] = l - v[i]
        }
        val out = IntArray(128)
        assertEquals(5, dec.decode(frame, 0, frame.size, out))
        assertContentEquals(expected, out.copyOf(10))
    }

    @Test
    fun invalidFramesThrowTheDecoderException() {
        // Too many channels for the cookie: a CPE in a mono stream.
        val w = BitWriter()
        w.put(1, 3); w.put(0, 4); w.put(0, 12); w.put(1, 4); w.put(7, 3)
        val frame = w.toBytes()
        assertFailsWith<AudioDekAlacException> { mono16.decode(frame, 0, frame.size, IntArray(64)) }
        // An unsupported element (CCE).
        val cce = BitWriter().also { it.put(2, 3); it.put(0, 5) }.toBytes()
        assertFailsWith<AudioDekAlacException> { mono16.decode(cce, 0, cce.size, IntArray(64)) }
        // A shift of 3 bytes.
        val shift3 = BitWriter().also { it.put(0, 3); it.put(0, 4); it.put(0, 12); it.put(6, 4) }.toBytes()
        assertFailsWith<AudioDekAlacException> { mono16.decode(shift3, 0, shift3.size, IntArray(64)) }
        // More samples than the cookie's frame length.
        val long = mono(residuals, 0, 0, partial = 65)
        assertFailsWith<AudioDekAlacException> { mono16.decode(long, 0, long.size, IntArray(64)) }
        // Output buffer too small.
        val ok = mono(residuals, 0, 0)
        assertFailsWith<AudioDekAlacException> { mono16.decode(ok, 0, ok.size, IntArray(5)) }
        // Bad ranges and an empty frame.
        assertFailsWith<AudioDekAlacException> { mono16.decode(ok, 5, ok.size, IntArray(64)) }
        assertFailsWith<AudioDekAlacException> { mono16.decode(ok, 0, 0, IntArray(64)) }
    }

    @Test
    fun theDecoderWorksAgainAfterAnError() {
        val bad = byteArrayOf(0x60)
        assertFailsWith<AudioDekAlacException> { mono16.decode(bad, 0, bad.size, IntArray(64)) }
        assertContentEquals(residuals, decodeMono(mono(residuals, 0, 0), 10))
        mono16.reset()
        assertContentEquals(residuals, decodeMono(mono(residuals, 0, 0), 10))
    }

    @Test
    fun cookieForms() {
        val plain = cookie(24, 6, 4096, rate = 48000)
        val c = AudioDekAlacConfig.parse(plain)
        assertEquals(4096, c.frameLength); assertEquals(24, c.bitDepth); assertEquals(6, c.channels)
        assertEquals(40, c.pb); assertEquals(10, c.mb); assertEquals(14, c.kb); assertEquals(48000L, c.sampleRate)
        // With the 12-byte 'alac' box header (36 bytes).
        val boxed = byteArrayOf(0, 0, 0, 36, 'a'.code.toByte(), 'l'.code.toByte(), 'a'.code.toByte(), 'c'.code.toByte(), 0, 0, 0, 0) + plain
        assertEquals(36, boxed.size)
        assertEquals(24, AudioDekAlacConfig.parse(boxed).bitDepth)
        // Behind a 'frma' box too.
        val framed = byteArrayOf(0, 0, 0, 12, 'f'.code.toByte(), 'r'.code.toByte(), 'm'.code.toByte(), 'a'.code.toByte(), 'a'.code.toByte(), 'l'.code.toByte(), 'a'.code.toByte(), 'c'.code.toByte()) + boxed
        assertEquals(24, AudioDekAlacConfig.parse(framed).bitDepth)
        assertEquals(true, c.sameAs(AudioDekAlacConfig.parse(plain)))
        assertEquals(false, c.sameAs(AudioDekAlacConfig.parse(cookie(16, 6, 4096, rate = 48000))))
    }

    @Test
    fun badCookiesAreRejected() {
        assertFailsWith<AudioDekAlacException> { AudioDekAlacConfig.parse(ByteArray(0)) }
        assertFailsWith<AudioDekAlacException> { AudioDekAlacConfig.parse(ByteArray(23)) }
        assertFailsWith<AudioDekAlacException> { AudioDekAlacConfig.parse(cookie(8)) }
        assertFailsWith<AudioDekAlacException> { AudioDekAlacConfig.parse(cookie(16, 9)) }
        assertFailsWith<AudioDekAlacException> { AudioDekAlacConfig.parse(cookie(16, 0)) }
        assertFailsWith<AudioDekAlacException> { AudioDekAlacConfig.parse(cookie(16, 2, frameLength = 0)) }
        assertFailsWith<AudioDekAlacException> { AudioDekAlacConfig.parse(cookie(16, 2, frameLength = 1 shl 20)) }
        assertFailsWith<AudioDekAlacException> { AudioDekAlacConfig.parse(cookie(16, 2, kb = 0)) }
        assertFailsWith<AudioDekAlacException> { AudioDekAlacConfig.parse(cookie(16, 2, pb = 0)) }
    }

    @Test
    fun theTaskCookieFromADeviceParses() {
        val hex = "000010000010280a0e0200ff00003667000f43b70000ac44"
        val bytes = ByteArray(24) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val c = AudioDekAlacConfig.parse(bytes)
        assertEquals(4096, c.frameLength); assertEquals(16, c.bitDepth); assertEquals(2, c.channels); assertEquals(44100L, c.sampleRate)
        assertEquals(13927L, c.maxFrameBytes)
    }

    @Test
    fun channelOrderMapsAlacToWave() {
        // 5.1: ALAC C L R Ls Rs LFE -> WAVE L R C LFE Ls Rs.
        val src = intArrayOf(10, 11, 12, 13, 14, 15) // C L R Ls Rs LFE
        val dst = IntArray(6)
        AudioDekChannels.toWave(src, dst, 1, 6)
        assertContentEquals(intArrayOf(11, 12, 10, 15, 13, 14), dst)
        // 7.1: ALAC C Lc Rc L R Ls Rs LFE -> L R C LFE Ls Rs Lc Rc.
        val s8 = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
        val d8 = IntArray(8)
        AudioDekChannels.toWave(s8, d8, 1, 8)
        assertContentEquals(intArrayOf(3, 4, 0, 7, 5, 6, 1, 2), d8)
        // Mono and stereo stay as they are.
        AudioDekChannels.toWave(intArrayOf(1, 2), dst, 1, 2)
        assertContentEquals(intArrayOf(1, 2), dst.copyOf(2))
    }
}
