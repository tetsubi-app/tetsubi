package gg.dekiru.musicplayer.data.auth

import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties

/**
 * The login in the desktop's keyring (Secret Service: GNOME Keyring,
 * KWallet, KeePassXC), as one item holding every key, read once when the
 * app opens. If a save fails (the keyring went away) the value lasts until
 * the app closes, and the failure is logged.
 */
internal class KeyringSecretStore(
    private val keyring: SecretServiceClient,
    initial: Map<String, String>,
) : SecretStore {

    private val values = LinkedHashMap(initial)

    @Synchronized
    override fun get(key: String): String? = values[key]

    @Synchronized
    override fun set(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
        save()
    }

    @Synchronized
    override fun clear() {
        values.clear()
        try {
            keyring.delete()
        } catch (e: Exception) {
            log("Couldn't remove the login from the keyring: ${e.message}")
        }
    }

    private fun save() {
        try {
            if (values.isEmpty()) keyring.delete() else keyring.write(encode(values))
        } catch (e: Exception) {
            log("Couldn't save the login to the keyring (kept until the app closes): ${e.message}")
        }
    }

    companion object {
        /** What the item is found by. The app ID, never the app's name (see docs/gotchas.md). */
        val ATTRIBUTES = mapOf(
            "xdg:schema" to "gg.dekiru.musicplayer.Login",
            "application" to "gg.dekiru.musicplayer",
        )

        /** The item's name in the keyring's own app (Seahorse, KWalletManager); only a label. */
        const val LABEL = "Tetsubi login"

        internal fun encode(values: Map<String, String>): String {
            val properties = Properties()
            values.forEach { (k, v) -> properties.setProperty(k, v) }
            return StringWriter().also { properties.store(it, null) }.toString()
        }

        internal fun decode(text: String): Map<String, String> {
            val properties = Properties()
            properties.load(StringReader(text))
            return properties.stringPropertyNames().associateWith { properties.getProperty(it) }
        }
    }
}

/**
 * Where the desktop keeps the login:
 *
 * - In the keyring (Secret Service) when there is one. A login left in the
 *   old file (`login.properties`, before the keyring) moves in on the first
 *   run and the file is deleted; if it can't be saved there the file stays
 *   and is used.
 * - In memory only, for this run, when the keyring is there but stays
 *   locked (its prompt dismissed): the user signs in again rather than the
 *   login going to a plain file beside a keyring.
 * - In the file (mode 600), as before, when there's no keyring at all
 *   (no session bus, or nothing offering Secret Service).
 *
 * `busAddress`: another bus than the session's (tests).
 */
fun desktopSecretStore(file: File = FileSecretStore.defaultFile(), busAddress: String? = null): SecretStore {
    val keyring = try {
        SecretServiceClient.connect(KeyringSecretStore.ATTRIBUTES, KeyringSecretStore.LABEL, busAddress)
    } catch (e: Throwable) {
        // Throwable: a runtime missing jdk.security.auth fails with NoClassDefFoundError.
        log("No keyring (Secret Service), so the login is kept in ${file.path}: ${e.message}")
        return FileSecretStore(file)
    }
    val saved = try {
        keyring.read()?.let(KeyringSecretStore::decode).orEmpty()
    } catch (e: KeyringLockedException) {
        log("The keyring stayed locked, so the login lasts until the app closes: ${e.message}")
        keyring.close()
        return InMemorySecretStore()
    } catch (e: Exception) {
        log("Couldn't read the keyring, so the login is kept in ${file.path}: ${e.message}")
        keyring.close()
        return FileSecretStore(file)
    }
    if (!file.exists()) return KeyringSecretStore(keyring, saved)

    // Moving from the file: the keyring's login wins if there's one already.
    if (saved.isNotEmpty()) {
        deleteOldLogin(file)
        return KeyringSecretStore(keyring, saved)
    }
    val old = FileSecretStore(file).all()
    if (old.isEmpty()) {
        deleteOldLogin(file)
        return KeyringSecretStore(keyring, emptyMap())
    }
    return try {
        keyring.write(KeyringSecretStore.encode(old))
        deleteOldLogin(file)
        log("Moved the login into the keyring")
        KeyringSecretStore(keyring, old)
    } catch (e: Exception) {
        log("Couldn't move the login into the keyring, so it stays in ${file.path}: ${e.message}")
        keyring.close()
        FileSecretStore(file)
    }
}

private fun deleteOldLogin(file: File) {
    if (!file.delete() && file.exists()) log("Couldn't delete the old login file ${file.path}")
}

private fun log(message: String) = System.err.println("Tetsubi login: $message")
