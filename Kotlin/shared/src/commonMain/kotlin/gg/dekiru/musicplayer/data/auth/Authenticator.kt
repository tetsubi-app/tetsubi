package gg.dekiru.musicplayer.data.auth

import gg.dekiru.musicplayer.data.api.ClientIdentity
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.api.JellyfinError
import gg.dekiru.musicplayer.data.api.TetsubiHttp
import gg.dekiru.musicplayer.data.api.randomUuid
import gg.dekiru.musicplayer.data.models.AuthenticationResponse
import io.ktor.client.HttpClient

/**
 * Signs in and remembers the login (as `Authenticator` on iOS): server
 * address, token, user ID and name go in the [SecretStore], so the next
 * launch [restore]s the login without a network round trip (works offline;
 * a rejected token shows up later as a 401).
 */
class Authenticator(
    private val store: SecretStore,
    private val deviceName: String,
    private val http: HttpClient = TetsubiHttp.client,
) {
    /** A random ID made once per install (cleared on sign out, as on iOS). */
    val deviceId: String
        get() = store.get(SecretKeys.DEVICE_ID) ?: randomUuid().also { store.set(SecretKeys.DEVICE_ID, it) }

    val identity: ClientIdentity get() = ClientIdentity(deviceName = deviceName, deviceId = deviceId)

    /** A signed-out API for `serverUrl` (sign-in and Quick Connect calls). */
    fun api(serverUrl: String): JellyfinApi = JellyfinApi(serverUrl, identity, http = http)

    /** Username and password. Saves the login and returns the signed-in API. */
    suspend fun signIn(serverUrl: String, username: String, password: String): JellyfinApi =
        save(serverUrl, api(serverUrl).authenticateByName(username, password))

    /** Swaps an approved Quick Connect secret for a token; saves the login. */
    suspend fun completeQuickConnect(serverUrl: String, secret: String): JellyfinApi =
        save(serverUrl, api(serverUrl).authenticateWithQuickConnect(secret))

    /** The saved login, or null if there's none. No network. */
    fun restore(): JellyfinApi? {
        val url = store.get(SecretKeys.SERVER_URL) ?: return null
        val token = store.get(SecretKeys.ACCESS_TOKEN) ?: return null
        val userId = store.get(SecretKeys.USER_ID) ?: return null
        return api(url).withSession(token, userId)
    }

    /** The signed-in user's name, if the server gave one. */
    val savedUserName: String? get() = store.get(SecretKeys.USER_NAME)

    fun signOut() = store.clear()

    private fun save(serverUrl: String, response: AuthenticationResponse): JellyfinApi {
        val token = response.accessToken ?: throw JellyfinError.IncompleteLogin()
        val userId = response.resolvedUserId ?: throw JellyfinError.IncompleteLogin()
        val api = api(serverUrl).withSession(token, userId)
        store.set(SecretKeys.SERVER_URL, api.baseUrl)
        store.set(SecretKeys.ACCESS_TOKEN, token)
        store.set(SecretKeys.USER_ID, userId)
        store.set(SecretKeys.USER_NAME, response.user?.name)
        return api
    }
}
