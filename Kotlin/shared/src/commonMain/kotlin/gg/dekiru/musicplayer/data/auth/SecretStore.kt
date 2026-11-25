package gg.dekiru.musicplayer.data.auth

/**
 * Small encrypted key–value storage for the login (the iOS Keychain's job).
 * Android: Keystore-backed ([AndroidSecretStore]). Linux: the desktop's
 * keyring (Secret Service), else a private file (`desktopSecretStore`).
 */
interface SecretStore {
    fun get(key: String): String?

    /** `null` removes the key. */
    fun set(key: String, value: String?)

    /** Removes everything (sign out). */
    fun clear()
}

/** The keys Tetsubi stores. */
object SecretKeys {
    const val SERVER_URL = "serverUrl"
    const val ACCESS_TOKEN = "accessToken"
    const val USER_ID = "userId"
    const val USER_NAME = "userName"
    const val DEVICE_ID = "deviceId"
}

/** Keeps values in memory only. For tests, and as a last resort. */
class InMemorySecretStore : SecretStore {
    private val values = mutableMapOf<String, String>()
    override fun get(key: String): String? = values[key]
    override fun set(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
    override fun clear() = values.clear()
}
