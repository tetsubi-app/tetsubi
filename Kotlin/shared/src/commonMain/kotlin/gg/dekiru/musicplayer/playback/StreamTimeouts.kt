package gg.dekiru.musicplayer.playback

/**
 * How long Android's player waits for a stream's server.
 *
 * A stream the server converts on the fly (`/universal`) gets a short limit
 * on its first response: just after launch the route to the server (a VPN,
 * Wi‑Fi coming up) may not carry the very first request, which then hangs
 * until a read timeout with nothing playing. Given up after a few seconds,
 * the reconnect ([StreamReconnect]) asks again at once, from the same
 * point. Once the stream has answered, its reads wait as long as any
 * other's. Direct streams and downloaded files keep Media3's usual
 * behaviour (they have their own retries, resuming by byte range).
 */
object StreamTimeouts {
    /** Connecting to the server for a direct stream. */
    const val CONNECT_MILLIS = 15_000

    /** Each read of a stream once it has answered (and a direct stream's first response). */
    const val READ_MILLIS = 30_000

    /** Connecting to the server for a converted stream. */
    const val CONVERTED_CONNECT_MILLIS = 8_000

    /** A converted stream's first response: connecting, sending the request and the server's answer. */
    const val CONVERTED_FIRST_RESPONSE_MILLIS = 8_000L

    /** The limit on `url`'s first response, in ms; null: none of its own (a direct stream, a file). */
    fun firstResponseLimitMillis(url: String): Long? =
        if (StreamUrls.isConverted(url)) CONVERTED_FIRST_RESPONSE_MILLIS else null
}
