package gg.dekiru.musicplayer.downloads

import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A real FLAC written the way the server converts: FFmpeg to a pipe, which
 * leaves total samples at 0. Skipped where FFmpeg isn't installed.
 */
class FlacRepairFfmpegTest {

    private fun tool(name: String): String? =
        (System.getenv("PATH").orEmpty().split(File.pathSeparator) + listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin"))
            .map { File(it, name) }.firstOrNull { it.canExecute() }?.path

    private fun run(vararg command: String, output: File? = null): String {
        val process = ProcessBuilder(*command).apply {
            if (output != null) redirectOutput(output) else redirectErrorStream(true)
        }.start()
        val text = if (output == null) process.inputStream.bufferedReader().readText() else ""
        process.waitFor(60, TimeUnit.SECONDS)
        assertEquals(0, process.exitValue(), "${command.joinToString(" ")}: $text")
        return text.trim()
    }

    @Test
    fun aFlacFromAPipeGetsTheSameTotalAsOneWrittenToAFile() {
        val ffmpeg = tool("ffmpeg")
        assumeTrue("ffmpeg isn't installed", ffmpeg != null)
        val dir = File(tempDir())
        val piped = File(dir, "piped.flac")
        val direct = File(dir, "direct.flac")
        val source = arrayOf("-v", "error", "-f", "lavfi", "-i", "sine=frequency=440:duration=3.3", "-ac", "2", "-ar", "44100")
        run(ffmpeg!!, *source, "-f", "flac", "-", output = piped)
        run(ffmpeg, "-y", *source, direct.path)

        fun total(file: File) = FlacRepair.totalSamples(file.readBytes().copyOfRange(8, 42))
        assertEquals(0L, total(piped))
        assertEquals(145_530L, total(direct))
        val length = piped.length()

        assertEquals(FlacRepair.Result.REPAIRED, FlacRepair.repairFile(piped.path, log = {}))
        assertEquals(145_530L, total(piped))
        assertEquals(length, piped.length())

        tool("metaflac")?.let { assertEquals("145530", run(it, "--show-total-samples", piped.path)) }
        tool("flac")?.let { run(it, "-t", "-s", piped.path) }
        tool("ffprobe")?.let {
            val samples = run(it, "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=duration_ts", "-of", "csv=p=0", piped.path)
            assertEquals("145530", samples)
        }
        dir.deleteRecursively()
    }
}
