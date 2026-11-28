package gg.dekiru.musicplayer.ui.signin

import gg.dekiru.musicplayer.data.api.FakeServer
import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import gg.dekiru.musicplayer.data.api.path
import gg.dekiru.musicplayer.data.auth.Authenticator
import gg.dekiru.musicplayer.data.auth.DiscoveredServer
import gg.dekiru.musicplayer.data.auth.InMemorySecretStore
import gg.dekiru.musicplayer.data.auth.QuickConnectController.State
import gg.dekiru.musicplayer.data.auth.ServerAddress
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import gg.dekiru.musicplayer.playback.settle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Discovery only fills in the address; Quick Connect starts from an explicit tap and stops when the address changes. */
class SignInModelTest {
    private val address = "http://jellyfin-box:8096"

    private fun server() = FakeServer { request ->
        when (request.path) {
            "/QuickConnect/Initiate" -> json("""{"Code":"123456","Secret":"s3cret"}""")
            "/QuickConnect/Connect" -> json("""{"Authenticated":false}""")
            else -> json("", HttpStatusCode.NotFound)
        }
    }

    private fun TestScope.model(fake: FakeServer, found: List<DiscoveredServer>) = SignInModel(
        Authenticator(InMemorySecretStore(), "Pad", fake.client), backgroundScope, onConnected = {},
        resolveAddress = { ServerAddress.Result.Found(it) },
        searchNetwork = { found },
    )

    private val one = listOf(DiscoveredServer("id-1", "Jellyfin-Box", "http://jellyfin-box:8096"))

    @Test
    fun oneServerFoundIsPickedButQuickConnectDoesNotStart() = runTest {
        val fake = server()
        val model = model(fake, one)
        model.onAppear()
        settle()
        assertEquals(address, model.serverUrl)
        assertEquals(State.Idle, model.quickConnect.state.value)
        assertEquals(0, fake.requests.count { it.path.startsWith("/QuickConnect") })
    }

    @Test
    fun pickingAServerOnlyFillsTheAddress() = runTest {
        val fake = server()
        val model = model(fake, emptyList())
        model.onAppear()
        settle()
        model.pick(one[0])
        settle()
        assertEquals(address, model.serverUrl)
        assertEquals(0, fake.requests.size)
    }

    @Test
    fun theUseQuickConnectTapStartsIt() = runTest {
        val fake = server()
        val model = model(fake, one)
        model.onAppear()
        settle()
        model.startQuickConnect()
        assertEquals(State.Pending("123456"), model.quickConnect.state.first { it is State.Pending })
        assertEquals(1, fake.requests.count { it.path == "/QuickConnect/Initiate" })
    }

    @Test
    fun changingTheAddressStopsQuickConnectAndForgetsTheCode() = runTest {
        val fake = server()
        val model = model(fake, one)
        model.changeServerUrl(address)
        model.startQuickConnect()
        model.quickConnect.state.first { it is State.Pending }
        model.changeServerUrl("http://other-box:8096")
        assertEquals(State.Idle, model.quickConnect.state.value)
        val polls = fake.requests.count { it.path == "/QuickConnect/Connect" }
        settle()
        testScheduler.advanceTimeBy(10_000)
        assertEquals(polls, fake.requests.count { it.path == "/QuickConnect/Connect" })
    }

    @Test
    fun leavingTheScreenAndThePasswordFormStopQuickConnect() = runTest {
        val fake = server()
        val model = model(fake, emptyList())
        model.changeServerUrl(address)
        model.startQuickConnect()
        model.quickConnect.state.first { it is State.Pending }
        model.useManual()
        assertEquals(State.Idle, model.quickConnect.state.value)
        assertEquals(SignInModel.Mode.Manual, model.mode)

        model.useQuickConnect()
        model.quickConnect.state.first { it is State.Pending }
        model.onDisappear()
        assertEquals(State.Idle, model.quickConnect.state.value)
        assertFalse(model.isFindingServer)
    }

    @Test
    fun withNoAddressQuickConnectStaysIdle() = runTest {
        val fake = server()
        val model = model(fake, emptyList())
        model.startQuickConnect()
        settle()
        assertTrue(model.quickConnect.state.value is State.Idle)
        assertEquals(0, fake.requests.size)
    }
}
