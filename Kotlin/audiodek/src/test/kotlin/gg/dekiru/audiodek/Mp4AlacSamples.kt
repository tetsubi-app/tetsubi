package gg.dekiru.audiodek

/**
 * Test-only MP4 reader: pulls the ALAC magic cookie and the samples (frames)
 * out of an `.m4a` through `stsd`, `stsz`, `stsc` and `stco`/`co64`.
 */
class Mp4AlacSamples(private val file: ByteArray) {
    /** The 24-byte `ALACSpecificConfig` from the `alac` box inside the sample entry. */
    var cookie: ByteArray = ByteArray(0)
        private set

    /** (offset, size) of each sample in [file]. */
    val samples = ArrayList<Pair<Int, Int>>()

    private class Box(val type: String, val start: Int, val end: Int)

    init {
        val moov = children(0, file.size).firstOrNull { it.type == "moov" } ?: error("no moov")
        val trak = children(moov.start, moov.end).filter { it.type == "trak" }.first { t ->
            val stbl = path(t, "mdia", "minf", "stbl")
            stbl != null && children(stbl.start, stbl.end).any { it.type == "stsd" } && entryType(stbl) == "alac"
        }
        val stbl = path(trak, "mdia", "minf", "stbl")!!
        val kids = children(stbl.start, stbl.end)
        readCookie(kids.first { it.type == "stsd" })
        val stsz = kids.first { it.type == "stsz" }
        val fixed = u32(stsz.start + 4)
        val count = u32(stsz.start + 8)
        val sizes = IntArray(count) { if (fixed != 0) fixed else u32(stsz.start + 12 + 4 * it) }
        val stsc = kids.first { it.type == "stsc" }
        val runs = (0 until u32(stsc.start + 4)).map { i ->
            val at = stsc.start + 8 + 12 * i
            Pair(u32(at), u32(at + 4)) // first chunk (1-based), samples per chunk
        }
        val offsets: List<Long> = kids.firstOrNull { it.type == "stco" }?.let { b ->
            (0 until u32(b.start + 4)).map { u32(b.start + 8 + 4 * it).toLong() and 0xFFFFFFFFL }
        } ?: kids.first { it.type == "co64" }.let { b ->
            (0 until u32(b.start + 4)).map { u64(b.start + 8 + 8 * it) }
        }
        var sample = 0
        for (chunk in offsets.indices) {
            val run = runs.last { it.first <= chunk + 1 }
            var at = offsets[chunk]
            repeat(run.second) {
                if (sample < count) {
                    samples += Pair(at.toInt(), sizes[sample])
                    at += sizes[sample]
                    sample++
                }
            }
        }
        check(sample == count) { "stsc/stco cover $sample of $count samples" }
    }

    fun frame(i: Int): ByteArray = file.copyOfRange(samples[i].first, samples[i].first + samples[i].second)

    private fun u32(at: Int) = ((file[at].toInt() and 0xFF) shl 24) or ((file[at + 1].toInt() and 0xFF) shl 16) or
        ((file[at + 2].toInt() and 0xFF) shl 8) or (file[at + 3].toInt() and 0xFF)

    private fun u64(at: Int) = (u32(at).toLong() shl 32) or (u32(at + 4).toLong() and 0xFFFFFFFFL)

    private fun children(from: Int, to: Int): List<Box> {
        val out = ArrayList<Box>()
        var at = from
        while (at + 8 <= to) {
            var size = u32(at).toLong() and 0xFFFFFFFFL
            val type = String(CharArray(4) { (file[at + 4 + it].toInt() and 0xFF).toChar() })
            var header = 8
            if (size == 1L) { size = u64(at + 8); header = 16 } else if (size == 0L) size = (to - at).toLong()
            if (size < header) break
            out += Box(type, at + header, minOf(to.toLong(), at + size).toInt())
            at += size.toInt()
        }
        return out
    }

    private fun path(from: Box, vararg names: String): Box? {
        var box = from
        for (n in names) box = children(box.start, box.end).firstOrNull { it.type == n } ?: return null
        return box
    }

    private fun entryType(stbl: Box): String {
        val stsd = children(stbl.start, stbl.end).first { it.type == "stsd" }
        return String(CharArray(4) { (file[stsd.start + 8 + 4 + it].toInt() and 0xFF).toChar() })
    }

    private fun readCookie(stsd: Box) {
        val entryStart = stsd.start + 8 // after version/flags and entry count
        val entryEnd = entryStart + u32(entryStart)
        var at = entryStart + 8 + 6 + 2
        val version = ((file[at].toInt() and 0xFF) shl 8) or (file[at + 1].toInt() and 0xFF)
        at += 8 + 2 + 2 + 2 + 2 + 4 // version..sample rate
        if (version == 1) at += 16 else if (version == 2) at += 36
        val alac = children(at, entryEnd).first { it.type == "alac" }
        cookie = file.copyOfRange(alac.start + 4, alac.end)
    }
}
