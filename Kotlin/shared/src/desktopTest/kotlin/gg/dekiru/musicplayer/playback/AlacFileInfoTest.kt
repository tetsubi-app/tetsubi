package gg.dekiru.musicplayer.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [AlacFileInfo] on real tiny ALAC `.m4a` files (made with ffmpeg: 50 ms of a sine). */
class AlacFileInfoTest {
    private fun bytes(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/alac/$name")) { "missing $name" }.use { it.readBytes() }

    private fun read(data: ByteArray): AlacFileInfo? = AlacFileInfo.read(data.size.toLong()) { offset, length ->
        if (offset + length > data.size) null else data.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    @Test
    fun readsRateDepthAndChannelsFromTheAlacCookie() {
        assertEquals(AlacFileInfo(44_100, 16, 2), read(bytes("tiny_16_st_44k.m4a")))
        assertEquals(AlacFileInfo(48_000, 24, 2), read(bytes("tiny_24_st_48k.m4a")))
    }

    @Test
    fun failsSoftOnDamagedOrForeignFiles() {
        val good = bytes("tiny_16_st_44k.m4a")
        assertNull(read(ByteArray(0)))
        assertNull(read("not an mp4 file at all, just text".encodeToByteArray()))
        // Cut off inside the moov box, and with garbage over the header.
        assertNull(read(good.copyOf(good.size / 2)))
        assertNull(read(good.copyOf().also { for (i in 0 until 64) it[i] = 0x7f }))
        // Only the ftyp box.
        assertNull(read(good.copyOf(32)))
    }
}
