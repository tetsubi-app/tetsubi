package gg.dekiru.musicplayer.data.auth

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import java.util.concurrent.TimeUnit

/**
 * The login in a file only this user can read
 * (`$XDG_DATA_HOME/tetsubi/login.properties`, else `~/.local/share/tetsubi/`).
 *
 * A stand-in until the Linux phase moves it into the system keyring
 * (Secret Service), with this as the fallback when no keyring runs.
 */
class FileSecretStore(private val file: File) : SecretStore {

    private val values = Properties()

    init {
        try {
            if (file.exists()) file.inputStream().use(values::load)
        } catch (e: IOException) {
            // Unreadable: start empty; the user signs in again.
        }
    }

    @Synchronized
    override fun get(key: String): String? = values.getProperty(key)

    @Synchronized
    override fun set(key: String, value: String?) {
        if (value == null) values.remove(key) else values.setProperty(key, value)
        save()
    }

    @Synchronized
    override fun clear() {
        values.clear()
        save()
    }

    private fun save() {
        try {
            file.parentFile?.mkdirs()
            if (!file.exists()) {
                file.createNewFile()
                try {
                    Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
                } catch (e: UnsupportedOperationException) {
                    // Not a POSIX file system (Windows): the user's profile folder is private anyway.
                }
            }
            file.outputStream().use { values.store(it, "Tetsubi login") }
        } catch (e: IOException) {
            // Can't save: the login lasts until the app closes.
        }
    }

    companion object {
        fun default(): FileSecretStore {
            val dataHome = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
                ?: (System.getProperty("user.home") + "/.local/share")
            return FileSecretStore(File(dataHome, "tetsubi/login.properties"))
        }
    }
}

/** The computer's name, sent to the server ("jellyfin-box"). Not `InetAddress.getLocalHost()`: that can stall for seconds. */
fun desktopDeviceName(): String {
    File("/etc/hostname").takeIf { it.canRead() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    return try {
        val process = ProcessBuilder("hostname").redirectErrorStream(true).start()
        val name = process.inputStream.bufferedReader().readText().trim()
        process.waitFor(2, TimeUnit.SECONDS)
        name.removeSuffix(".local").ifEmpty { "Linux" }
    } catch (e: IOException) {
        "Linux"
    }
}
