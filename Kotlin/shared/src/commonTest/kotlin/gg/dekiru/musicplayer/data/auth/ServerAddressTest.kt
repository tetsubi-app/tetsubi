package gg.dekiru.musicplayer.data.auth

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Turning a typed server address into one that works. Ported from ServerAddressTests.swift. */
class ServerAddressTest {

    @Test
    fun bareIpTriesJellyfinPortFirst() = assertEquals(
        listOf("http://192.168.1.20:8096", "https://192.168.1.20", "http://192.168.1.20", "https://192.168.1.20:8920"),
        ServerAddress.candidates("192.168.1.20"),
    )

    @Test
    fun bareHostWithPort() =
        assertEquals(listOf("http://jellyfin-box:8096", "https://jellyfin-box:8096"), ServerAddress.candidates("jellyfin-box:8096"))

    @Test
    fun httpWithoutPortTriesAsTypedThen8096() = assertEquals(
        listOf("http://192.168.1.20", "http://192.168.1.20:8096"),
        ServerAddress.candidates("http://192.168.1.20"),
    )

    @Test
    fun httpsWithoutPortTriesAsTypedThen8920() = assertEquals(
        listOf("https://music.example.com", "https://music.example.com:8920"),
        ServerAddress.candidates("https://music.example.com"),
    )

    @Test
    fun fullAddressIsUsedAsIs() =
        assertEquals(listOf("http://192.168.1.20:8096"), ServerAddress.candidates("http://192.168.1.20:8096"))

    @Test
    fun trailingSlashSpacesAndCaseAreTidied() =
        assertEquals(listOf("http://192.168.1.20:8096"), ServerAddress.candidates("  HTTP://192.168.1.20:8096/  "))

    @Test
    fun pathIsKept() = assertEquals(
        listOf("https://example.com/jellyfin", "https://example.com:8920/jellyfin"),
        ServerAddress.candidates("https://example.com/jellyfin"),
    )

    @Test
    fun nonsenseGivesNothing() {
        assertEquals(emptyList(), ServerAddress.candidates(""))
        assertEquals(emptyList(), ServerAddress.candidates("   "))
        assertEquals(emptyList(), ServerAddress.candidates("my server"))
        assertEquals(emptyList(), ServerAddress.candidates("ftp://192.168.1.20"))
    }

    // Resolving

    private val jellyfinInfo = """{"Id":"abc","Version":"12.4.0","ProductName":"Jellyfin Server","ServerName":"Jellyfin-Box"}"""

    @Test
    fun resolvePicksTheFirstAddressThatIsJellyfin() = runTest {
        val result = ServerAddress.resolve("192.168.1.20") { url ->
            // Only the plain-http, port-80 address answers.
            if (url == "http://192.168.1.20/System/Info/Public") jellyfinInfo else null
        }
        assertEquals(ServerAddress.Result.Found("http://192.168.1.20"), result)
    }

    @Test
    fun resolveSkipsAWebPageThatIsntJellyfin() = runTest {
        // Port 80 is a router's web page; Jellyfin is on 8096.
        val result = ServerAddress.resolve("http://192.168.1.20") { url ->
            if (":8096/" in url) jellyfinInfo else "<html>Router</html>"
        }
        assertEquals(ServerAddress.Result.Found("http://192.168.1.20:8096"), result)
    }

    @Test
    fun resolveReportsNotFound() = runTest {
        assertEquals(ServerAddress.Result.NotFound("192.168.1.20"), ServerAddress.resolve(" 192.168.1.20 ") { null })
    }

    @Test
    fun resolveReportsInvalid() = runTest {
        assertEquals(ServerAddress.Result.Invalid, ServerAddress.resolve("my server") { jellyfinInfo })
    }

    @Test
    fun otherProductsAreNotJellyfin() {
        assertFalse(ServerAddress.isJellyfinInfo("""{"Id":"x","Version":"4.9","ProductName":"Emby Server"}"""))
        assertFalse(ServerAddress.isJellyfinInfo("{}"))
        assertFalse(ServerAddress.isJellyfinInfo("not json"))
        assertTrue(ServerAddress.isJellyfinInfo(jellyfinInfo))
        assertTrue(ServerAddress.isJellyfinInfo("""{"Id":"x","Version":"10.11"}"""))
    }
}
