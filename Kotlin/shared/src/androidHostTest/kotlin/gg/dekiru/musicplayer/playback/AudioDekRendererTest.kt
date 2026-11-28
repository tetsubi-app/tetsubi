package gg.dekiru.musicplayer.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import gg.dekiru.audiodek.AudioDekAlacException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pure parts of the audioDEK Media3 renderer: encodings, PCM bytes, cookies, reuse, log line, errors. */
class AudioDekRendererTest {

    private fun cookie(bits: Int, channels: Int, frameLength: Int = 4096, rate: Int = 44100): ByteArray {
        val b = ByteArray(24)
        fun put(at: Int, v: Long, n: Int) { for (i in 0 until n) b[at + i] = (v shr (8 * (n - 1 - i))).toByte() }
        put(0, frameLength.toLong(), 4); b[5] = bits.toByte(); b[6] = 40; b[7] = 10; b[8] = 14; b[9] = channels.toByte()
        put(10, 255, 2); put(12, 13927, 4); put(16, 1_000_000, 4); put(20, rate.toLong(), 4)
        return b
    }

    @Test
    fun onlyAlacIsHandled() {
        assertTrue(AudioDekFormats.handles(MimeTypes.AUDIO_ALAC))
        assertFalse(AudioDekFormats.handles(MimeTypes.AUDIO_FLAC))
        assertFalse(AudioDekFormats.handles(MimeTypes.AUDIO_AAC))
        assertFalse(AudioDekFormats.handles(null))
    }

    @Test
    fun pcmEncodingFollowsTheBitDepth() {
        assertEquals(C.ENCODING_PCM_16BIT, AudioDekFormats.pcmEncoding(16))
        assertEquals(C.ENCODING_PCM_24BIT, AudioDekFormats.pcmEncoding(24))
        assertEquals(C.ENCODING_PCM_24BIT, AudioDekFormats.pcmEncoding(20)) // left-justified in 24 bits
        assertEquals(C.ENCODING_PCM_32BIT, AudioDekFormats.pcmEncoding(32))
        assertFailsWith<AudioDekAlacException> { AudioDekFormats.pcmEncoding(8) }
        assertEquals(listOf(2, 3, 3, 4), listOf(16, 20, 24, 32).map { AudioDekFormats.bytesPerSample(it) })
    }

    @Test
    fun pcmBytesAreLittleEndianAndTwentyBitIsShiftedLeftFour() {
        fun bytes(bits: Int, vararg v: Int): List<Int> {
            val out = ByteBuffer.allocate(v.size * AudioDekFormats.bytesPerSample(bits)).order(ByteOrder.BIG_ENDIAN)
            AudioDekFormats.writePcm(v, v.size, bits, out)
            assertEquals(ByteOrder.BIG_ENDIAN, out.order()) // the caller's order is put back
            return out.array().map { it.toInt() and 0xFF }
        }
        assertEquals(listOf(0x34, 0x12, 0xFF, 0xFF), bytes(16, 0x1234, -1))
        assertEquals(listOf(0x56, 0x34, 0x12), bytes(24, 0x123456))
        assertEquals(listOf(0xFF, 0xFF, 0xFF), bytes(24, -1))
        // 20-bit 0x12345 -> 24-bit 0x123450
        assertEquals(listOf(0x50, 0x34, 0x12), bytes(20, 0x12345))
        assertEquals(listOf(0x00, 0x00, 0x80), bytes(20, -0x80000)) // most negative 20-bit value
        assertEquals(listOf(0x78, 0x56, 0x34, 0x12), bytes(32, 0x12345678))
    }

    @Test
    fun theCookieComesFromTheFirstInitializationData() {
        val config = AudioDekFormats.config(listOf(cookie(24, 6, rate = 48000)))
        assertEquals(24, config.bitDepth); assertEquals(6, config.channels); assertEquals(48000L, config.sampleRate)
        assertFailsWith<AudioDekAlacException> { AudioDekFormats.config(emptyList()) }
        assertFailsWith<AudioDekAlacException> { AudioDekFormats.config(listOf(ByteArray(5))) }
    }

