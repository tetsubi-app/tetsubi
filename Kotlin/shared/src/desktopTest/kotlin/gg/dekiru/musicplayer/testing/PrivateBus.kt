package gg.dekiru.musicplayer.testing

import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * A D-Bus session bus of the test's own, so tests never touch the desktop's.
 * Nothing is started on demand (no service activation): a test starts what
 * it needs itself. [start] skips the test where dbus-daemon isn't installed
 * (a Mac, CI without it).
 */
class PrivateBus private constructor(private val process: Process, val address: String, private val dir: File) : AutoCloseable {

    override fun close() {
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        dir.deleteRecursively()
    }

    companion object {
        fun start(): PrivateBus {
            val daemon = listOf("/usr/bin/dbus-daemon", "/bin/dbus-daemon").firstOrNull { File(it).canExecute() }
            assumeTrue("dbus-daemon isn't installed", daemon != null)
            val dir = Files.createTempDirectory("tetsubi-bus").toFile()
            val config = File(dir, "bus.conf").apply { writeText(config(dir)) }
            val process = ProcessBuilder(daemon, "--config-file=${config.path}", "--nofork", "--nopidfile", "--print-address=1")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            val address = process.inputStream.bufferedReader().readLine()?.trim()
            if (address.isNullOrEmpty()) {
                process.destroyForcibly()
                dir.deleteRecursively()
                assumeTrue("dbus-daemon didn't start", false)
            }
            return PrivateBus(process, address!!, dir)
        }

        /** A session bus with no service directories (nothing activated) that lets everyone in it talk and own names. */
        private fun config(dir: File) = """
            <!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-BUS Bus Configuration 1.0//EN"
              "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
            <busconfig>
              <type>session</type>
              <listen>unix:dir=${dir.path}</listen>
              <auth>EXTERNAL</auth>
              <policy context="default">
                <allow send_destination="*" eavesdrop="true"/>
                <allow eavesdrop="true"/>
                <allow own="*"/>
              </policy>
            </busconfig>
        """.trimIndent()
    }
}
