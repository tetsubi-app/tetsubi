package gg.dekiru.musicplayer.downloads

import gg.dekiru.musicplayer.logLine
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readTo

/**
 * Fills in the total-samples count of a FLAC file whose STREAMINFO says 0.
 *
 * A FLAC the server converts on the fly (`Audio/{id}/universal`) is written by
 * FFmpeg to a pipe, so FFmpeg can't go back and fill in STREAMINFO: total
 * samples stays 0 and there is no SEEKTABLE. Media3's `FlacExtractor` then
 * can't seek (its binary-search seeker needs the total), and a seek restarts
 * the track at 0:00. This counts the samples by walking the audio frames and
 * writes the 36-bit count into STREAMINFO; nothing else changes (the MD5 stays
 * as it is, the file keeps its length).
 *
 * A file that isn't FLAC, already has a total, or doesn't parse cleanly is
 * left alone. Every frame is checked: its header's CRC-8, the frame or sample
 * number following on from the one before, and the CRC-16 over the whole frame.
 *
 * The patched file is written next to the original as `<file>.tmp` and moved
 * over it, so an app killed half way leaves the original as it was (and a
 * `.tmp` the download code ignores).
 */
internal object FlacRepair {

    enum class Result {
        /** Total samples written. */
        REPAIRED,

        /** STREAMINFO already had a total: untouched. */
        ALREADY_SET,

        /** Not a FLAC file: untouched. */
        NOT_FLAC,

        /** FLAC, but the frames didn't add up (truncated, damaged): untouched. */
        UNPARSEABLE,

        /** Reading or writing failed: untouched. */
        FAILED,
    }

    /** Offset of the byte that holds the top 4 bits of total samples: "fLaC" (4) + block header (4) + 13. */
    internal const val TOTAL_OFFSET = 21L

    private const val STREAMINFO_LENGTH = 34
    private const val MAX_TOTAL = (1L shl 36) - 1

    private val defaultLog: (String) -> Unit = { logLine("Tetsubi", "flac: $it") }

    /** Repairs `path` if it needs it (see the class). Never throws. */
    fun repairFile(path: String, log: (String) -> Unit = defaultLog): Result {
        val file = Path(path)
        val total = try {
            val outcome = SystemFileSystem.source(file).buffered().use(::countIfMissing)
            when (outcome) {
                is Count.Done -> outcome
                is Count.Skip -> {
                    if (outcome.result == Result.UNPARSEABLE) log("left alone, frames don't add up: $path")
                    return outcome.result
                }
            }
        } catch (e: Exception) {
            log("couldn't read $path: ${e.message}")
            return Result.FAILED
        }
        val tmp = Path("$path.tmp")
        return try {
            val length = SystemFileSystem.metadataOrNull(file)?.size ?: -1
            SystemFileSystem.source(file).buffered().use { source ->
                SystemFileSystem.sink(tmp).buffered().use { sink -> copyPatched(source, sink, total.samples, total.flacStart) }
            }
            if (SystemFileSystem.metadataOrNull(tmp)?.size != length) throw IllegalStateException("size changed")
            SystemFileSystem.atomicMove(tmp, file)
            log("total samples set to ${total.samples}: $path")
            Result.REPAIRED
        } catch (e: Exception) {
            runCatching { SystemFileSystem.delete(tmp, mustExist = false) }
            log("couldn't repair $path: ${e.message}")
            Result.FAILED
        }
    }

    internal sealed interface Count {
        /** `flacStart`: where "fLaC" is (after an ID3v2 tag, if there's one). */
        data class Done(val samples: Long, val flacStart: Long = 0) : Count
        data class Skip(val result: Result) : Count
    }

