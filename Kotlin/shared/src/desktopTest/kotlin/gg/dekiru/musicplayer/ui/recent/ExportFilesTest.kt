package gg.dekiru.musicplayer.ui.recent

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The export's files: Android's clipboard copy and "Save" go through these (the JVM code is shared). */
class ExportFilesTest {

    private val dir: File = Files.createTempDirectory("export-files").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** A stream that remembers being flushed and closed. */
    private class FakeStream : OutputStream() {
        val bytes = ByteArrayOutputStream()
        var flushed = false
        var closed = false
        override fun write(b: Int) = bytes.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = bytes.write(b, off, len)
        override fun flush() {
            flushed = true
        }
        override fun close() {
            closed = true
        }
    }

    private val png = ByteArray(5000) { (it % 251).toByte() }

    @Test
    fun writesTheWholePictureAndCopiesItIntoTheChosenFile() {
        val file = File(dir, "exports/pending-save.png")
        ExportFiles.writeWhole(file, png)
        assertEquals(png.size.toLong(), file.length())
        assertFalse(File(dir, "exports/pending-save.png.part").exists())

        val out = FakeStream()
        assertEquals(png.size.toLong(), ExportFiles.copyInto(file) { out })
        assertContentEquals(png, out.bytes.toByteArray())
        assertTrue(out.flushed)
        assertTrue(out.closed)
    }

    @Test
    fun aNewPictureReplacesTheOldOne() {
        val file = File(dir, "pending-save.png")
        ExportFiles.writeWhole(file, ByteArray(10) { 1 })
        ExportFiles.writeWhole(file, png)
        assertContentEquals(png, file.readBytes())
    }

    @Test
    fun anEmptyPictureIsNeverWritten() {
        val file = File(dir, "pending-save.png")
        assertFailsWith<IOException> { ExportFiles.writeWhole(file, ByteArray(0)) }
        assertFalse(file.exists())
    }

    @Test
    fun aMissingOrEmptyPictureIsntCopiedAndTheFileIsntOpened() {
        var opened = false
        val missing = File(dir, "gone.png")
        assertFailsWith<IOException> { ExportFiles.copyInto(missing) { opened = true; FakeStream() } }
        val empty = File(dir, "empty.png").apply { writeBytes(ByteArray(0)) }
        assertFailsWith<IOException> { ExportFiles.copyInto(empty) { opened = true; FakeStream() } }
        assertFalse(opened)
    }

    @Test
    fun noStreamIsAFailure() {
        val file = File(dir, "pending-save.png").apply { writeBytes(png) }
        assertFailsWith<IOException> { ExportFiles.copyInto(file) { null } }
    }

    @Test
    fun aFailingWriteIsAFailureAndTheStreamIsStillClosed() {
        val file = File(dir, "pending-save.png").apply { writeBytes(png) }
        var closed = false
        val broken = object : OutputStream() {
            override fun write(b: Int) = throw IOException("disk full")
            override fun close() {
                closed = true
            }
        }
        assertFailsWith<IOException> { ExportFiles.copyInto(file) { broken } }
        assertTrue(closed)
    }
}
