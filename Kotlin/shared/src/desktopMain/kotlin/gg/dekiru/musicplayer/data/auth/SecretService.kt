@file:Suppress("FunctionName", "ktlint:standard:function-naming")

package gg.dekiru.musicplayer.data.auth

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// The Secret Service API (freedesktop.org), as GNOME Keyring, KWallet (KDE
// Frameworks 5.97 and later) and KeePassXC offer it on the session bus.

/** Two return values. */
class TwoValues<A, B>(
    @field:Position(0) @JvmField val first: A,
    @field:Position(1) @JvmField val second: B,
) : Tuple()

/** A secret as the API carries it (`(oayays)`): the session, parameters (none for "plain"), the value and its type. */
class SecretValue(
    @field:Position(0) @JvmField val session: DBusPath,
    @field:Position(1) @JvmField val parameters: ByteArray,
    @field:Position(2) @JvmField val value: ByteArray,
    @field:Position(3) @JvmField val contentType: String,
) : Struct()

@DBusInterfaceName("org.freedesktop.Secret.Service")
interface SecretServiceApi : DBusInterface {
    fun OpenSession(algorithm: String, input: Variant<*>): TwoValues<Variant<*>, DBusPath>
    fun SearchItems(attributes: Map<String, String>): TwoValues<List<DBusPath>, List<DBusPath>>
    fun Unlock(objects: List<DBusPath>): TwoValues<List<DBusPath>, DBusPath>
    fun ReadAlias(name: String): DBusPath
    fun CreateCollection(properties: Map<String, Variant<*>>, alias: String): TwoValues<DBusPath, DBusPath>
}

@DBusInterfaceName("org.freedesktop.Secret.Collection")
interface SecretCollectionApi : DBusInterface {
    fun CreateItem(properties: Map<String, Variant<*>>, secret: SecretValue, replace: Boolean): TwoValues<DBusPath, DBusPath>
}

@DBusInterfaceName("org.freedesktop.Secret.Item")
interface SecretItemApi : DBusInterface {
    fun GetSecret(session: DBusPath): SecretValue
    fun Delete(): DBusPath
}

@DBusInterfaceName("org.freedesktop.Secret.Session")
interface SecretSessionApi : DBusInterface {
    fun Close()
}

@DBusInterfaceName("org.freedesktop.Secret.Prompt")
interface SecretPromptApi : DBusInterface {
    fun Prompt(windowId: String)

    class Completed(path: String, val dismissed: Boolean, val result: Variant<*>) : DBusSignal(path, dismissed, result)
}

/** The keyring was there but stayed locked (its unlock prompt was dismissed or timed out). */
class KeyringLockedException(message: String) : Exception(message)

/**
 * One secret in the desktop's keyring, found by [attributes]. Blocking
 * calls; a locked keyring asks the user to unlock it (the desktop's own
 * prompt), waiting up to [PROMPT_TIMEOUT_SECONDS].
 *
 * The session is "plain": the secret crosses the session bus unencrypted,
 * which only this user's processes can read. (libsecret encrypts that hop
 * too; worth adding later.)
 */
