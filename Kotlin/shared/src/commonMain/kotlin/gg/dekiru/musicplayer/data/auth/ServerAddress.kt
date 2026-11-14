package gg.dekiru.musicplayer.data.auth

import gg.dekiru.musicplayer.data.api.TetsubiHttp
import gg.dekiru.musicplayer.data.api.TetsubiJson
import gg.dekiru.musicplayer.data.models.PublicSystemInfo
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Makes the typed server address forgiving (as `ServerAddress` on iOS).
 * People type "192.168.1.20", "jellyfin-box:8096" or "http://192.168.1.20" (no
 * port); this works out the likely full addresses and uses the first one
 * where a Jellyfin server answers `/System/Info/Public` (no sign-in needed).
 *
 * - No `http://` or `https://`: `http://` is added.
 * - No port: the address as typed is tried first (a server behind a web
 *   proxy uses the standard port), then Jellyfin's own: 8096 for http, 8920
 *   for https. With no scheme either, http on 8096 comes first, as that's
 *   how a home server usually runs.
 */
object ServerAddress {

    const val HTTP_PORT = 8096
    const val HTTPS_PORT = 8920

    /** Shown in the empty field and in errors. */
    const val EXAMPLE = "http://192.168.1.20:8096"

    sealed interface Result {
        data class Found(val url: String) : Result
        /** Not an address at all (empty, spaces, no host). */
        data object Invalid : Result
        /** Nothing that answered was a Jellyfin server. */
        data class NotFound(val input: String) : Result
    }

    fun message(result: Result): String? = when (result) {
        is Result.Found -> null
        Result.Invalid -> "Enter your server's address, e.g. $EXAMPLE"
        is Result.NotFound ->
            "Couldn't find a Jellyfin server at ${result.input}. Check the address, and the port (usually $HTTP_PORT)."
    }

    /** The addresses to try, most likely first. Empty if `input` can't be an address. */
    fun candidates(input: String): List<String> {
        var text = input.trim().trimEnd('/')
        if (text.isEmpty() || text.any { it.isWhitespace() }) return emptyList()

        val lower = text.lowercase()
        val hadScheme = lower.startsWith("http://") || lower.startsWith("https://")
        if (!hadScheme) {
            // "jellyfin-box:8096" would otherwise read as scheme "jellyfin-box".
            if ("://" in text) return emptyList()
            text = "http://$text"
        }
        val parts = UrlParts.parse(text) ?: return emptyList()
        fun make(scheme: String, port: Int?) = parts.copy(scheme = scheme, port = port).toString()

        val list = when {
            parts.port != null -> buildList {
                // Port given: use it; without a scheme, https is the fallback.
                add(make(parts.scheme, parts.port))
                if (!hadScheme) add(make("https", parts.port))
            }
            !hadScheme -> listOf(
                make("http", HTTP_PORT),
                make("https", null),
                make("http", null),
                make("https", HTTPS_PORT),
            )
            parts.scheme == "https" -> listOf(make("https", null), make("https", HTTPS_PORT))
            else -> listOf(make("http", null), make("http", HTTP_PORT))
        }
        return list.distinct()
    }

    /** Fetches a URL's body, or null on any failure. Swappable for tests. */
    fun interface Fetch {
        suspend operator fun invoke(url: String): String?
    }

    /** The first candidate address where a Jellyfin server answers. */
    suspend fun resolve(input: String, fetch: Fetch = Fetch { fetchPublicInfo(it) }): Result {
        val list = candidates(input)
        if (list.isEmpty()) return Result.Invalid
        for (base in list) {
            currentCoroutineContext().ensureActive()
            val body = fetch("$base/System/Info/Public")
            if (body != null && isJellyfinInfo(body)) return Result.Found(base)
        }
        return Result.NotFound(input.trim())
    }

    /**
     * Whether `/System/Info/Public` came from a Jellyfin server: it has the
     * server's ID and version; the product name says Jellyfin when present.
     */
    fun isJellyfinInfo(body: String): Boolean {
        val info = try {
            TetsubiJson.decodeFromString(PublicSystemInfo.serializer(), body)
        } catch (e: Exception) {
            return false
        }
        if (info.id.isNullOrEmpty() || info.version == null) return false
        return info.productName?.contains("jellyfin", ignoreCase = true) ?: true
    }

    /**
     * Short timeout: a wrong port usually fails fast, but an address that
     * doesn't exist shouldn't keep the user waiting long per guess.
     */
    suspend fun fetchPublicInfo(url: String, http: HttpClient = TetsubiHttp.client): String? = try {
        val response = http.get(url) {
            header(HttpHeaders.Accept, "application/json")
            timeout { requestTimeoutMillis = 4_000; connectTimeoutMillis = 4_000 }
        }
        if (response.status == HttpStatusCode.OK) response.bodyAsText() else null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
