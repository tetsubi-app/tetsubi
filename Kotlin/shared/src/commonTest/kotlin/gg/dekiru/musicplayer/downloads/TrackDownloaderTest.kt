package gg.dekiru.musicplayer.downloads

import gg.dekiru.musicplayer.data.api.FakeServer
import gg.dekiru.musicplayer.data.api.authorization
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackDownloaderTest {

    private val content = ByteArray(300_000) { (it % 251).toByte() }

    /** A server that honours `Range` (206) or ignores it (200). */
    private fun server(ranges: Boolean, status: HttpStatusCode? = null) = FakeServer { request ->
        if (status != null) return@FakeServer respond("nope", status)
        val range = request.headers[HttpHeaders.Range]
        if (ranges && range != null) {
            val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
            if (from >= content.size) return@FakeServer respond("", HttpStatusCode.RequestedRangeNotSatisfiable)
            val rest = content.copyOfRange(from, content.size)
            respond(rest, HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentLength, "${rest.size}"))
        } else {
            respond(content, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "${content.size}"))
        }
    }

    private fun downloader(server: FakeServer) = TrackDownloader(server.client, { "MediaBrowser Token=\"tok\"" })

    @Test
    fun downloadsToThePartFileThenRenames() = runTest {
        val dir = tempDir()
        val server = server(ranges = true)
        var last = 0L to (null as Long?)
        downloader(server).download("http://jellyfin-box:8096/Audio/t1/stream.flac?static=true", "$dir/01 - A.flac") { b, t -> last = b to t }
        assertContentEquals(content, LocalFiles.readBytes("$dir/01 - A.flac"))
        assertFalse(LocalFiles.exists("$dir/01 - A.flac.part"))
        assertEquals(content.size.toLong() to content.size.toLong(), last)
        val request = server.requests.single()
        assertNull(request.headers[HttpHeaders.Range])
        assertEquals("MediaBrowser Token=\"tok\"", request.authorization)
        assertFalse("ApiKey" in request.url.toString())
    }

    @Test
    fun resumesAPartFileWithRangeWhenTheServerAnswers206() = runTest {
        val dir = tempDir()
        LocalFiles.writeBytes("$dir/01 - A.flac.part", content.copyOfRange(0, 100_000))
        val server = server(ranges = true)
        var first = -1L
        downloader(server).download("http://x/a", "$dir/01 - A.flac") { b, _ -> if (first < 0) first = b }
        assertEquals("bytes=100000-", server.requests.single().headers[HttpHeaders.Range])
        assertEquals(100_000L, first)
        assertContentEquals(content, LocalFiles.readBytes("$dir/01 - A.flac"))
    }

    @Test
    fun startsAgainWhenTheServerAnswers200() = runTest {
        val dir = tempDir()
        LocalFiles.writeBytes("$dir/01 - A.flac.part", ByteArray(100_000) { 9 })
        val server = server(ranges = false)
        downloader(server).download("http://x/a", "$dir/01 - A.flac")
        assertEquals("bytes=100000-", server.requests.single().headers[HttpHeaders.Range])
        assertContentEquals(content, LocalFiles.readBytes("$dir/01 - A.flac"))
    }

    @Test
    fun startsAgainWhenTheRangeIsRefused() = runTest {
        val dir = tempDir()
        LocalFiles.writeBytes("$dir/01 - A.flac.part", ByteArray(content.size + 10) { 9 })
        val server = server(ranges = true)
        downloader(server).download("http://x/a", "$dir/01 - A.flac")
        assertEquals(2, server.requests.size)
        assertNull(server.requests[1].headers[HttpHeaders.Range])
        assertContentEquals(content, LocalFiles.readBytes("$dir/01 - A.flac"))
    }

    @Test
    fun serverErrorsFailAndLeaveNoFile() = runTest {
        val dir = tempDir()
        val e = assertFailsWith<DownloadException> {
            downloader(server(ranges = true, status = HttpStatusCode.InternalServerError)).download("http://x/a", "$dir/01 - A.flac")
        }
        assertEquals("HTTP 500", e.message)
        assertFalse(LocalFiles.exists("$dir/01 - A.flac"))
        assertTrue(LocalFiles.list(dir).isEmpty())
    }
}
