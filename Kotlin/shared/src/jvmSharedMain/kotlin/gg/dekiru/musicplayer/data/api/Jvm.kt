package gg.dekiru.musicplayer.data.api

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.OkHttpClient
import java.text.Normalizer
import java.util.UUID

private val combiningMarks = Regex("\\p{Mn}+")

internal actual fun stripDiacritics(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(combiningMarks, "")

/**
 * The app's one OkHttp client: its connection pool, DNS and proxy settings.
 * Ktor's engine ([TetsubiHttp]) is built on it, and Android's player streams
 * over clients derived from it (`StreamHttpClients`), so a stream reuses the
 * connection the API calls already have open instead of setting up its own
 * (a phone's first stream reached the server ~8 s late on a separate stack).
 * Derive with `newBuilder()`, which keeps the pool; never build a second one.
 */
object TetsubiOkHttp {
    val client: OkHttpClient by lazy { OkHttpClient() }
}

internal actual fun newHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(OkHttp) {
    engine { preconfigured = TetsubiOkHttp.client }
    configure()
}

internal actual fun randomUuid(): String = UUID.randomUUID().toString()
