package gg.dekiru.musicplayer.data.auth

import gg.dekiru.musicplayer.data.auth.ServerDiscovery.ipv4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Finding Jellyfin servers on the local network. Ported from ServerDiscoveryTests.swift. */
class ServerDiscoveryTest {

    @Test
    fun parsesAServerReply() {
        val json = """{"Address":"http://192.168.1.20:8096","Id":"abc123","Name":"Jellyfin-Box","EndpointAddress":null}"""
        assertEquals(
            DiscoveredServer("abc123", "Jellyfin-Box", "http://192.168.1.20:8096"),
            ServerDiscovery.parse(json, senderIp = "192.168.1.20"),
        )
    }

    @Test
    fun dockerContainerAddressIsSwappedForTheSender() {
        val json = """{"Address":"http://172.19.0.2:8096","Id":"abc123","Name":"Jellyfin-Box"}"""
        assertEquals("http://192.168.1.50:8096", ServerDiscovery.parse(json, senderIp = "192.168.1.50")?.address)
    }

    @Test
    fun ipv6LoopbackAddressIsSwappedForTheSender() = assertEquals(
        "http://192.168.1.50:8096",
        ServerDiscovery.preferredAddress("http://[::1]:8096", senderIp = "192.168.1.50"),
    )

    @Test
    fun hostnameAddressIsKept() = assertEquals(
        "https://music.example.com",
        ServerDiscovery.preferredAddress("https://music.example.com", senderIp = "192.168.1.50"),
    )

    @Test
    fun httpsAndPortSurviveTheSwap() = assertEquals(
        "https://192.168.1.50:8920",
        ServerDiscovery.preferredAddress("https://10.0.0.5:8920", senderIp = "192.168.1.50"),
    )

    @Test
    fun missingNameGetsAFallback() {
        val json = """{"Address":"http://192.168.1.20:8096","Id":"abc123"}"""
        assertEquals("Jellyfin server", ServerDiscovery.parse(json, senderIp = "192.168.1.20")?.name)
    }

    @Test
    fun rejectsJunk() {
        assertNull(ServerDiscovery.parse("hello", senderIp = "192.168.1.20"))
        assertNull(ServerDiscovery.parse("""{"Name":"No ID"}""", senderIp = "192.168.1.20"))
    }

    // Sweep

    @Test
    fun sweepCoversAHomeNetworkExceptThisDevice() {
        val targets = ServerDiscovery.sweepTargets(ipv4("192.168.1.20"), ipv4("255.255.255.0"))
        assertEquals(253, targets.size) // .1–.254, minus this device
        assertEquals(ipv4("192.168.1.1"), targets.first())
        assertEquals(ipv4("192.168.1.254"), targets.last())
        assertFalse(ipv4("192.168.1.20") in targets)
    }

    @Test
    fun sweepCoversASlash22() {
        val targets = ServerDiscovery.sweepTargets(ipv4("192.168.4.81"), ipv4("255.255.252.0"))
        assertEquals(1021, targets.size)
        assertTrue(ipv4("192.168.5.50") in targets)
        assertEquals(ipv4("192.168.7.254"), targets.last())
    }

    @Test
    fun bigNetworksAreCutToTheSlash22AroundThisDevice() {
        val targets = ServerDiscovery.sweepTargets(ipv4("10.0.9.5"), ipv4("255.0.0.0"))
        assertEquals(1021, targets.size)
        assertEquals(ipv4("10.0.8.1"), targets.first())
        assertEquals(ipv4("10.0.11.254"), targets.last())
    }

    @Test
    fun tinyNetworksHaveNothingToSweep() {
        assertEquals(emptyList(), ServerDiscovery.sweepTargets(ipv4("192.168.1.20"), ipv4("255.255.255.255")))
        assertEquals(emptyList(), ServerDiscovery.sweepTargets(ipv4("192.168.1.20"), ipv4("255.255.255.254")))
    }

    @Test
    fun onlyPrivateRangesAreSwept() {
        assertTrue(ServerDiscovery.isPrivate(ipv4("10.1.2.3")))
        assertTrue(ServerDiscovery.isPrivate(ipv4("172.16.0.1")))
        assertTrue(ServerDiscovery.isPrivate(ipv4("172.31.255.1")))
        assertTrue(ServerDiscovery.isPrivate(ipv4("192.168.4.81")))
        assertFalse(ServerDiscovery.isPrivate(ipv4("172.32.0.1")))
        assertFalse(ServerDiscovery.isPrivate(ipv4("8.8.8.8")))
        assertFalse(ServerDiscovery.isPrivate(ipv4("169.254.1.1")))
        assertFalse(ServerDiscovery.isPrivate(ipv4("100.64.0.10"))) // Tailscale
    }

    @Test
    fun ipv4RoundTrips() = assertEquals("192.168.1.50", ServerDiscovery.ipv4String(ipv4("192.168.1.50")))

    @Test
    fun duplicateRepliesAreMergedAndSorted() {
        val a = DiscoveredServer("1", "Zebra", "http://192.168.1.2:8096")
        val b = DiscoveredServer("2", "attic", "http://192.168.1.3:8096")
        assertEquals(listOf(b, a), ServerDiscovery.merge(listOf(a, b, a.copy())))
    }
}
