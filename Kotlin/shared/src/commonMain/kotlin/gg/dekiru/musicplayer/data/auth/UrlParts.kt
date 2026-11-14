package gg.dekiru.musicplayer.data.auth

import io.ktor.http.DEFAULT_PORT
import io.ktor.http.Url

/**
 * A server address taken apart, rebuilt exactly as wanted: no trailing
 * slash, port only when given, no query. (Ktor's own `toString` adds or drops
 * parts depending on the scheme's default port.)
 */
internal data class UrlParts(val scheme: String, val host: String, val port: Int?, val path: String) {

    override fun toString(): String = buildString {
        append(scheme).append("://")
        append(if (':' in host) "[$host]" else host)
        if (port != null) append(':').append(port)
        append(path)
    }

    companion object {
        /** Null if `text` isn't an absolute URL with a host. */
        fun parse(text: String): UrlParts? {
            val url = try {
                Url(text)
            } catch (e: Exception) {
                return null
            }
            val host = url.host.removeSurrounding("[", "]")
            if (host.isEmpty() || !text.contains("://")) return null
            return UrlParts(
                scheme = url.protocol.name.lowercase(),
                host = host,
                port = url.specifiedPort.takeIf { it != DEFAULT_PORT },
                path = url.encodedPath.trimEnd('/'),
            )
        }

        private val ipv4 = Regex("""^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$""")

        /** An IPv4 or IPv6 literal, not a host name. */
        fun isIpAddress(host: String): Boolean = ipv4.matches(host) || ':' in host
    }
}