    @Test
    fun aDecoderIsReusedOnlyForAnIdenticalCookie() {
        val a = cookie(16, 2)
        assertTrue(AudioDekFormats.canReuse(listOf(a), listOf(a.copyOf())))
        assertFalse(AudioDekFormats.canReuse(listOf(a), listOf(cookie(24, 2))))
        assertFalse(AudioDekFormats.canReuse(listOf(a), listOf(cookie(16, 2, rate = 48000))))
        assertFalse(AudioDekFormats.canReuse(emptyList(), emptyList()))
        assertFalse(AudioDekFormats.canReuse(listOf(a), emptyList()))
    }

    @Test
    fun theLogLineHasNoUrlOrToken() {
        assertEquals("alac decode: item=abc123 bits=16 channels=2 rate=44100 mode=audioDEK", AudioDekFormats.logLine("abc123", 16, 2, 44100))
    }

    @Test
    fun theOutputMaskAndLogLineNameTheLayoutForEveryChannelCount() {
        // 1 and 2: no mask set (ExoPlayer's MONO/STEREO).
        assertEquals(Format.NO_VALUE, AudioDekFormats.channelMask(1))
        assertEquals(Format.NO_VALUE, AudioDekFormats.channelMask(2))
        assertEquals("audioDEK output: channels=2 mask=default(STEREO) layout=L-R", AudioDekFormats.outputLine(2))
        assertEquals(mapOf(3 to 0x1C, 4 to 0x41C, 5 to 0xDC, 6 to 0xFC, 7 to 0x4FC, 8 to 0x18FC), (3..8).associateWith { AudioDekFormats.channelMask(it) })
        // The mask Format accepts has as many bits as channels (Format checks this).
        for (n in 3..8) assertEquals(n, Integer.bitCount(AudioDekFormats.channelMask(n)))
        assertEquals("audioDEK output: channels=4 mask=SURROUND(0x41c) layout=C-L-R-Cs", AudioDekFormats.outputLine(4))
        assertEquals("audioDEK output: channels=6 mask=5POINT1(0xfc) layout=C-L-R-Ls-Rs-LFE", AudioDekFormats.outputLine(6))
        assertEquals("audioDEK output: channels=8 mask=7POINT1_SURROUND(0x18fc) layout=C-Lc-Rc-L-R-Ls-Rs-LFE", AudioDekFormats.outputLine(8))
    }

    @Test
    fun theFrameDecoderReordersMultichannelAndConvertsAFrame() {
        // A 5.1 24-bit "escape" frame is too long to build by hand here; check a mono 16-bit frame end to end.
        val config = cookie(16, 1, frameLength = 64)
        val decoder = AudioDekFrameDecoder(AudioDekFormats.config(listOf(config)))
        assertEquals(C.ENCODING_PCM_16BIT, decoder.pcmEncoding)
        assertEquals(64 * 2, decoder.maxOutputBytes)
        // SCE, uncompressed (escape), partial frame of 3 samples: 0x0001, 0xFFFF (-1), 0x7FFF.
        val bits = StringBuilder("000" + "0000" + "000000000000" + "1001" + "0".repeat(16) + "0000000000000011")
        for (v in intArrayOf(1, 0xFFFF, 0x7FFF)) bits.append(v.toString(2).padStart(16, '0'))
        bits.append("111")
        while (bits.length % 8 != 0) bits.append('0')
        val frame = ByteArray(bits.length / 8) { bits.substring(it * 8, it * 8 + 8).toInt(2).toByte() }
        var out = ByteBuffer.allocate(0)
        val frames = decoder.decode(ByteBuffer.wrap(frame), frame.size) { n -> ByteBuffer.allocate(n).also { out = it } }
        assertEquals(3, frames)
        assertContentEquals(byteArrayOf(1, 0, -1, -1, -1, 0x7F), out.array())
    }

    @Test
    fun aCorruptFrameBecomesTheDecoderException() {
        val decoder = AudioDekFrameDecoder(AudioDekFormats.config(listOf(cookie(16, 2))))
        val junk = ByteBuffer.wrap(byteArrayOf(0x3F, 0x01))
        // The Media3 shell turns this into AudioDekDecoderException (decoding failed, ERROR_CODE_DECODING_FAILED 4003).
        assertFailsWith<AudioDekAlacException> { decoder.decode(junk, 2) { ByteBuffer.allocate(it) } }
        // 4003 is one of the codes that sends the item to the converted stream.
        assertTrue(4003 in DirectPlayFallback.DECODE_ERROR_CODES)
    }
}