    /** Reads the header and, when total samples is 0, counts the samples in the frames. */
    internal fun countIfMissing(source: Source): Count {
        // Some FLAC files start with an ID3v2 tag (Media3 reads past it too).
        var flacStart = 0L
        if (source.request(10) && source.peek().readByteArray(3).contentEquals(byteArrayOf(0x49, 0x44, 0x33))) {
            val id3 = source.readByteArray(10)
            val size = (3..6).fold(0L) { acc, k -> (acc shl 7) or (id3[k + 3].toLong() and 0x7F) }
            flacStart = 10 + size + if (id3[5].toInt() and 0x10 != 0) 10 else 0
            if (!source.request(flacStart - 10)) return Count.Skip(Result.NOT_FLAC)
            source.skip(flacStart - 10)
        }
        if (!source.request(8)) return Count.Skip(Result.NOT_FLAC)
        val magic = ByteArray(4).also { source.readTo(it) }
        if (!magic.contentEquals(byteArrayOf(0x66, 0x4C, 0x61, 0x43))) return Count.Skip(Result.NOT_FLAC)
        // STREAMINFO must come first.
        val first = source.readInt()
        var last = first ushr 31 == 1
        if ((first ushr 24) and 0x7F != 0 || first and 0xFFFFFF != STREAMINFO_LENGTH) return Count.Skip(Result.UNPARSEABLE)
        if (!source.request(STREAMINFO_LENGTH.toLong())) return Count.Skip(Result.UNPARSEABLE)
        val info = ByteArray(STREAMINFO_LENGTH).also { source.readTo(it) }
        if (totalSamples(info) != 0L) return Count.Skip(Result.ALREADY_SET)
        // The other metadata blocks.
        while (!last) {
            if (!source.request(4)) return Count.Skip(Result.UNPARSEABLE)
            val header = source.readInt()
            last = header ushr 31 == 1
            if ((header ushr 24) and 0x7F == 127) return Count.Skip(Result.UNPARSEABLE)
            val length = (header and 0xFFFFFF).toLong()
            if (!source.request(length)) return Count.Skip(Result.UNPARSEABLE)
            source.skip(length)
        }
        val samples = countSamples(source) ?: return Count.Skip(Result.UNPARSEABLE)
        if (samples <= 0 || samples > MAX_TOTAL) return Count.Skip(Result.UNPARSEABLE)
        return Count.Done(samples, flacStart)
    }

    /** Total samples from a STREAMINFO body (34 bytes). */
    internal fun totalSamples(info: ByteArray): Long =
        ((info[13].toLong() and 0x0F) shl 32) or
            ((info[14].toLong() and 0xFF) shl 24) or ((info[15].toLong() and 0xFF) shl 16) or
            ((info[16].toLong() and 0xFF) shl 8) or (info[17].toLong() and 0xFF)

    /** Copies `source` to `sink` with bytes 21…25 after "fLaC" holding `total` (the top nibble of the first is kept). */
    private fun copyPatched(source: Source, sink: Sink, total: Long, flacStart: Long) {
        source.readTo(sink, flacStart)
        val head = ByteArray(TOTAL_OFFSET.toInt() + 5)
        source.readTo(head)
        patch(head, total)
        sink.write(head)
        source.transferTo(sink)
    }

    /** Writes `total` into a file's first bytes (at least 26: "fLaC", block header, STREAMINFO). */
    internal fun patch(head: ByteArray, total: Long) {
        val i = TOTAL_OFFSET.toInt()
        head[i] = ((head[i].toInt() and 0xF0) or ((total ushr 32).toInt() and 0x0F)).toByte()
        head[i + 1] = (total ushr 24).toByte()
        head[i + 2] = (total ushr 16).toByte()
        head[i + 3] = (total ushr 8).toByte()
        head[i + 4] = total.toByte()
    }

    // Frames

    /** The most a frame header can take: sync and 2 bytes, 7 for the number, 2 + 2 optional, CRC-8. */
    private const val MAX_HEADER = 16

    private class Header(val variable: Boolean, val number: Long, val blockSize: Int)

    /**
     * Counts the samples in the audio frames that make up the rest of `source`
     * (which must start at the first frame). Null when they don't parse cleanly.
     */
    internal fun countSamples(source: Source): Long? {
        val buf = ByteArray(64 * 1024 + MAX_HEADER)
        var pos = 0
        var lim = 0
        var eof = false
        fun refill() {
            buf.copyInto(buf, 0, pos, lim)
            lim -= pos
            pos = 0
            while (lim < buf.size && !eof) {
                val n = source.readAtMostTo(buf, lim, buf.size)
                if (n < 0) eof = true else lim += n
            }
        }
        refill()
        val variable = parseHeader(buf, pos, lim)?.variable ?: return null
        var frames = 0L
        var samples = 0L
        var crc = 0
        while (true) {
            if (lim - pos < MAX_HEADER && !eof) refill()
            if (pos >= lim) break
            val b = buf[pos].toInt() and 0xFF
            // A frame can only start where the one before ends: its CRC-16 has come round to 0.
            if (b == 0xFF && crc == 0) {
                val header = parseHeader(buf, pos, lim)
                if (header != null) {
                    // A real header where a frame ends, but out of step: a frame is missing or damaged.
                    if (header.variable != variable || header.number != (if (variable) samples else frames)) return null
                    frames++
                    samples += header.blockSize
                }
            }
            crc = ((crc shl 8) xor CRC16[((crc ushr 8) xor b) and 0xFF]) and 0xFFFF
            pos++
        }
        return if (frames > 0 && crc == 0) samples else null
    }

