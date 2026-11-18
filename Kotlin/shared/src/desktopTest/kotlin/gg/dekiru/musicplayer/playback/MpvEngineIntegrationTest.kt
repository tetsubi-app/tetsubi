package gg.dekiru.musicplayer.playback

import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * MpvEngine over the real libmpv, with mpv's null audio output (`ao=null`):
 * no sound reaches the speakers. Skipped where libmpv isn't installed.
 */
class MpvEngineIntegrationTest {
    private val main = Executors.newSingleThreadExecutor { r -> Thread(r, "test-main").apply { isDaemon = true } }
    private val events = LinkedBlockingQueue<String>()
    private var engine: MpvEngine? = null
    private val dir: File = Files.createTempDirectory("tetsubi-mpv").toFile()

    private fun <T> onMain(block: () -> T): T = main.submit<T> { block() }.get(10, TimeUnit.SECONDS)

    private fun start(): MpvEngine {
        assumeTrue("libmpv isn't installed", MpvLibrary.load().isSuccess)
        val e = MpvEngine(options = mapOf("ao" to "null"), post = { main.execute(it) })
        engine = e
        onMain {
            e.listener = object : EngineListener {
                override fun onIndexChanged(index: Int, automatic: Boolean) { events += "index $index ${if (automatic) "auto" else "skip"}" }
                override fun onIsPlayingChanged(isPlaying: Boolean) { events += "playing $isPlaying" }
                override fun onEnded() { events += "ended" }
                override fun onError(message: String) { events += "error $message" }
            }
        }
        return e
    }

    @AfterTest
    fun tearDown() {
        engine?.close()
        main.shutdownNow()
        dir.deleteRecursively()
    }

    /** Waits for `event`, skipping others; fails after `seconds`. */
    private fun await(event: String, seconds: Long = 10) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (true) {
            val left = deadline - System.nanoTime()
            val next = if (left > 0) events.poll(left, TimeUnit.NANOSECONDS) else null
            if (next == null) fail("No \"$event\" within $seconds s")
            if (next.startsWith("error")) fail(next)
            if (next == event) return
        }
    }

    /** A short 16-bit stereo sine-wave WAV. */
    private fun wav(name: String, seconds: Double, hz: Double): EngineItem {
        val rate = 44_100
        val frames = (rate * seconds).toInt()
        val data = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { i ->
            val s = (sin(2 * PI * hz * i / rate) * 3000).toInt().toShort()
            data.putShort(s).putShort(s)
        }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + frames * 4); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(2); putInt(rate); putInt(rate * 4); putShort(4); putShort(16)
            put("data".toByteArray()); putInt(frames * 4)
        }
        val file = File(dir, "$name.wav")
        file.writeBytes(header.array() + data.array())
        return EngineItem(name, "file://${file.absolutePath}", name, "Test", "Test", "", (seconds * 1000).toLong())
    }

    @Test
    fun withoutLibmpvNothingPlaysAndHomeSaysWhy() {
        assumeTrue("libmpv is installed", MpvLibrary.load().isFailure)
        val e = MpvEngine(options = mapOf("ao" to "null"), post = { main.execute(it) })
        engine = e
        assertFalse(e.isSupported)
        assertEquals(MpvEngine.MISSING_LIBRARY_MESSAGE, e.unavailableReason)
        // Every call is a no-op, not a crash.
        e.setAuthorization("x")
        e.setQueue(listOf(wav("one", 0.1, 440.0)), 0)
        e.play()
        e.seekTo(1_000)
        e.skipTo(0)
        e.setVolume(0.5f)
        e.stop()
        assertEquals(0, e.positionMillis)
        assertEquals(0, e.durationMillis)
    }

    @Test
    fun playsAQueueThroughToTheEnd() {
        val e = start()
        val items = listOf(wav("one", 1.5, 440.0), wav("two", 1.5, 660.0))
        onMain {
            e.setQueue(items, 0)
            e.play()
        }
        await("playing true")
        await("index 1 auto")
        await("ended")
    }

    @Test
    fun seeksPausesAndKeepsTheTrackWhenTheRestIsReplaced() {
        val e = start()
        val items = listOf(wav("long", 6.0, 440.0), wav("next", 1.0, 550.0))
        onMain {
            e.setQueue(items, 0)
            e.play()
        }
        await("playing true")

        onMain { e.seekTo(3_000) }
        Thread.sleep(500)
        val afterSeek = onMain { e.positionMillis }
        assertTrue(afterSeek in 2_800..4_500, "position after seeking to 3 s: $afterSeek")

        onMain { e.pause() }
        await("playing false")
        onMain { e.play() }
        await("playing true")

        events.clear()
        val before = onMain { e.positionMillis }
        onMain { e.replaceAfterCurrent(listOf(wav("other", 1.0, 770.0))) }
        Thread.sleep(500)
        val after = onMain { e.positionMillis }
        assertTrue(after >= before, "the playing track restarted: $before then $after")
        assertNull(events.firstOrNull { it.startsWith("index") }, "a change of track: $events")

        // The replaced entry follows, then the queue ends.
        await("index 1 auto")
        await("ended")
    }
}
