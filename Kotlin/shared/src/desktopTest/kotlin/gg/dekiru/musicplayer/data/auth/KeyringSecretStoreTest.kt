package gg.dekiru.musicplayer.data.auth

import gg.dekiru.musicplayer.testing.PrivateBus
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The login in a real keyring: GNOME Keyring on a private D-Bus, with a
 * throwaway home (its login keyring made and unlocked at start). Skipped
 * where dbus-daemon or gnome-keyring-daemon isn't installed.
 */
class KeyringSecretStoreTest {
    private val home: File = Files.createTempDirectory("tetsubi-keyring").toFile()
    private val file = File(home, "login.properties")
    private var bus: PrivateBus? = null
    private var keyringDaemon: Process? = null

    @AfterTest
    fun tearDown() {
        keyringDaemon?.let { it.destroy(); it.waitFor(2, TimeUnit.SECONDS) }
        bus?.close()
        home.deleteRecursively()
    }

    private fun startKeyring(): String {
        val daemon = File("/usr/bin/gnome-keyring-daemon")
        assumeTrue("gnome-keyring-daemon isn't installed", daemon.canExecute())
        val b = PrivateBus.start().also { bus = it }
        val process = ProcessBuilder(daemon.path, "--foreground", "--unlock", "--components=secrets")
            .apply {
                environment()["DBUS_SESSION_BUS_ADDRESS"] = b.address
                environment()["HOME"] = home.path
                environment()["XDG_DATA_HOME"] = File(home, "data").path
                environment()["XDG_RUNTIME_DIR"] = File(home, "run").apply { mkdirs() }.path
            }
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        keyringDaemon = process
        // --unlock reads the login keyring's password from stdin, and makes that keyring (the default).
        process.outputStream.use { it.write("test".toByteArray()) }
        // Wait for it to take its name on the bus.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val connected = runCatching { SecretServiceClient.connect(KeyringSecretStore.ATTRIBUTES, "t", b.address).close() }.isSuccess
            if (connected) return b.address
            Thread.sleep(100)
        }
        assumeTrue("gnome-keyring-daemon didn't start", false)
        error("unreachable")
    }

    @Test
    fun keepsTheLoginInTheKeyring() {
        val address = startKeyring()
        val store = desktopSecretStore(file, address)
        assertIs<KeyringSecretStore>(store)
        store.set(SecretKeys.SERVER_URL, "https://music.example")
        store.set(SecretKeys.ACCESS_TOKEN, "token, with = odd: characters")
        assertFalse(file.exists(), "nothing written to the file")

        // A new run reads it back.
        val again = desktopSecretStore(file, address)
        assertEquals("https://music.example", again.get(SecretKeys.SERVER_URL))
        assertEquals("token, with = odd: characters", again.get(SecretKeys.ACCESS_TOKEN))

        // Sign out removes it.
        again.clear()
        assertNull(desktopSecretStore(file, address).get(SecretKeys.SERVER_URL))
    }

    @Test
    fun movesAnOldLoginFileIntoTheKeyring() {
        val address = startKeyring()
        FileSecretStore(file).apply {
            set(SecretKeys.SERVER_URL, "https://music.example")
            set(SecretKeys.ACCESS_TOKEN, "abc")
        }
        assertTrue(file.exists())
        val store = desktopSecretStore(file, address)
        assertIs<KeyringSecretStore>(store)
        assertEquals("abc", store.get(SecretKeys.ACCESS_TOKEN))
        assertFalse(file.exists(), "the old file is deleted")
        assertEquals("abc", desktopSecretStore(file, address).get(SecretKeys.ACCESS_TOKEN))
    }

    @Test
    fun usesTheFileWithoutAKeyring() {
        // A bus with nothing offering Secret Service.
        val b = PrivateBus.start().also { bus = it }
        val store = desktopSecretStore(file, b.address)
        assertIs<FileSecretStore>(store)
        store.set(SecretKeys.ACCESS_TOKEN, "abc")
        assertTrue(file.exists())
    }

    @Test
    fun encodesEveryCharacter() {
        val values = mapOf("a" to "x=y:z, \\ \n é", "b" to "")
        assertEquals(values, KeyringSecretStore.decode(KeyringSecretStore.encode(values)))
    }
}
