package gg.dekiru.audiodek

/*
 * audioDEK: Kotlin ALAC decoder. Its structure follows Apple's open-source ALAC
 * reference decoder (https://github.com/macosforge/alac, Apache License 2.0,
 * Copyright (c) 2011 Apple Inc.): the element parsing, the adaptive Golomb
 * (rice) residual decoder, the two-pass adaptive FIR predictor and the stereo
 * un-mixing. See NOTICE-ALAC.md.
 */

/** A frame that cannot be decoded (corrupt, truncated or unsupported). Never thrown for anything else. */
class AudioDekAlacException(message: String) : Exception(message)

/**
 * The 24-byte `ALACSpecificConfig` ("magic cookie"). All fields are big-endian
 * in the file; [parse] also takes the 36-byte form that keeps the 12-byte
 * `alac` box header (and longer forms that end with it, e.g. a `frma` wrapper).
 */
class AudioDekAlacConfig(
    val frameLength: Int,
    val compatibleVersion: Int,
    val bitDepth: Int,
    val pb: Int,
    val mb: Int,
    val kb: Int,
    val channels: Int,
    val maxRun: Int,
    val maxFrameBytes: Long,
    val avgBitRate: Long,
    val sampleRate: Long,
) {
    companion object {
        const val SIZE = 24

        fun parse(cookie: ByteArray): AudioDekAlacConfig {
            val start = when {
                cookie.size == SIZE -> 0
                // [size]['alac'][version/flags] + config, possibly after a 'frma' box.
                cookie.size >= SIZE + 12 && cookie.tag(cookie.size - SIZE - 8) == "alac" -> cookie.size - SIZE
                else -> throw AudioDekAlacException("ALAC cookie has an unexpected size (${cookie.size} bytes)")
            }
            fun u8(i: Int) = cookie[start + i].toInt() and 0xFF
            fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
            fun u32(i: Int) = (u16(i).toLong() shl 16) or u16(i + 2).toLong()
            val config = AudioDekAlacConfig(
                frameLength = u32(0).let { if (it > Int.MAX_VALUE) -1 else it.toInt() },
                compatibleVersion = u8(4),
                bitDepth = u8(5),
                pb = u8(6),
                mb = u8(7),
                kb = u8(8),
                channels = u8(9),
                maxRun = u16(10),
                maxFrameBytes = u32(12),
                avgBitRate = u32(16),
                sampleRate = u32(20),
            )
            config.validate()
            return config
        }

        private fun ByteArray.tag(at: Int): String =
            if (at < 0 || at + 4 > size) "" else String(CharArray(4) { (this[at + it].toInt() and 0xFF).toChar() })
    }

    private fun validate() {
        if (compatibleVersion != 0) throw AudioDekAlacException("ALAC cookie version $compatibleVersion is not supported")
        if (frameLength !in 1..MAX_FRAME_LENGTH) throw AudioDekAlacException("ALAC frame length $frameLength is out of range")
        if (bitDepth != 16 && bitDepth != 20 && bitDepth != 24 && bitDepth != 32) {
            throw AudioDekAlacException("ALAC bit depth $bitDepth is not supported")
        }
        if (channels !in 1..8) throw AudioDekAlacException("ALAC channel count $channels is not supported")
        if (kb !in 1..31) throw AudioDekAlacException("ALAC cookie has an invalid history limit ($kb)")
        if (mb > 0xFFFF || pb == 0) throw AudioDekAlacException("ALAC cookie has invalid rice parameters")
    }

    /** Equal cookies (same stream parameters), for deciding whether a decoder can be reused. */
    fun sameAs(other: AudioDekAlacConfig): Boolean =
        frameLength == other.frameLength && bitDepth == other.bitDepth && pb == other.pb && mb == other.mb &&
            kb == other.kb && channels == other.channels && maxRun == other.maxRun && sampleRate == other.sampleRate
}

internal const val MAX_FRAME_LENGTH = 32768

/**
 * Decodes ALAC frames (one MP4 sample each) to interleaved integer PCM.
 * Not thread safe; keeps scratch buffers, no per-sample allocation. Output
 * samples are right-justified two's complement values of the file's bit depth
 * (16, 20, 24 or 32 bits), channels in ALAC's own order (see [AudioDekChannels]).
 */
class AudioDekAlacDecoder(val config: AudioDekAlacConfig) {

