package gg.dekiru.musicplayer.data.api

import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** URL building, request shapes and error mapping, against a pretend server. */
class JellyfinApiTest {

    private val authAnswer = """
        {"User":{"Id":"user-1","Name":"jake"},"AccessToken":"tok-1","ServerId":"srv",
         "SessionInfo":{"Id":"session-9","UserId":"user-1"},"Extra":"ignored"}
    """

    // URL building

    @Test
    fun urlJoinsBaseAndPath() {
        val api = FakeServer { json("{}") }.api()
        assertEquals("http://jellyfin-box:8096/Users/Me", api.url("Users/Me"))
        assertEquals("http://jellyfin-box:8096/Users/Me", api.url("/Users/Me"))
    }

    @Test
    fun urlKeepsTheServersOwnPath() {
        // A server behind a proxy at /jellyfin; a trailing slash on the base is ignored.
        val api = FakeServer { json("{}") }.api("https://example.com/jellyfin/")
        assertEquals("https://example.com/jellyfin", api.baseUrl)
        assertEquals("https://example.com/jellyfin/System/Info/Public", api.url("System/Info/Public"))
    }

    @Test
    fun urlEncodesQuery() {
        val api = FakeServer { json("{}") }.api()
        assertEquals(
            "http://jellyfin-box:8096/QuickConnect/Connect?secret=a+b%26c",
            api.url("QuickConnect/Connect", listOf("secret" to "a b&c")),
        )
    }

    // Sign-in

    @Test
    fun authenticateByNameSendsUsernameAndPw() = runTest {
        val server = FakeServer { json(authAnswer) }
        val response = server.api(token = "old").authenticateByName("jake", "secret")

        val request = server.requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/Users/AuthenticateByName", request.path)
        assertEquals("""{"Username":"jake","Pw":"secret"}""", request.bodyText)
        assertFalse("Token=" in request.authorization, "sign-in must not send a token")
        assertTrue(request.authorization.startsWith("MediaBrowser Client=\"Tetsubi\""))

        assertEquals("tok-1", response.accessToken)
        assertEquals("user-1", response.resolvedUserId)
        assertEquals("jake", response.user?.name)
    }

    @Test
    fun wrongPasswordIsReportedAsSuch() = runTest {
        val server = FakeServer { json("", HttpStatusCode.Unauthorized) }
        assertFailsWith<JellyfinError.WrongCredentials> { server.api().authenticateByName("jake", "nope") }
    }

    @Test
    fun userIdFallsBackToSessionInfo() = runTest {
        val server = FakeServer { json("""{"AccessToken":"t","SessionInfo":{"Id":"session-9","UserId":"user-2"}}""") }
        assertEquals("user-2", server.api().authenticateByName("a", "b").resolvedUserId)
    }

    @Test
    fun signedInCallsSendTheToken() = runTest {
        val server = FakeServer { json("""{"Id":"user-1","Name":"jake"}""") }
        val user = server.api(token = "tok-1", userId = "user-1").currentUser()
        assertEquals("jake", user.name)
        assertEquals("/Users/Me", server.requests.single().path)
        assertTrue("Token=\"tok-1\"" in server.requests.single().authorization)
    }

    // Quick Connect (ported from the Swift request-shape tests)

    @Test
    fun quickConnectStartIsAPostWithoutAToken() = runTest {
        val server = FakeServer { json("""{"Secret":"abc","Code":"123456","Authenticated":false}""") }
        val start = server.api(token = "tok-1").startQuickConnect()
        val request = server.requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/QuickConnect/Initiate", request.path)
        assertTrue("MediaBrowser" in request.authorization)
        assertFalse("Token=" in request.authorization, "Initiate must not carry a user token")
        assertEquals("123456", start.code)
        assertEquals("abc", start.secret)
    }

    @Test
    fun quickConnectPollCarriesTheSecretAndNoToken() = runTest {
        val server = FakeServer { json("""{"Authenticated":true}""") }
        val poll = server.api(token = "tok-1").pollQuickConnect("abc")
        val request = server.requests.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("/QuickConnect/Connect", request.path)
        assertEquals("abc", request.url.parameters["secret"])
        assertFalse("Token=" in request.authorization)
        assertTrue(poll.isApproved)
    }

    @Test
    fun quickConnectPollPendingUntilAuthenticated() = runTest {
        assertFalse(FakeServer { json("""{"Authenticated":false}""") }.api().pollQuickConnect("s").isApproved)
        assertFalse(FakeServer { json("{}") }.api().pollQuickConnect("s").isApproved)
    }

    @Test
    fun quickConnectDisabledIs401() = runTest {
        val server = FakeServer { json("", HttpStatusCode.Unauthorized) }
        assertFailsWith<JellyfinError.QuickConnectDisabled> { server.api().startQuickConnect() }
    }

    @Test
    fun quickConnectExpiredIs404() = runTest {
        val server = FakeServer { json("", HttpStatusCode.NotFound) }
        assertFailsWith<JellyfinError.QuickConnectExpired> { server.api().pollQuickConnect("old") }
    }

    @Test
    fun quickConnectExchangeSendsTheSecret() = runTest {
        val server = FakeServer { json(authAnswer) }
        server.api().authenticateWithQuickConnect("abc")
        val request = server.requests.single()
        assertEquals("/Users/AuthenticateWithQuickConnect", request.path)
        assertEquals("""{"Secret":"abc"}""", request.bodyText)
    }

    // Errors

    @Test
    fun serverErrorsKeepStatusAndBody() = runTest {
        val server = FakeServer { json("boom", HttpStatusCode.InternalServerError) }
        val error = assertFailsWith<JellyfinError.Http> { server.api(token = "t", userId = "u").currentUser() }
        assertEquals(500, error.status)
        assertEquals("HTTP 500 — boom", error.message)
    }

    @Test
    fun unreadableAnswersAreDecodeErrors() = runTest {
        val server = FakeServer { json("<html>router</html>") }
        assertFailsWith<JellyfinError.Decode> { server.api().startQuickConnect() }
    }

    @Test
    fun connectionFailuresAreNetworkErrors() = runTest {
        val server = FakeServer { throw IllegalStateException("connection refused") }
        val error = assertFailsWith<JellyfinError.Network> { server.api().startQuickConnect() }
        assertTrue("connection refused" in error.message.orEmpty())
    }
}
