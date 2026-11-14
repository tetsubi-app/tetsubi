package gg.dekiru.musicplayer.data.auth

import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.api.JellyfinError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One Quick Connect login (as on iOS): start it, show the 6-digit code, then
 * poll every [pollIntervalMillis] until it's approved on another device, the
 * code expires, or [maxAttempts] polls pass (5 minutes by default: time to switch devices and approve).
 * [cancel] (or the scope ending) stops it; a late answer can't sign in.
 */
class QuickConnectController(
    private val authenticator: Authenticator,
    private val scope: CoroutineScope,
    private val pollIntervalMillis: Long = 2_000,
    private val maxAttempts: Int = 150,
) {
    sealed interface State {
        data object Idle : State
        data object Starting : State
        data class Pending(val code: String) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Called once when the login succeeds, with the signed-in API. */
    var onConnected: ((JellyfinApi) -> Unit)? = null

    private var job: Job? = null

    fun start(serverUrl: String) {
        cancel()
        _state.value = State.Starting
        job = scope.launch { run(serverUrl) }
    }

    /** Stops polling. Leaves [state] as it is. */
    fun cancel() {
        job?.cancel()
        job = null
    }

    private suspend fun run(serverUrl: String) {
        val api = authenticator.api(serverUrl)
        val start = try {
            api.startQuickConnect()
        } catch (e: JellyfinError) {
            return fail(e.message ?: "Quick Connect failed.")
        }
        val code = start.code.orEmpty()
        val secret = start.secret.orEmpty()
        if (code.isEmpty() || secret.isEmpty()) return fail("The server didn't return a login code.")
        _state.value = State.Pending(code)

        var attempts = 0
        while (true) {
            attempts++
            if (attempts > maxAttempts) {
                return fail("The login didn't complete in time. Try again, or sign in manually.")
            }
            val poll = try {
                api.pollQuickConnect(secret)
            } catch (e: JellyfinError) {
                return fail(e.message ?: "Quick Connect failed.")
            }
            if (poll.isApproved) break
            delay(pollIntervalMillis)
        }

        val signedIn = try {
            authenticator.completeQuickConnect(serverUrl, secret)
        } catch (e: JellyfinError) {
            return fail(e.message ?: "Quick Connect failed.")
        }
        _state.value = State.Idle
        job = null
        onConnected?.invoke(signedIn)
    }

    private fun fail(message: String) {
        _state.value = State.Failed(message)
    }
}