    private val frameLength = config.frameLength
    private val mixU = IntArray(frameLength)
    private val mixV = IntArray(frameLength)
    private val predictor = IntArray(frameLength)
    private val shiftBuffer = IntArray(frameLength * 2)
    private val coefsU = IntArray(32)
    private val coefsV = IntArray(32)

    /** The frame being decoded, copied with zero padding so bit peeks never leave the array. */
    private var buf = ByteArray(0)
    private var pos = 0
    private var endBits = 0

    /** Largest number of Ints [decode] can write. */
    val maxSamples: Int get() = frameLength * config.channels

    /** Nothing carries over between ALAC frames; kept so a renderer's flush has one place to call. */
    fun reset() {
        pos = 0
        endBits = 0
    }

    /**
     * Decodes the frame in `input[offset until offset+length]` into [out]
     * (`frames * channels` Ints, interleaved) and returns the frames decoded.
     * @throws AudioDekAlacException for a corrupt, truncated or unsupported frame.
     */
    fun decode(input: ByteArray, offset: Int, length: Int, out: IntArray): Int {
        if (offset < 0 || length < 0 || offset > input.size - length) throw AudioDekAlacException("ALAC frame range is outside its buffer")
        if (length == 0) throw AudioDekAlacException("ALAC frame is empty")
        if (buf.size < length + PAD) buf = ByteArray(length + PAD + 1024)
        input.copyInto(buf, 0, offset, offset + length)
        buf.fill(0, length, length + PAD)
        pos = 0
        endBits = length * 8

        val channels = config.channels
        var channelIndex = 0
        var numSamples = -1
        while (true) {
            when (val tag = bits(3)) {
                TAG_SCE, TAG_LFE -> {
                    if (channelIndex + 1 > channels) throw AudioDekAlacException("ALAC frame has more channels than its cookie")
                    numSamples = element(false, channelIndex, out, numSamples)
                    channelIndex += 1
                }
                TAG_CPE -> {
                    if (channelIndex + 2 > channels) throw AudioDekAlacException("ALAC frame has more channels than its cookie")
                    numSamples = element(true, channelIndex, out, numSamples)
                    channelIndex += 2
                }
                TAG_DSE -> {
                    bits(4)
                    val align = bits(1)
                    var count = bits(8)
                    if (count == 255) count += bits(8)
                    if (align != 0) pos = (pos + 7) and 7.inv()
                    skip(count * 8)
                }
                TAG_FIL -> {
                    var count = bits(4)
                    if (count == 15) count += bits(8) - 1
                    skip(count * 8)
                }
                TAG_END -> break
                else -> throw AudioDekAlacException("ALAC element type $tag is not supported")
            }
        }
        if (channelIndex != channels || numSamples < 0) throw AudioDekAlacException("ALAC frame has $channelIndex of $channels channels")
        return numSamples
    }

    private fun bits(n: Int): Int {
        if (pos + n > endBits) throw AudioDekAlacException("ALAC frame is truncated")
        val v = peek(n)
        pos += n
        return v
    }

    private fun skip(n: Int) {
        if (n < 0 || pos + n > endBits) throw AudioDekAlacException("ALAC frame is truncated")
        pos += n
    }

    /** The next [n] (1..32) bits without moving; bytes past the frame read as zero (the padding). */
    private fun peek(n: Int): Int {
        val i = pos ushr 3
        val b = buf
        val v = ((b[i].toLong() and 0xFF) shl 32) or ((b[i + 1].toLong() and 0xFF) shl 24) or
            ((b[i + 2].toLong() and 0xFF) shl 16) or ((b[i + 3].toLong() and 0xFF) shl 8) or (b[i + 4].toLong() and 0xFF)
        return ((v ushr (40 - (pos and 7) - n)) and ((1L shl n) - 1)).toInt()
    }

