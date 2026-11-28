package gg.dekiru.audiodek

import java.io.File
import kotlin.test.Test

/**
 * Not a test: with `ALAC_FIXTURE_DIR` set it writes every [AlacSignals.all]
 * signal as `<name>.wav` there, for `tools/make-fixtures.sh` to
 * feed to the two ALAC encoders. Without it, it does nothing.
 */
class AlacFixtureWavWriter {
    @Test
    fun writeWavs() {
        val dir = System.getenv("ALAC_FIXTURE_DIR")?.takeIf { it.isNotEmpty() }?.let(::File) ?: return
        dir.mkdirs()
        for (signal in AlacSignals.all) File(dir, signal.name + ".wav").writeBytes(wav(signal))
        // name, SHA-256 of the source PCM (little-endian, whole bytes per sample), bits, channels, rate
        val manifest = AlacSignals.all.joinToString("\n", prefix = "# audioDEK fixtures: name sha256-of-source-pcm bits channels rate\n", postfix = "\n") {
            val pcm = AlacSignals.littleEndianPcm(it.samples(), (it.bits + 7) / 8 * 8)
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(pcm).joinToString("") { b -> "%02x".format(b) }
            "${it.name} $hash ${it.bits} ${it.channels} ${it.rate}"
        }
        File(dir, "manifest.txt").writeText(manifest)
    }

    /** 20-bit audio is written in 24-bit samples shifted left 4 (the WAVE convention), with 20 valid bits. */
    private fun wav(s: AlacSignal): ByteArray {
        val container = if (s.bits == 20) 24 else s.bits
        val samples = s.samples()
        val pcm = AlacSignals.littleEndianPcm(if (s.bits == 20) IntArray(samples.size) { samples[it] shl 4 } else samples, container)
        val extensible = s.channels > 2 || container > 16
        val fmtSize = if (extensible) 40 else 16
        val out = java.io.ByteArrayOutputStream()
        fun le(v: Int, n: Int) { for (i in 0 until n) out.write((v ushr (8 * i)) and 0xFF) }
        out.write("RIFF".toByteArray()); le(4 + 8 + fmtSize + 8 + pcm.size, 4); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le(fmtSize, 4)
        le(if (extensible) 0xFFFE else 1, 2); le(s.channels, 2); le(s.rate, 4)
        le(s.rate * s.channels * container / 8, 4); le(s.channels * container / 8, 2); le(container, 2)
        if (extensible) {
            le(22, 2); le(s.bits, 2); le(if (s.channelMask != 0) s.channelMask else if (s.channels == 1) 4 else 3, 4)
            out.write(byteArrayOf(1, 0, 0, 0, 0, 0, 0x10, 0, 0x80.toByte(), 0, 0, 0xAA.toByte(), 0, 0x38, 0x9B.toByte(), 0x71))
        }
        out.write("data".toByteArray()); le(pcm.size, 4); out.write(pcm)
        return out.toByteArray()
    }
}
