package gg.dekiru.musicplayer.downloads

import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random

/** A fresh empty folder under the system temp directory. */
fun tempDir(): String {
    val dir = Path(SystemTemporaryDirectory, "tetsubi-test-${Random.nextLong().toULong()}").toString()
    LocalFiles.deleteRecursively(dir)
    LocalFiles.createDirectories(dir)
    return dir
}
