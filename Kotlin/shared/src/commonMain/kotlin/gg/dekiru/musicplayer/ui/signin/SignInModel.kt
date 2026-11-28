package gg.dekiru.musicplayer.ui.signin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.api.JellyfinError
import gg.dekiru.musicplayer.data.auth.Authenticator
import gg.dekiru.musicplayer.data.auth.DiscoveredServer
import gg.dekiru.musicplayer.data.auth.QuickConnectController
import gg.dekiru.musicplayer.data.auth.ServerAddress
import gg.dekiru.musicplayer.data.auth.ServerDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The sign-in screen's state and actions (as `ServerConfigView`'s on iOS).
 * Quick Connect panel first; username & password as the fallback, shown on any
 * Quick Connect failure. The server field is shared by both, with servers
 * found on the network listed below it (one found and nothing typed: picked).
 * Discovery and picking only fill in the address: Quick Connect (which creates a
 * pending approval request on the server) starts only from the user's explicit
 * "Use Quick Connect" tap, and stops (code discarded) when the address changes,
 * on the manual form and when the screen goes away.
 */
class SignInModel(
    private val authenticator: Authenticator,
    private val scope: CoroutineScope,
    private val onConnected: (JellyfinApi) -> Unit,
    private val resolveAddress: suspend (String) -> ServerAddress.Result = { ServerAddress.resolve(it) },
    private val searchNetwork: suspend () -> List<DiscoveredServer> = { ServerDiscovery.search() },
) {
    enum class Mode { QuickConnect, Manual }

    val quickConnect = QuickConnectController(authenticator, scope).also {
        it.onConnected = { api -> onConnected(api) }
    }

    var mode by mutableStateOf(Mode.QuickConnect)
        private set
    var serverUrl by mutableStateOf("")
        private set
    var username by mutableStateOf("")
    var password by mutableStateOf("")
    var isConnecting by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var discovered by mutableStateOf<List<DiscoveredServer>>(emptyList())
        private set
    var isSearching by mutableStateOf(false)
        private set
    var hasSearched by mutableStateOf(false)
        private set

    /** Working out the full address (scheme, port) before Quick Connect. */
    var isFindingServer by mutableStateOf(false)
        private set

    private var started = false
    private var findJob: Job? = null

    val trimmedServerUrl: String get() = serverUrl.trim()
    val hasServerUrl: Boolean get() = trimmedServerUrl.isNotEmpty()
    val canSignIn: Boolean get() = !isConnecting && password.isNotEmpty() && hasServerUrl

    /** First time the screen shows: search the network. Nothing contacts a server but the search itself. */
    fun onAppear() {
        if (started) return
        started = true
        search()
    }

    /** The user edited the address: a Quick Connect request for the old one is dropped. */
    fun changeServerUrl(text: String) {
        if (text == serverUrl) return
        serverUrl = text
        stopQuickConnect()
    }

    /** The screen goes away: no Quick Connect request is left waiting. */
    fun onDisappear() {
        stopQuickConnect()
    }

    private fun stopQuickConnect() {
        findJob?.cancel()
        isFindingServer = false
        quickConnect.reset()
    }

    /** Called by the screen when Quick Connect fails: reveal the manual form with the reason. */
    fun onQuickConnectFailed(message: String) {
        mode = Mode.Manual
        errorMessage = message
    }

    fun search() {
        if (isSearching) return
        isSearching = true
        scope.launch {
            val servers = searchNetwork()
            discovered = servers
            isSearching = false
            hasSearched = true
            // One server and nothing typed yet: pick it.
            if (servers.size == 1 && !hasServerUrl) pick(servers[0])
        }
    }

    fun pick(server: DiscoveredServer) {
        if (server.address == trimmedServerUrl) return
        changeServerUrl(server.address)
        errorMessage = null
    }

    fun useManual() {
        stopQuickConnect()
        mode = Mode.Manual
    }

    /** "Use Quick Connect": the one way a Quick Connect request starts (also from the manual form's link). */
    fun useQuickConnect() {
        errorMessage = null
        mode = Mode.QuickConnect
        startQuickConnect()
    }

    /** Works out the full address, shows it in the field, then asks it for a code. */
    fun startQuickConnect() {
        findJob?.cancel()
        quickConnect.cancel()
        if (!hasServerUrl) {
            isFindingServer = false
            return
        }
        val typed = trimmedServerUrl
        isFindingServer = true
        findJob = scope.launch {
            val result = resolveAddress(typed)
            // Ignore the answer if the address changed meanwhile.
            if (typed != trimmedServerUrl) return@launch
            isFindingServer = false
            when (result) {
                is ServerAddress.Result.Found -> {
                    serverUrl = result.url
                    quickConnect.start(result.url)
                }
                else -> {
                    mode = Mode.Manual
                    errorMessage = ServerAddress.message(result)
                }
            }
        }
    }

    /** Username & password. */
    fun signIn() {
        if (!canSignIn) return
        errorMessage = null
        isConnecting = true
        findJob?.cancel()
        isFindingServer = false
        val typed = trimmedServerUrl
        scope.launch {
            try {
                when (val result = resolveAddress(typed)) {
                    is ServerAddress.Result.Found -> {
                        serverUrl = result.url
                        val api = authenticator.signIn(result.url, username.trim(), password)
                        onConnected(api)
                    }
                    else -> errorMessage = ServerAddress.message(result)
                }
            } catch (e: JellyfinError) {
                errorMessage = e.message
            } finally {
                isConnecting = false
            }
        }
    }
}
