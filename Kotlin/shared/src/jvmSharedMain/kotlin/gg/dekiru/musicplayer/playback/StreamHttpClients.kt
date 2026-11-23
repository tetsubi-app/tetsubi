package gg.dekiru.musicplayer.playback

import gg.dekiru.musicplayer.data.api.TetsubiOkHttp
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The OkHttp clients Android's player streams over ([StreamTimeouts]'s waits),
 * derived from the app's own client ([TetsubiOkHttp]) so they share its
 * connection pool, DNS and proxy settings: a stream to the server reuses the
 * connection the API calls already have open. (Here rather than in
 * androidMain so the JVM tests can check them; the desktop plays with mpv.)
 *
 * They have their own dispatcher, so a stream never waits in a queue behind
 * API calls or artwork (OkHttp allows 5 requests per host per dispatcher),
 * nor behind converted streams given up by `FirstResponseDataSource` that
 * are still ending by themselves.
 */
object StreamHttpClients {
    /** Direct streams (the original file): 15 s to connect, 30 s for the response and each read. */
    fun direct(base: OkHttpClient = TetsubiOkHttp.client): OkHttpClient =
        derive(base, StreamTimeouts.CONNECT_MILLIS)

    /**
     * Converted streams (`/universal`): 8 s to connect; reads keep 30 s, and the
     * first response is limited to [StreamTimeouts.CONVERTED_FIRST_RESPONSE_MILLIS]
     * by `FirstResponseDataSource`.
     */
    fun converted(base: OkHttpClient = TetsubiOkHttp.client): OkHttpClient =
        derive(base, StreamTimeouts.CONVERTED_CONNECT_MILLIS)

    private val dispatcher: Dispatcher by lazy {
        Dispatcher().apply { maxRequestsPerHost = maxRequests }
    }

    private fun derive(base: OkHttpClient, connectMillis: Int): OkHttpClient = base.newBuilder()
        .dispatcher(dispatcher)
        .connectTimeout(connectMillis.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(StreamTimeouts.READ_MILLIS.toLong(), TimeUnit.MILLISECONDS)
        .build()
}