    /** Decodes one SCE/LFE (mono) or CPE (stereo pair) element; the tag is already read. */
    private fun element(stereo: Boolean, channelIndex: Int, out: IntArray, expected: Int): Int {
        val bitDepth = config.bitDepth
        bits(4) // element instance tag
        bits(12) // unused
        val header = bits(4)
        val partial = header ushr 3
        val bytesShifted = (header ushr 1) and 3
        val escape = header and 1
        if (bytesShifted == 3) throw AudioDekAlacException("ALAC frame has an invalid shift")
        var numSamples = frameLength
        if (partial != 0) {
            val hi = bits(16)
            val lo = bits(16)
            if (hi != 0) throw AudioDekAlacException("ALAC frame has too many samples")
            numSamples = lo
        }
        if (numSamples > frameLength) throw AudioDekAlacException("ALAC frame has $numSamples samples (the cookie says at most $frameLength)")
        if (expected >= 0 && numSamples != expected) throw AudioDekAlacException("ALAC elements disagree on the frame length")
        val channels = config.channels
        if (out.size < numSamples * channels) throw AudioDekAlacException("ALAC output buffer is too small")

        var mixBits = 0
        var mixRes = 0
        var shift = 0
        if (escape == 0) {
            val chanBits = bitDepth - bytesShifted * 8 + (if (stereo) 1 else 0)
            // Mono elements carry the two mix fields too (always 0); only stereo uses them.
            mixBits = bits(8)
            mixRes = bits(8).toByte().toInt()
            val modeU = bits(4)
            val denU = bits(4)
            val pbU = bits(3)
            val numU = bits(5)
            for (i in 0 until numU) coefsU[i] = bits(16).toShort().toInt()
            var modeV = 0
            var denV = 0
            var pbV = 0
            var numV = 0
            if (stereo) {
                modeV = bits(4)
                denV = bits(4)
                pbV = bits(3)
                numV = bits(5)
                for (i in 0 until numV) coefsV[i] = bits(16).toShort().toInt()
            }
            var shiftPos = -1
            if (bytesShifted != 0) {
                shift = bytesShifted * 8
                shiftPos = pos
                skip(shift * (if (stereo) 2 else 1) * numSamples)
            }
            decodeChannel(mixU, numSamples, chanBits, pbU, modeU, coefsU, numU, denU)
            if (stereo) decodeChannel(mixV, numSamples, chanBits, pbV, modeV, coefsV, numV, denV)
            if (shiftPos >= 0) {
                val resume = pos
                pos = shiftPos
                val count = numSamples * (if (stereo) 2 else 1)
                for (i in 0 until count) {
                    shiftBuffer[i] = peek(shift)
                    pos += shift
                }
                pos = resume
            }
        } else {
            val rawBits = bitDepth
            val up = 32 - rawBits
            if (pos + rawBits * (if (stereo) 2 else 1) * numSamples > endBits) throw AudioDekAlacException("ALAC frame is truncated")
            for (i in 0 until numSamples) {
                mixU[i] = (rawSample(rawBits) shl up) shr up
                if (stereo) mixV[i] = (rawSample(rawBits) shl up) shr up
            }
        }
        write(out, channelIndex, numSamples, stereo, mixBits, mixRes, shift)
        return numSamples
    }

    private fun rawSample(n: Int): Int {
        val v = peek(n)
        pos += n
        return v
    }

    private fun decodeChannel(dst: IntArray, num: Int, chanBits: Int, pbFactor: Int, mode: Int, coefs: IntArray, numCoefs: Int, denShift: Int) {
        decodeResiduals(predictor, num, chanBits, config.pb * pbFactor / 4)
        if (mode == 0) {
            predict(predictor, dst, num, coefs, numCoefs, chanBits, denShift)
        } else {
            predict(predictor, predictor, num, coefs, 31, chanBits, 0)
            predict(predictor, dst, num, coefs, numCoefs, chanBits, denShift)
        }
    }

    private fun log2(v: Int): Int = if (v <= 0) 0 else 31 - v.countLeadingZeroBits()

    /** Adaptive Golomb decoding of [num] residuals into [dst]. */
    private fun decodeResiduals(dst: IntArray, num: Int, bps: Int, pb: Int) {
        val kb = config.kb
        var history = config.mb
        var signModifier = 0
        var i = 0
        while (i < num) {
            var k = log2((history ushr 9) + 3)
            if (k > kb) k = kb
            val x = scalar(k, bps) + signModifier
            signModifier = 0
            dst[i++] = (x ushr 1) xor -(x and 1)
            history = if ((x ushr 16) != 0) 0xFFFF else history + x * pb - ((history * pb) ushr 9)
            if (pos > endBits) throw AudioDekAlacException("ALAC frame is truncated")

            // A run of zeros.
            if (history < 128 && i < num) {
                k = 7 - log2(history) + ((history + 16) ushr 6)
                if (k > kb) k = kb
                val block = scalar(k, 16)
                if (block > 0) {
                    if (block > num - i) throw AudioDekAlacException("ALAC frame has an invalid zero run")
                    dst.fill(0, i, i + block)
                    i += block
                }
                if (block in 0..0xFFFF) signModifier = 1
                history = 0
                if (pos > endBits) throw AudioDekAlacException("ALAC frame is truncated")
            }
        }
    }

