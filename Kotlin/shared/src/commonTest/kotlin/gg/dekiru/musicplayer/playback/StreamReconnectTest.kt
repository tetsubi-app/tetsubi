package gg.dekiru.musicplayer.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What Android does when a converted stream's connection drops (see [StreamReconnect]). */
class StreamReconnectTest {

    private val converted = EngineItem(
        "t", "http://server/Audio/t/universal?UserId=u&Container=flac", "", "", "", "", 240_000,
        castUrl = "http://server/Audio/t/stream.flac?static=true&ApiKey=k",
    )
    private val direct = EngineItem("t", "http://server/Audio/t/stream.flac?static=true", "", "", "", "", 240_000)
    private val local = EngineItem("t", "file:///data/Downloads/t.flac", "", "", "", "", 240_000)

    @Test
    fun onlyAConvertedStreamThatLostItsConnectionIsReconnected() {
        for (code in listOf(2000, 2001, 2002)) assertTrue(StreamReconnect.shouldReconnect(code, converted, casting = false), "$code")
        // A refused request (bad HTTP status), a missing file, a broken file: reported at once.
        for (code in listOf(2004, 2005, 3001, 4001)) assertFalse(StreamReconnect.shouldReconnect(code, converted, casting = false), "$code")
        // Direct streams and downloads: ExoPlayer picks them up where they stopped.
        assertFalse(StreamReconnect.shouldReconnect(2001, direct, casting = false))
        assertFalse(StreamReconnect.shouldReconnect(2001, local, casting = false))
        assertFalse(StreamReconnect.shouldReconnect(2001, null, casting = false))
        // The Cast device has its own connection.
        assertFalse(StreamReconnect.shouldReconnect(2001, converted, casting = true))
    }

    @Test
    fun resumesWhereTheStreamGotTo() {
        // Its own stream, 1:15 in.
        assertEquals(75_000, StreamReconnect.resumePosition(0, 75_000, 240_000))
        // A stream already restarted at 1:00 (a seek or an earlier reconnect), 15 s in.
        assertEquals(75_000, StreamReconnect.resumePosition(60_000, 15_000, 240_000))
        // A try that failed before playing anything: the same point.
        assertEquals(60_000, StreamReconnect.resumePosition(60_000, 0, 240_000))
        // Never past a second before the end (as a seek), nor before the start.
        assertEquals(239_000, StreamReconnect.resumePosition(200_000, 45_000, 240_000))
        assertEquals(0, StreamReconnect.resumePosition(0, -5, 240_000))
        // Length unknown: as reached.
        assertEquals(90_000, StreamReconnect.resumePosition(30_000, 60_000, 0))
    }

    @Test
    fun triesAtOnceThenBacksOffAndGivesUpAfterTwoMinutes() {
        assertEquals(0, StreamReconnect.waitBefore(0, 0))
        assertEquals(1_000, StreamReconnect.waitBefore(1, 0))
        assertEquals(2_000, StreamReconnect.waitBefore(2, 1_000))
        assertEquals(4_000, StreamReconnect.waitBefore(3, 3_000))
        assertEquals(10_000, StreamReconnect.waitBefore(4, 7_000))
        assertEquals(10_000, StreamReconnect.waitBefore(20, 110_000))
        assertNull(StreamReconnect.waitBefore(20, 110_001))
        assertNull(StreamReconnect.waitBefore(0, 120_001))
    }

    @Test
    fun aRunOfTriesFollowsTheClockAndEnds() {
        var now = 1_000_000L
        val state = ReconnectState { now }
        assertFalse(state.active)

        // Each try fails straight away, after its wait.
        val waits = mutableListOf<Long>()
        while (true) {
            val wait = state.failed() ?: break
            waits += wait
            now += wait
        }
        assertEquals(listOf(0L, 1_000, 2_000, 4_000), waits.take(4))
        assertTrue(waits.drop(4).all { it == 10_000L })
        assertTrue(waits.sum() <= StreamReconnect.GIVE_UP_AFTER_MILLIS)
        assertTrue(waits.sum() > StreamReconnect.GIVE_UP_AFTER_MILLIS - 10_000)
        // Given up: the run is over, and the next failure starts a new one at once.
        assertFalse(state.active)
        assertEquals(0, state.failed())
        assertTrue(state.active)
    }

    @Test
    fun playingAgainEndsTheRun() {
        var now = 0L
        val state = ReconnectState { now }
        assertEquals(0, state.failed())
        assertEquals(1_000, state.failed())
        now += 1_000
        state.reset()
        assertFalse(state.active)
        // A later drop starts again from an immediate try, with the full two minutes.
        now += 600_000
        assertEquals(0, state.failed())
        assertEquals(1_000, state.failed())
    }
}
