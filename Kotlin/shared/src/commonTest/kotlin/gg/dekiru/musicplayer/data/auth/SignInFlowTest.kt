package gg.dekiru.musicplayer.data.auth

import gg.dekiru.musicplayer.AppSession
import gg.dekiru.musicplayer.data.api.FakeServer
import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.api.JellyfinError
import gg.dekiru.musicplayer.data.api.path
import gg.dekiru.musicplayer.data.settings.AppEnvironment
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Signing in, remembering the login, restoring it offline, and Quick Connect polling. */
class SignInFlowTest {

    private val authAnswer = """{"User":{"Id":"user-1","Name":"jake"},"AccessToken":"tok-1"}"""

    // Authenticator

    @Test
    fun signInSavesTheLoginAndRestoreNeedsNoNetwork() = runTest {
        val store = InMemorySecretStore()
        val server = FakeServer { json(authAnswer) }
        val auth = Authenticator(store, "Test Pad", server.client)

        val api = auth.signIn("http://jellyfin-box:8096/", "jake", "pw")
        assertEquals("tok-1", api.accessToken)
        assertEquals("user-1", api.userId)
        assertEquals("http://jellyfin-box:8096", store.get(SecretKeys.SERVER_URL))
        assertEquals("jake", auth.savedUserName)

        val before = server.requests.size
        val restored = assertNotNull(Authenticator(store, "Test Pad", server.client).restore())
        assertEquals(before, server.requests.size, "restore must not call the server")
        assertEquals("http://jellyfin-box:8096", restored.baseUrl)
        assertEquals("tok-1", restored.accessToken)
        assertEquals("user-1", restored.userId)
    }

    @Test
    fun deviceIdIsMadeOnceAndKept() {
        val store = InMemorySecretStore()
        val first = Authenticator(store, "Pad").deviceId
        assertEquals(first, Authenticator(store, "Pad").deviceId)
        assertEquals(36, first.length)
    }

    @Test
    fun signOutForgetsTheLogin() = runTest {
        val store = InMemorySecretStore()
        val server = FakeServer { json(authAnswer) }
        val session = AppSession(Authenticator(store, "Pad", server.client), testEnvironment())
        session.signIn(session.authenticator.signIn("http://jellyfin-box:8096", "jake", "pw"))
        assertNotNull(session.api.value)

        session.signOut()
        assertNull(session.api.value)
        assertFalse(session.restore())
        assertNull(store.get(SecretKeys.ACCESS_TOKEN))
    }

    @Test
    fun restoreWithNothingSavedIsSignedOut() {
        assertFalse(AppSession(Authenticator(InMemorySecretStore(), "Pad"), testEnvironment()).restore())
    }

    @Test
    fun loginWithoutTokenIsNotSaved() = runTest {
        val store = InMemorySecretStore()
        val server = FakeServer { json("""{"User":{"Id":"u"}}""") }
        assertFailsWith<JellyfinError.IncompleteLogin> {
            Authenticator(store, "Pad", server.client).signIn("http://jellyfin-box:8096", "a", "b")
        }
        assertNull(store.get(SecretKeys.SERVER_URL))
    }

    // Quick Connect

    /** Approves on the third poll. */
    private fun quickConnectServer(approveOnPoll: Int = 3): FakeServer {
        var polls = 0
        return FakeServer { request ->
            when (request.path) {
                "/QuickConnect/Initiate" -> json("""{"Code":"123456","Secret":"s3cret"}""")
                "/QuickConnect/Connect" -> json("""{"Authenticated":${++polls >= approveOnPoll}}""")
                "/Users/AuthenticateWithQuickConnect" -> json(authAnswer)
                else -> json("", HttpStatusCode.NotFound)
            }
        }
    }

    @Test
    fun quickConnectShowsCodeThenSignsInWhenApproved() = runTest {
        val server = quickConnectServer()
        val store = InMemorySecretStore()
        val controller = QuickConnectController(Authenticator(store, "Pad", server.client), backgroundScope, pollIntervalMillis = 10)
        val connected = CompletableDeferred<JellyfinApi>()
        controller.onConnected = { connected.complete(it) }

        controller.start("http://jellyfin-box:8096")
        assertEquals(QuickConnectController.State.Pending("123456"), controller.state.first { it is QuickConnectController.State.Pending })

        val api = connected.await()
        assertEquals("tok-1", api.accessToken)
        assertEquals(QuickConnectController.State.Idle, controller.state.value)
        assertEquals(3, server.requests.count { it.path == "/QuickConnect/Connect" })
        assertEquals("tok-1", store.get(SecretKeys.ACCESS_TOKEN))
    }

    @Test
    fun quickConnectGivesUpAfterMaxAttempts() = runTest {
        val server = quickConnectServer(approveOnPoll = Int.MAX_VALUE)
        val controller = QuickConnectController(Authenticator(InMemorySecretStore(), "Pad", server.client), backgroundScope, 10, maxAttempts = 4)
        controller.start("http://jellyfin-box:8096")

        val failed = assertIs<QuickConnectController.State.Failed>(controller.state.first { it is QuickConnectController.State.Failed })
        assertTrue("didn't complete in time" in failed.message)
        assertEquals(4, server.requests.count { it.path == "/QuickConnect/Connect" })
    }

    @Test
    fun quickConnectDisabledFailsWithAMessage() = runTest {
        val server = FakeServer { json("", HttpStatusCode.Unauthorized) }
        val controller = QuickConnectController(Authenticator(InMemorySecretStore(), "Pad", server.client), backgroundScope)
        controller.start("http://jellyfin-box:8096")
        val failed = assertIs<QuickConnectController.State.Failed>(controller.state.first { it is QuickConnectController.State.Failed })
        assertTrue("isn't available" in failed.message)
    }

    @Test
    fun quickConnectExpiredCodeFails() = runTest {
        val server = FakeServer { request ->
            if (request.path == "/QuickConnect/Initiate") json("""{"Code":"1","Secret":"s"}""") else json("", HttpStatusCode.NotFound)
        }
        val controller = QuickConnectController(Authenticator(InMemorySecretStore(), "Pad", server.client), backgroundScope)
        controller.start("http://jellyfin-box:8096")
        val failed = assertIs<QuickConnectController.State.Failed>(controller.state.first { it is QuickConnectController.State.Failed })
        assertTrue("expired" in failed.message)
    }

    @Test
    fun cancelledQuickConnectNeverSignsIn() = runTest {
        val server = quickConnectServer(approveOnPoll = 1)
        val controller = QuickConnectController(Authenticator(InMemorySecretStore(), "Pad", server.client), backgroundScope, 10)
        var connected = false
        controller.onConnected = { connected = true }
        controller.start("http://jellyfin-box:8096")
        controller.cancel()
        testScheduler.advanceUntilIdle()
        assertFalse(connected)
    }
}

private fun testEnvironment() = AppEnvironment(cacheDir = "build/test-cache", downloadsDir = "build/test-downloads", preferences = InMemoryPreferences())
