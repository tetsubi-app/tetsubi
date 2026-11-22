package gg.dekiru.musicplayer.ui.recent

import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * The exported picture's files (Android's clipboard copy and its "Save"
 * hand-over to the system picker): written whole or not at all, and never
 * passed on empty.
 */
internal object ExportFiles {

    /**
     * Writes [png] to [file] through a temporary file beside it, then
     * renames it into place, so a reader never sees half a picture. Throws
     * when [png] is empty or the file doesn't end up [png]'s size.
     */
    fun writeWhole(file: File, png: ByteArray) {
        if (png.isEmpty()) throw IOException("The image is empty.")
        val dir = file.absoluteFile.parentFile ?: throw IOException("No folder for ${file.name}.")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Couldn't make ${dir.name}.")
        val temp = File(dir, "${file.name}.part")
        try {
            temp.outputStream().use { out ->
                out.write(png)
                out.flush()
            }
            if (!temp.renameTo(file)) {
                // Some file systems won't rename over a file: remove it first.
                file.delete()
                if (!temp.renameTo(file)) throw IOException("Couldn't write ${file.name}.")
            }
        } finally {
            temp.delete()
        }
        if (file.length() != png.size.toLong()) throw IOException("${file.name} was written short.")
    }

    /**
     * Copies [file] into the stream [open] gives (flushed, then closed) and
     * returns the bytes copied. Throws when [file] is missing or empty (then
     * [open] isn't called), when [open] gives no stream, or when fewer bytes
     * than the file's go across.
     */
    fun copyInto(file: File, open: () -> OutputStream?): Long {
        val expected = file.length()
        if (!file.isFile || expected <= 0L) throw IOException("The image to save is missing.")
        val out = open() ?: throw IOException("The chosen file couldn't be opened.")
        val copied = out.use { stream ->
            val n = file.inputStream().use { it.copyTo(stream) }
            stream.flush()
            n
        }
        if (copied != expected) throw IOException("Only $copied of $expected bytes were saved.")
        return copied
    }
}
