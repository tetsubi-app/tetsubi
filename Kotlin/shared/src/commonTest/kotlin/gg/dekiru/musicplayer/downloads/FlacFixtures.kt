package gg.dekiru.musicplayer.downloads

/**
 * Small FLAC files built by hand for tests: STREAMINFO (44.1 kHz, mono,
 * 16 bit) and frames with CONSTANT or VERBATIM subframes, so no encoder is
 * needed. Frame headers carry a real CRC-8 and frames a real CRC-16.
 */
object FlacFixtures {

    /** "fLaC", STREAMINFO with `total`, any `extraBlocks` (type to body), then `frames`. */
    fun file(frames: List<ByteArray>, total: Long = 0, blockSize: Int = 4096, extraBlocks: List<Pair<Int, ByteArray>> = emptyList()): ByteArray {
        val out = mutableListOf<Byte>()
        out += "fLaC".encodeToByteArray().toList()
        out += blockHeader(0, 34, last = extraBlocks.isEmpty()).toList()
        out += streamInfo(total, blockSize).toList()
        extraBlocks.forEachIndexed { i, (type, body) ->
            out += blockHeader(type, body.size, last = i == extraBlocks.lastIndex).toList()
            out += body.toList()
        }
        frames.forEach { out += it.toList() }
        return out.toByteArray()
    }

    private fun blockHeader(type: Int, length: Int, last: Boolean) = byteArrayOf(
        ((if (last) 0x80 else 0) or type).toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte(),
    )

    /** STREAMINFO body: 44100 Hz, 1 channel, 16 bits, `total` samples, an MD5 of 1…16. */
    fun streamInfo(total: Long, blockSize: Int): ByteArray {
        val b = ByteArray(34)
        b[0] = (blockSize ushr 8).toByte(); b[1] = blockSize.toByte()
        b[2] = (blockSize ushr 8).toByte(); b[3] = blockSize.toByte()
        val packed = (44100L shl 44) or (0L shl 41) or (15L shl 36) or total
        for (i in 0 until 8) b[10 + i] = (packed ushr (56 - 8 * i)).toByte()
        for (i in 0 until 16) b[18 + i] = (i + 1).toByte()
        return b
    }

    /** A frame header (with its CRC-8). `number`: the frame number (fixed) or first sample (variable). */
    fun header(variable: Boolean, number: Long, blockSize: Int): ByteArray {
        val out = mutableListOf<Byte>(0xFF.toByte(), (if (variable) 0xF9 else 0xF8).toByte())
        val (code, tail) = sizeCode(blockSize)
        out += ((code shl 4) or 9).toByte() // 44.1 kHz
        out += (4 shl 1).toByte() // mono, 16 bit
        out += utf8(number).toList()
        out += tail.toList()
        val bytes = out.toByteArray()
        return bytes + FlacRepair.crc8(bytes, 0, bytes.size).toByte()
    }

    /** A whole frame: header, one subframe (CONSTANT `value`, or VERBATIM `verbatim`), CRC-16. */
    fun frame(variable: Boolean, number: Long, blockSize: Int, value: Int = 0, verbatim: ByteArray? = null): ByteArray {
        val sub = if (verbatim != null) {
            require(verbatim.size == blockSize * 2)
            byteArrayOf(0x02) + verbatim
        } else {
            byteArrayOf(0x00, (value ushr 8).toByte(), value.toByte())
        }
        val body = header(variable, number, blockSize) + sub
        val crc = FlacRepair.crc16(body, 0, body.size)
        return body + byteArrayOf((crc ushr 8).toByte(), crc.toByte())
    }

    /** Frames for these block sizes, numbered as a fixed or variable blocksize stream. */
    fun frames(variable: Boolean, sizes: List<Int>, value: Int = 0): List<ByteArray> {
        var sample = 0L
        return sizes.mapIndexed { i, size ->
            frame(variable, if (variable) sample else i.toLong(), size, value).also { sample += size }
        }
    }

    private fun sizeCode(size: Int): Pair<Int, ByteArray> = when {
        size == 192 -> 1 to ByteArray(0)
        size in listOf(576, 1152, 2304, 4608) -> (2 + listOf(576, 1152, 2304, 4608).indexOf(size)) to ByteArray(0)
        size in (0..7).map { 256 shl it } -> (8 + (0..7).first { 256 shl it == size }) to ByteArray(0)
        size <= 256 -> 6 to byteArrayOf((size - 1).toByte())
        else -> 7 to byteArrayOf(((size - 1) ushr 8).toByte(), (size - 1).toByte())
    }

    private fun utf8(n: Long): ByteArray {
        if (n < 0x80) return byteArrayOf(n.toByte())
        val bits = 64 - n.countLeadingZeroBits()
        val count = when {
            bits <= 11 -> 2
            bits <= 16 -> 3
            bits <= 21 -> 4
            bits <= 26 -> 5
            bits <= 31 -> 6
            else -> 7
        }
        val out = ByteArray(count)
        var v = n
        for (i in count - 1 downTo 1) {
            out[i] = (0x80 or (v and 0x3F).toInt()).toByte()
            v = v ushr 6
        }
        out[0] = if (count == 7) 0xFE.toByte() else (((0xFF00 ushr count) and 0xFF) or v.toInt()).toByte()
        return out
    }
}