    /** One rice/Golomb value: up to 9 unary ones, an escape of [bps] raw bits after 9. */
    private fun scalar(k: Int, bps: Int): Int {
        val window = peek(9)
        val pre = (window.inv() and 0x1FF).countLeadingZeroBits() - 23
        if (pre > 8) {
            pos += 9
            val v = peek(bps)
            pos += bps
            return v
        }
        pos += pre + 1
        var x = pre
        if (k != 1) {
            val extra = peek(k)
            x = (pre shl k) - pre
            if (extra > 1) {
                x += extra - 1
                pos += k
            } else {
                pos += k - 1
            }
        }
        return x
    }

    /** The adaptive FIR predictor (undoes the encoder's prediction); sample values are kept to [chanBits] bits. */
    private fun predict(pc: IntArray, out: IntArray, num: Int, coefs: IntArray, numActive: Int, chanBits: Int, denShift: Int) {
        if (num <= 0) return
        val up = 32 - chanBits
        out[0] = pc[0]
        if (num == 1) return
        if (numActive == 0) {
            if (pc !== out) pc.copyInto(out, 1, 1, num)
            return
        }
        if (numActive == 31) {
            for (j in 1 until num) out[j] = ((pc[j] + out[j - 1]) shl up) shr up
            return
        }
        val warm = if (numActive < num - 1) numActive else num - 1
        for (j in 1..warm) out[j] = ((pc[j] + out[j - 1]) shl up) shr up
        val round = if (denShift > 0) 1 shl (denShift - 1) else 0
        for (i in numActive + 1 until num) {
            val base = i - numActive
            val d = out[base - 1]
            var sum = 0
            for (j in 0 until numActive) sum += (out[base + numActive - 1 - j] - d) * coefs[j]
            val err = pc[i]
            out[i] = (((sum + round) shr denShift) + d + err shl up) shr up
            // Adapt the coefficients toward the sign of the error.
            val errSign = if (err > 0) 1 else if (err < 0) -1 else 0
            if (errSign != 0) {
                var e = err
                var m = 0
                while (m < numActive && e * errSign > 0) {
                    var v = d - out[base + m]
                    val s = (if (v > 0) 1 else if (v < 0) -1 else 0) * errSign
                    val j = numActive - 1 - m
                    coefs[j] = (coefs[j] - s).toShort().toInt()
                    v *= s
                    e -= (v shr denShift) * (m + 1)
                    m++
                }
            }
        }
    }

    /** Un-mixes stereo and writes the element's channels, interleaved, at the file's bit depth. */
    private fun write(out: IntArray, channelIndex: Int, num: Int, stereo: Boolean, mixBits: Int, mixRes: Int, shift: Int) {
        val channels = config.channels
        val depth = config.bitDepth
        val u = mixU
        val v = mixV
        if (!stereo) {
            var o = channelIndex
            for (i in 0 until num) {
                out[o] = finish(u[i], depth, shift, if (shift != 0) shiftBuffer[i] else 0)
                o += channels
            }
            return
        }
        var o = channelIndex
        for (i in 0 until num) {
            val a: Int
            val b: Int
            if (mixRes != 0) {
                a = u[i] + v[i] - ((mixRes * v[i]) shr mixBits)
                b = a - v[i]
            } else {
                a = u[i]
                b = v[i]
            }
            if (shift != 0 && depth > 20) {
                out[o] = finish(a, depth, shift, shiftBuffer[2 * i])
                out[o + 1] = finish(b, depth, shift, shiftBuffer[2 * i + 1])
            } else {
                out[o] = finish(a, depth, 0, 0)
                out[o + 1] = finish(b, depth, 0, 0)
            }
            o += channels
        }
    }

    private fun finish(sample: Int, depth: Int, shift: Int, low: Int): Int = when (depth) {
        16 -> sample.toShort().toInt()
        20 -> (sample shl 12) shr 12
        24 -> (((sample shl shift) or low) shl 8) shr 8
        else -> (sample shl shift) or low
    }

    private companion object {
        const val PAD = 16
        const val TAG_SCE = 0
        const val TAG_CPE = 1
        const val TAG_DSE = 4
        const val TAG_LFE = 3
        const val TAG_FIL = 6
        const val TAG_END = 7
    }
}