internal class SecretServiceClient private constructor(
    private val connection: DBusConnection,
    private val session: DBusPath,
    private val attributes: Map<String, String>,
    private val label: String,
) : AutoCloseable {

    private val service = remote<SecretServiceApi>(SERVICE_PATH)

    /** The secret, or null if there's none. */
    fun read(): String? {
        val item = findItem() ?: return null
        val secret = remote<SecretItemApi>(item.path).GetSecret(session)
        return String(secret.value, Charsets.UTF_8)
    }

    /** Saves `value`, replacing the one there (in the default keyring). */
    fun write(value: String) {
        val collection = defaultCollection()
        unlock(listOf(collection))
        val properties = mapOf<String, Variant<*>>(
            "org.freedesktop.Secret.Item.Label" to Variant(label),
            "org.freedesktop.Secret.Item.Attributes" to Variant(attributes, "a{ss}"),
        )
        val secret = SecretValue(session, ByteArray(0), value.toByteArray(Charsets.UTF_8), "text/plain; charset=utf8")
        val (_, prompt) = remote<SecretCollectionApi>(collection.path).CreateItem(properties, secret, true).let { it.first to it.second }
        if (prompt.path != "/") runPrompt(prompt)
    }

    /** Removes the secret, if there is one. */
    fun delete() {
        val (unlocked, locked) = service.SearchItems(attributes).let { it.first to it.second }
        for (item in unlocked + locked) {
            val prompt = remote<SecretItemApi>(item.path).Delete()
            if (prompt.path != "/") runPrompt(prompt)
        }
    }

    /**
     * The default keyring. Where there's none yet (a desktop that never made
     * one), one is made as libsecret does: the desktop asks the user for its
     * password. Unverified: no desktop without one was tried.
     */
    private fun defaultCollection(): DBusPath {
        service.ReadAlias("default").takeIf { it.path != "/" }?.let { return it }
        val properties = mapOf<String, Variant<*>>("org.freedesktop.Secret.Collection.Label" to Variant("Login"))
        val created = service.CreateCollection(properties, "default")
        if (created.first.path != "/") return created.first
        val result = runPrompt(created.second)
        return (result?.value as? DBusPath)?.takeIf { it.path != "/" }
            ?: throw IllegalStateException("The keyring has no default collection")
    }

    /** The item, unlocked (asking the user if need be); null if there's none. */
    private fun findItem(): DBusPath? {
        val (unlocked, locked) = service.SearchItems(attributes).let { it.first to it.second }
        unlocked.firstOrNull()?.let { return it }
        val item = locked.firstOrNull() ?: return null
        unlock(listOf(item))
        return item
    }

    private fun unlock(objects: List<DBusPath>) {
        val result = service.Unlock(objects)
        if (result.second.path != "/") runPrompt(result.second)
    }

    /** Shows the desktop's prompt and waits for it; its result. Throws [KeyringLockedException] if it's dismissed. */
    private fun runPrompt(prompt: DBusPath): Variant<*>? {
        val done = CompletableFuture<SecretPromptApi.Completed>()
        val handler = connection.addSigHandler(SecretPromptApi.Completed::class.java) { signal ->
            if (signal.path == prompt.path) done.complete(signal)
        }
        try {
            // No window ID: Compose's AWT window has none to give that every desktop takes.
            remote<SecretPromptApi>(prompt.path).Prompt("")
            val completed = done.get(PROMPT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (completed.dismissed) throw KeyringLockedException("The keyring prompt was dismissed")
            return completed.result
        } catch (e: TimeoutException) {
            throw KeyringLockedException("The keyring prompt got no answer")
        } finally {
            runCatching { handler.close() }
        }
    }

    private inline fun <reified T : DBusInterface> remote(path: String): T =
        connection.getRemoteObject(BUS_NAME, path, T::class.java)

    override fun close() {
        runCatching { remote<SecretSessionApi>(session.path).Close() }
        runCatching { connection.close() }
    }

    companion object {
        const val BUS_NAME = "org.freedesktop.secrets"
        private const val SERVICE_PATH = "/org/freedesktop/secrets"
        const val PROMPT_TIMEOUT_SECONDS = 120L

        /**
         * Opens a session with the keyring (D-Bus starts it if it's
         * installed but not running). Throws where there's no session bus or
         * no Secret Service. `busAddress`: another bus than the session's (tests).
         */
        fun connect(attributes: Map<String, String>, label: String, busAddress: String? = null): SecretServiceClient {
            val builder = if (busAddress != null) DBusConnectionBuilder.forAddress(busAddress) else DBusConnectionBuilder.forSessionBus()
            val connection = builder.withShared(false).build()
            try {
                val service = connection.getRemoteObject(BUS_NAME, SERVICE_PATH, SecretServiceApi::class.java)
                val session = service.OpenSession("plain", Variant("")).second
                return SecretServiceClient(connection, session, attributes, label)
            } catch (e: Exception) {
                runCatching { connection.close() }
                throw e
            }
        }
    }
}
