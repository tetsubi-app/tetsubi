package gg.dekiru.musicplayer.playback

/**
 * Sample rate, bit depth and channels of an ALAC track in an MP4/M4A file, read from the file's own
 * `alac` configuration (the 24-byte cookie inside `moov/trak/mdia/minf/stbl/stsd`). Pure: [read] gets a
 * function that returns `length` bytes at `offset` (null past the end), so Android reads a file through
 * it and the tests read a byte array. Used for a downloaded original ALAC `.m4a`, where the platform's
 * extractor may not give a bit depth. Null for anything else or a damaged file (fail soft).
 */
data class AlacFileInfo(val sampleRate: Int, val bitDepth: Int, val channels: Int) {
    companion object {
        /** Containers whose children are walked on the way to `stsd`. */
        private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl")

        fun read(fileSize: Long, reader: (offset: Long, length: Int) -> ByteArray?): AlacFileInfo? =
            runCatching { find(0L, fileSize, 0, fileSize, reader) }.getOrNull()

        private fun find(start: Long, end: Long, depth: Int, fileSize: Long, reader: (Long, Int) -> ByteArray?): AlacFileInfo? {
            if (depth > 8) return null
            var pos = start
            while (pos + 8 <= end) {
                val head = reader(pos, minOf(16L, end - pos).toInt()) ?: return null
                var size = u32(head, 0)
                val type = String(head, 4, 4, Charsets.ISO_8859_1)
                var header = 8L
                if (size == 1L && head.size >= 16) { size = u64(head, 8); header = 16L } else if (size == 0L) size = end - pos
                if (size < header || pos + size > end) return null
                val body = pos + header
                when {
                    type in CONTAINERS -> find(body, pos + size, depth + 1, fileSize, reader)?.let { return it }
                    // stsd: version/flags (4), entry count (4), then sample entries.
                    type == "stsd" -> entry(body + 8, pos + size, reader)?.let { return it }
                }
                pos += size
            }
            return null
        }

        /** The first `alac` sample entry: 8-byte box header, 28 bytes of audio sample entry, then its child boxes. */
        private fun entry(start: Long, end: Long, reader: (Long, Int) -> ByteArray?): AlacFileInfo? {
            val head = reader(start, 8) ?: return null
            if (String(head, 4, 4, Charsets.ISO_8859_1) != "alac") return null
            val size = u32(head, 0)
            if (size < 36 || start + size > end) return null
            var pos = start + 8 + 28
            val entryEnd = start + size
            while (pos + 8 <= entryEnd) {
                val box = reader(pos, 8) ?: return null
                val boxSize = u32(box, 0)
                if (boxSize < 8 || pos + boxSize > entryEnd) return null
                if (String(box, 4, 4, Charsets.ISO_8859_1) == "alac" && boxSize >= 8 + 4 + 24) {
                    val cookie = reader(pos + 12, 24) ?: return null
                    val depth = cookie[5].toInt() and 0xFF
                    val channels = cookie[9].toInt() and 0xFF
                    val rate = u32(cookie, 20).toInt()
                    return if (depth in 16..32 && channels in 1..8 && rate > 0) AlacFileInfo(rate, depth, channels) else null
                }
                pos += boxSize
            }
            return null
        }

        private fun u32(b: ByteArray, o: Int): Long =
            ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

        private fun u64(b: ByteArray, o: Int): Long = (u32(b, o) shl 32) or u32(b, o + 4)
    }
}
