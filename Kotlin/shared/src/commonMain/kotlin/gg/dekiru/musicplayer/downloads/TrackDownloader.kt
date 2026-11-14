package gg.dekiru.musicplayer.downloads

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A track download that went wrong. `message` is shown to the user. */
class DownloadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Downloads one URL to a file. Writes `<file>.part` and renames it when
 * done. A `.part` left by a pause or a closed app is continued with
 * `Range: bytes=<size>-` when the server answers 206; a 200 starts it again.
 * Cancel the coroutine to stop (the `.part` stays, for resuming).
 */
class TrackDownloader(
    private val http: HttpClient,
    private val authorization: () -> String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    /** `onProgress(bytes so far, total bytes or null)`. */
    suspend fun download(url: String, destination: String, onProgress: (Long, Long?) -> Unit = { _, _ -> }) =
        withContext(ioDispatcher) {
            val part = destination + DownloadPaths.PART
            if (!fetch(url, part, onProgress)) {
                // 416: the saved part doesn't fit the file any more; start again.
                LocalFiles.deleteRecursively(part)
                fetch(url, part, onProgress)
            }
            LocalFiles.move(part, destination)
        }

    /** False when the server refused the range (416). */
    private suspend fun fetch(url: String, part: String, onProgress: (Long, Long?) -> Unit): Boolean {
        val existing = LocalFiles.size(part)
        return http.prepareGet(url) {
            header(HttpHeaders.Authorization, authorization())
            if (existing > 0) header(HttpHeaders.Range, "bytes=$existing-")
            // Long files: no overall limit, only an idle one (as iOS, 300 s).
            timeout {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = 300_000
            }
        }.execute { response ->
            val status = response.status
            if (status == HttpStatusCode.RequestedRangeNotSatisfiable && existing > 0) return@execute false
            if (!status.isSuccess()) throw DownloadException("HTTP ${status.value}")
            val resuming = existing > 0 && status == HttpStatusCode.PartialContent
            var written = if (resuming) existing else 0L
            val total = response.contentLength()?.let { it + written }
            val channel = response.bodyAsChannel()
            LocalFiles.sink(part, append = resuming).use { sink ->
                val buffer = ByteArray(64 * 1024)
                var reported = 0L
                onProgress(written, total)
                while (true) {
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n < 0) break
                    if (n == 0) continue
                    sink.write(buffer, 0, n)
                    written += n
                    // Report every 256 KB, not every chunk.
                    if (written - reported >= 256 * 1024) {
                        reported = written
                        onProgress(written, total)
                    }
                }
                sink.flush()
            }
            if (total != null && written < total) throw DownloadException("The download ended early")
            onProgress(written, total)
            true
        }
    }
}
