package gg.dekiru.musicplayer.data.api

import kotlin.test.Test
import kotlin.test.assertEquals

/** The server header: format, and device names made plain text (HTTP 400 otherwise). Ported from HeaderSafeNameTests.swift. */
class AuthHeaderTest {

    private val identity = ClientIdentity(deviceName = "Xiaomi Pad Mini", deviceId = "dev-1", version = "0.1.0")

    @Test
    fun headerWithoutToken() {
        assertEquals(
            "MediaBrowser Client=\"Tetsubi\", Device=\"Xiaomi Pad Mini\", DeviceId=\"dev-1\", Version=\"0.1.0\"",
            mediaBrowserHeader(identity, token = null),
        )
    }

    @Test
    fun headerWithToken() {
        assertEquals(
            "MediaBrowser Client=\"Tetsubi\", Device=\"Xiaomi Pad Mini\", DeviceId=\"dev-1\", Version=\"0.1.0\", Token=\"abc\"",
            mediaBrowserHeader(identity, token = "abc"),
        )
    }

    @Test
    fun headerUsesSafeDeviceName() {
        val header = mediaBrowserHeader(identity.copy(deviceName = "Jake’s \"Pad\""), token = null)
        assertEquals(true, "Device=\"Jake's Pad\"" in header, header)
    }

    @Test
    fun curlyApostropheBecomesStraight() = assertEquals("Jake's Mac mini", headerSafe("Jake’s Mac mini"))

    @Test
    fun accentsAreDropped() = assertEquals("Zoe's iPhone", headerSafe("Zoë’s iPhone"))

    @Test
    fun quotesBackslashesAndEmojiAreRemoved() = assertEquals("My Studio Mac", headerSafe("My \"Studio\" Mac\\ 🎧"))

    @Test
    fun plainNamesAreUnchanged() {
        assertEquals("Pixel 9", headerSafe("Pixel 9"))
        assertEquals("Tetsubi", headerSafe("Tetsubi"))
    }

    @Test
    fun nothingLeftGetsAFallback() = assertEquals("Device", headerSafe("🎧🎧"))
}
