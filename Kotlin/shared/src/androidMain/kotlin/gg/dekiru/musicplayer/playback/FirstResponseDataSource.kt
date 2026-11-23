package gg.dekiru.musicplayer.playback

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * The player's HTTP streams, with a limit on a converted stream's first
 * response ([StreamTimeouts]).
 *
 * The HTTP source (`OkHttpDataSource`, [StreamHttpClients]) has one read
 * timeout for everything: the wait for the response and every read after
 * it, so a shorter one would also cut off a stream that pauses for a while
 * mid-track. Instead, a converted
 * stream is opened (connect, request, response headers) on a helper thread
 * while the loading thread waits at most [StreamTimeouts.CONVERTED_FIRST_RESPONSE_MILLIS];
 * past that it fails with a connection timeout (`PlaybackException` 2002),
 * which `Media3Engine` reconnects from the same point. The abandoned
 * request is closed on the helper thread once it ends by itself (its own
 * timeouts). Reads after a successful open run on the loading thread as
 * usual, with the normal 30 s read timeout. Everything else (direct
 * streams) is opened as before.
 *
 * Transfer events (the stream-start log's `response`) are this source's
 * own, so an abandoned request never reports a response.
 */
@UnstableApi
internal class FirstResponseDataSource private constructor(
    private val direct: DataSource.Factory,
    private val converted: DataSource.Factory,
) : BaseDataSource(/* isNetwork = */ true) {

    /** `direct` for direct streams; `converted` (a shorter connect wait) for converted ones. */
    class Factory(private val direct: DataSource.Factory, private val converted: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = FirstResponseDataSource(direct, converted)
    }

    private var source: DataSource? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val limit = StreamTimeouts.firstResponseLimitMillis(dataSpec.uri.toString())
        val s = (if (limit == null) direct else converted).createDataSource()
        source = s
        val length = if (limit == null) s.open(dataSpec) else openWithin(s, dataSpec, limit)
        opened = true
        transferStarted(dataSpec)
        return length
    }

    private fun openWithin(s: DataSource, dataSpec: DataSpec, limitMillis: Long): Long {
        val task = FutureTask { s.open(dataSpec) }
        opener.execute(task)
        try {
            return task.get(limitMillis, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            abandon(s, task)
            throw HttpDataSource.HttpDataSourceException(
                "No response within $limitMillis ms",
                SocketTimeoutException("No response within $limitMillis ms"),
                dataSpec,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                HttpDataSource.HttpDataSourceException.TYPE_OPEN,
            )
        } catch (e: InterruptedException) {
            // The load was cancelled (a skip, a new queue).
            abandon(s, task)
            Thread.currentThread().interrupt()
            throw InterruptedIOException()
        } catch (e: ExecutionException) {
            // It failed by itself; close() closes it as usual.
            throw e.cause as? IOException ?: IOException(e.cause)
        }
    }

    /** Lets a request still opening go: it's closed on the helper thread once its open ends. */
    private fun abandon(s: DataSource, task: FutureTask<Long>) {
        source = null
        opener.execute {
            runCatching { task.get() }
            runCatching { s.close() }
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val s = source ?: throw IOException("Not open")
        val read = s.read(buffer, offset, length)
        if (read > 0) bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = source?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = source?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            source?.close()
        } finally {
            source = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    private companion object {
        /** Opens converted streams; idle threads go after a minute. */
        val opener: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "Tetsubi-stream-open").apply { isDaemon = true } }
    }
}
