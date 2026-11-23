package gg.dekiru.musicplayer.data.api

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import kotlinx.serialization.json.Json

/**
 * The one HTTP client the app uses (OkHttp on Android and desktop, over
 * `TetsubiOkHttp.client`, whose connections Android's player shares).
 */
object TetsubiHttp {
    val client: HttpClient by lazy { newHttpClient { tetsubiDefaults() } }
}

/** Timeouts as on iOS: 30 s per request. Errors are checked by hand, not thrown. */
fun HttpClientConfig<*>.tetsubiDefaults() {
    expectSuccess = false
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 10_000
    }
}

/** Jellyfin's JSON: PascalCase keys (mapped with @SerialName), many fields we skip. */
val TetsubiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/** A client on the platform's engine, configured by [configure]. */
internal expect fun newHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient

/** A random ID for this install (`DeviceId` in the header). */
internal expect fun randomUuid(): String