    /** A valid frame header at `at` (sync, no reserved values, CRC-8 right), or null. */
    private fun parseHeader(buf: ByteArray, at: Int, lim: Int): Header? {
        fun byte(i: Int): Int = if (at + i < lim) buf[at + i].toInt() and 0xFF else -1
        if (byte(0) != 0xFF) return null
        val b1 = byte(1)
        if (b1 != 0xF8 && b1 != 0xF9) return null
        val variable = b1 == 0xF9
        val b2 = byte(2)
        val b3 = byte(3)
        if (b2 < 0 || b3 < 0) return null
        val sizeCode = b2 ushr 4
        val rateCode = b2 and 0x0F
        if (sizeCode == 0 || rateCode == 0x0F) return null
        if (b3 ushr 4 > 10 || (b3 ushr 1) and 7 == 3 || b3 and 1 != 0) return null
        // The frame (fixed) or sample (variable) number, UTF-8 style.
        var i = 4
        val lead = byte(i++)
        if (lead < 0) return null
        val extra: Int
        var number: Long
        when {
            lead and 0x80 == 0 -> { extra = 0; number = lead.toLong() }
            lead and 0xE0 == 0xC0 -> { extra = 1; number = (lead and 0x1F).toLong() }
            lead and 0xF0 == 0xE0 -> { extra = 2; number = (lead and 0x0F).toLong() }
            lead and 0xF8 == 0xF0 -> { extra = 3; number = (lead and 0x07).toLong() }
            lead and 0xFC == 0xF8 -> { extra = 4; number = (lead and 0x03).toLong() }
            lead and 0xFE == 0xFC -> { extra = 5; number = (lead and 0x01).toLong() }
            lead == 0xFE && variable -> { extra = 6; number = 0 }
            else -> return null
        }
        repeat(extra) {
            val c = byte(i++)
            if (c < 0 || c and 0xC0 != 0x80) return null
            number = (number shl 6) or (c and 0x3F).toLong()
        }
        val blockSize = when (sizeCode) {
            1 -> 192
            in 2..5 -> 576 shl (sizeCode - 2)
            6 -> byte(i++).takeIf { it >= 0 }?.plus(1) ?: return null
            7 -> {
                val hi = byte(i++)
                val lo = byte(i++)
                if (hi < 0 || lo < 0) return null
                ((hi shl 8) or lo) + 1
            }
            else -> 256 shl (sizeCode - 8)
        }
        i += when (rateCode) {
            12 -> 1
            13, 14 -> 2
            else -> 0
        }
        val crc = byte(i)
        if (crc < 0 || crc8(buf, at, at + i) != crc) return null
        return Header(variable, number, blockSize)
    }

    /** CRC-8, polynomial 0x07, as FLAC frame headers. */
    internal fun crc8(bytes: ByteArray, from: Int, to: Int): Int {
        var crc = 0
        for (k in from until to) crc = CRC8[(crc xor (bytes[k].toInt() and 0xFF)) and 0xFF]
        return crc
    }

    /** CRC-16, polynomial 0x8005, as FLAC frame footers. */
    internal fun crc16(bytes: ByteArray, from: Int, to: Int): Int {
        var crc = 0
        for (k in from until to) crc = ((crc shl 8) xor CRC16[((crc ushr 8) xor (bytes[k].toInt() and 0xFF)) and 0xFF]) and 0xFFFF
        return crc
    }

    private val CRC8 = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF }
        c
    }

    private val CRC16 = IntArray(256) { n ->
        var c = n shl 8
        repeat(8) { c = if (c and 0x8000 != 0) ((c shl 1) xor 0x8005) and 0xFFFF else (c shl 1) and 0xFFFF }
        c
    }
}