/** ALAC's channel order for 1..8 channels and where each goes in WAVE (Android/ExoPlayer) order. */
object AudioDekChannels {
    /**
     * For each ALAC channel index, the output position in WAVE order. ALAC:
     * 1 C; 2 L R; 3 C L R; 4 C L R Cs; 5 C L R Ls Rs; 5.1 C L R Ls Rs LFE;
     * 6.1 C L R Ls Rs Cs LFE; 7.1 C Lc Rc L R Ls Rs LFE.
     */
    private val offsets = arrayOf(
        intArrayOf(0),
        intArrayOf(0, 1),
        intArrayOf(2, 0, 1),
        intArrayOf(2, 0, 1, 3),
        intArrayOf(2, 0, 1, 3, 4),
        intArrayOf(2, 0, 1, 4, 5, 3),
        intArrayOf(2, 0, 1, 4, 5, 6, 3),
        intArrayOf(2, 6, 7, 0, 1, 4, 5, 3),
    )

    /** Android `AudioFormat.CHANNEL_OUT_*` speaker bits (constants copied so this module stays free of Android). */
    private const val FL = 0x4
    private const val FR = 0x8
    private const val FC = 0x10
    private const val LFE = 0x20
    private const val BL = 0x40
    private const val BR = 0x80
    private const val BC = 0x400
    private const val SL = 0x800
    private const val SR = 0x1000

    /**
     * Where an ALAC layout goes on Android: its channels' names in ALAC order, the Android speaker each is played
     * on ([speakers], by ALAC channel index, in the output order of [AudioDekChannels.toWave]), and the
     * `CHANNEL_OUT_*` mask that has exactly those speakers ([androidMask]; the audio track takes the data in
     * ascending bit order, which is WAVE order). [explicitMask] is false for mono and stereo, which ExoPlayer's own
     * count-to-layout map already gets right: no mask is set there.
     */
    class Layout(
        val channels: Int,
        /** ALAC's channel order, e.g. "C L R Cs". */
        val alacOrder: String,
        /** The Android speaker of each ALAC channel, e.g. FC FL FR BC. */
        val speakers: List<String>,
        val androidMask: Int,
        val maskName: String,
        val explicitMask: Boolean,
    )

    private val layouts = listOf(
        Layout(1, "C", listOf("FL"), FL, "MONO", false),
        Layout(2, "L R", listOf("FL", "FR"), FL or FR, "STEREO", false),
        Layout(3, "C L R", listOf("FC", "FL", "FR"), FL or FR or FC, "FL|FR|FC", true),
        Layout(4, "C L R Cs", listOf("FC", "FL", "FR", "BC"), FL or FR or FC or BC, "SURROUND", true),
        Layout(5, "C L R Ls Rs", listOf("FC", "FL", "FR", "BL", "BR"), FL or FR or FC or BL or BR, "FL|FR|FC|BL|BR", true),
        Layout(6, "C L R Ls Rs LFE", listOf("FC", "FL", "FR", "BL", "BR", "LFE"), FL or FR or FC or LFE or BL or BR, "5POINT1", true),
        Layout(7, "C L R Ls Rs Cs LFE", listOf("FC", "FL", "FR", "BL", "BR", "BC", "LFE"), FL or FR or FC or LFE or BL or BR or BC, "5POINT1|BC", true),
        // Lc/Rc (wide fronts) have no speaker in Android's 8-channel layout: they go to the side speakers.
        Layout(8, "C Lc Rc L R Ls Rs LFE", listOf("FC", "SL", "SR", "FL", "FR", "BL", "BR", "LFE"), FL or FR or FC or LFE or BL or BR or SL or SR, "7POINT1_SURROUND", true),
    )

    fun layout(channels: Int): Layout = layouts[channels - 1]

    /** `result[alacIndex]` is the WAVE-order position of that channel. */
    fun waveOrder(channels: Int): IntArray = offsets[channels - 1]

    /** Reorders `frames` interleaved frames: ALAC order in [src], WAVE order into [dst]. */
    fun toWave(src: IntArray, dst: IntArray, frames: Int, channels: Int) {
        val map = waveOrder(channels)
        var s = 0
        var d = 0
        for (f in 0 until frames) {
            for (c in 0 until channels) dst[d + map[c]] = src[s + c]
            s += channels
            d += channels
        }
    }
}
