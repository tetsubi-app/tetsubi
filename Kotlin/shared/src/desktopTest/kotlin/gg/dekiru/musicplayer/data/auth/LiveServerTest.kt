package gg.dekiru.musicplayer.data.auth

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Against a real server on this network. Skipped unless set:
 * - TETSUBI_LIVE_ADDRESS: an address as a user might type it, e.g. "192.168.1.50"
 * - TETSUBI_LIVE_DISCOVERY=1: a server answering discovery on UDP 7359
 */
class LiveServerTest {

    @Test
    fun liveResolve() = runBlocking {
        val input = System.getenv("TETSUBI_LIVE_ADDRESS").orEmpty()
        if (input.isEmpty()) return@runBlocking
        val result = ServerAddress.resolve(input)
        println("Resolved $input → $result")
        assertIs<ServerAddress.Result.Found>(result)
        Unit
    }

    @Test
    fun liveDiscovery() = runBlocking {
        if (System.getenv("TETSUBI_LIVE_DISCOVERY") != "1") return@runBlocking
        val servers = ServerDiscovery.search()
        println("Found: $servers")
        assertTrue(servers.isNotEmpty())
    }
}
