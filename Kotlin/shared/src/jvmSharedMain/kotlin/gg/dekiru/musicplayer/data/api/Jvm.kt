package gg.dekiru.musicplayer.data.api

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp
import java.text.Normalizer
import java.util.UUID

private val combiningMarks = Regex("\\p{Mn}+")

internal actual fun stripDiacritics(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD).replace(combiningMarks, "")

internal actual fun httpEngineFactory(): HttpClientEngineFactory<*> = OkHttp

internal actual fun randomUuid(): String = UUID.randomUUID().toString()
