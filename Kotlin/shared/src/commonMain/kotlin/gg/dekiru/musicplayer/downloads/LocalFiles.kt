package gg.dekiru.musicplayer.downloads

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.readString
import kotlinx.io.writeString

/** Small file helpers for downloads (kotlinx-io, so the code stays shared). Paths are plain strings. */
internal object LocalFiles {
    private val fs = SystemFileSystem

    fun exists(path: String): Boolean = fs.exists(Path(path))

    /**
     * `path` if it exists, else a finished file in the same folder with the same name apart from the
     * extension (a track saved as FLAC that a manifest still calls m4a); null if neither.
     */
    fun existingFile(path: String): String? {
        if (exists(path)) return path
        val dir = path.substringBeforeLast('/', "")
        val stem = path.substringAfterLast('/').substringBeforeLast('.')
        if (dir.isEmpty() || stem.isEmpty()) return null
        val found = runCatching { list(dir) }.getOrDefault(emptyList()).firstOrNull {
            it.substringBeforeLast('.') == stem && it != stem && !it.endsWith(".part") && !it.endsWith(".tmp") && !it.endsWith(".jpg") && !it.endsWith(".json")
        }
        return found?.let { "$dir/$it" }
    }

    fun isDirectory(path: String): Boolean = fs.metadataOrNull(Path(path))?.isDirectory == true

    /** Size in bytes, 0 if missing. */
    fun size(path: String): Long = fs.metadataOrNull(Path(path))?.takeIf { it.isRegularFile }?.size ?: 0

    /** Names of the entries in `dir` (empty if it's missing). */
    fun list(dir: String): List<String> =
        if (isDirectory(dir)) fs.list(Path(dir)).map { it.name } else emptyList()

    fun createDirectories(path: String) = fs.createDirectories(Path(path))

    /** Deletes a file, or a folder and everything in it. No error if missing. */
    fun deleteRecursively(path: String) {
        val p = Path(path)
        val meta = fs.metadataOrNull(p) ?: return
        if (meta.isDirectory) fs.list(p).forEach { deleteRecursively(it.toString()) }
        fs.delete(p, mustExist = false)
    }

    /** Total size of a file, or of everything in a folder. */
    fun totalSize(path: String): Long {
        val p = Path(path)
        val meta = fs.metadataOrNull(p) ?: return 0
        return if (meta.isDirectory) fs.list(p).sumOf { totalSize(it.toString()) } else maxOf(meta.size, 0)
    }

    fun move(from: String, to: String) = fs.atomicMove(Path(from), Path(to))

    fun readText(path: String): String? =
        if (exists(path)) runCatching { fs.source(Path(path)).buffered().use { it.readString() } }.getOrNull() else null

    fun readBytes(path: String): ByteArray? =
        if (exists(path)) runCatching { fs.source(Path(path)).buffered().use { it.readByteArray() } }.getOrNull() else null

    /** Writes through a temporary file, so a half-written file never replaces a good one. */
    fun writeText(path: String, text: String) {
        val tmp = "$path.tmp"
        fs.sink(Path(tmp)).buffered().use { it.writeString(text) }
        move(tmp, path)
    }

    fun writeBytes(path: String, bytes: ByteArray) {
        val tmp = "$path.tmp"
        fs.sink(Path(tmp)).buffered().use { it.write(bytes) }
        move(tmp, path)
    }

    /** A sink that appends to (or replaces) `path`. */
    fun sink(path: String, append: Boolean) = fs.sink(Path(path), append).buffered()
}
