package gg.dekiru.musicplayer.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A pretend Jellyfin server for tests: records requests, answers with `handler`. */
class FakeServer(
    private val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) {
    val requests = mutableListOf<HttpRequestData>()

    // Requests can arrive on several threads at once (downloads run two tracks together).
    private val lock = Mutex()

    val client = HttpClient(
        MockEngine { request ->
            lock.withLock { requests += request }
            handler(request)
        },
    ) { tetsubiDefaults() }

    val identity = ClientIdentity(deviceName = "Test Pad", deviceId = "device-1", version = "0.1.0")

    fun api(baseUrl: String = "http://jellyfin-box:8096", token: String? = null, userId: String? = null) =
        JellyfinApi(baseUrl, identity, token, userId, client)

    companion object {
        fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }
}

val HttpRequestData.path: String get() = url.encodedPath
val HttpRequestData.authorization: String get() = headers[HttpHeaders.Authorization].orEmpty()
val HttpRequestData.bodyText: String get() = (body as? TextContent)?.text.orEmpty()
