package gg.dekiru.musicplayer.playback

import gg.dekiru.musicplayer.data.api.TetsubiHttp
import gg.dekiru.musicplayer.data.api.TetsubiOkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class StreamHttpClientsTest {
    private val converted = "http://server/Audio/t1/universal?UserId=u&Container=flac&StartTimeTicks=490000000"
    private val direct = "http://server/Audio/t1/stream.flac?static=true"

    @Test
    fun directStreamsWait15sToConnectAnd30sToRead() {
        val client = StreamHttpClients.direct()
        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(30_000, client.readTimeoutMillis)
        assertNull(StreamTimeouts.firstResponseLimitMillis(direct))
    }

    @Test
    fun convertedStreamsWait8sToConnectAndForTheFirstResponse() {
        val client = StreamHttpClients.converted()
        assertEquals(8_000, client.connectTimeoutMillis)
        // Later reads keep 30 s; the first response is cut at 8 s by FirstResponseDataSource.
        assertEquals(30_000, client.readTimeoutMillis)
        assertEquals(8_000L, StreamTimeouts.firstResponseLimitMillis(converted))
    }

    @Test
    fun convertedStreamsUseHttp1AndEverythingElseKeepsHttp2() {
        assertEquals(listOf(okhttp3.Protocol.HTTP_1_1), StreamHttpClients.converted().protocols)
        assertEquals(TetsubiOkHttp.client.protocols, StreamHttpClients.direct().protocols)
        assertEquals(TetsubiOkHttp.client.protocols, TetsubiOkHttp.client.newBuilder().build().protocols)
    }

    @Test
    fun streamsShareTheAppsConnectionsButNotItsRequestQueue() {
        val app = TetsubiOkHttp.client
        val streams = listOf(StreamHttpClients.direct(), StreamHttpClients.converted())
        for (client in streams) {
            assertSame(app.connectionPool, client.connectionPool)
            assertSame(app.dns, client.dns)
            assertSame(app.proxySelector, client.proxySelector)
            assertEquals(app.proxy, client.proxy)
            assertNotSame(app.dispatcher, client.dispatcher)
            assertEquals(client.dispatcher.maxRequests, client.dispatcher.maxRequestsPerHost)
            assertEquals(true, client.followSslRedirects)
        }
        assertSame(streams[0].dispatcher, streams[1].dispatcher)
    }

    @Test
    fun theApiClientIsBuiltOnTheSameOkHttpClient() {
        assertSame(TetsubiOkHttp.client, (TetsubiHttp.client.engine.config as OkHttpConfig).preconfigured)
    }

    @Test
    fun derivesFromTheClientItIsGiven() {
        val base = okhttp3.OkHttpClient()
        assertSame(base.connectionPool, StreamHttpClients.converted(base).connectionPool)
        assertNotSame(TetsubiOkHttp.client.connectionPool, StreamHttpClients.direct(base).connectionPool)
    }
}
