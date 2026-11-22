package gg.dekiru.musicplayer.playback

import gg.dekiru.musicplayer.playback.StreamStartLog.Kind
import gg.dekiru.musicplayer.playback.StreamStartLog.Reason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Android track-start diagnostic line (see [StreamStartLog]). */
class StreamStartLogTest {

    private var now = 5_000_000L
    private val lines = mutableListOf<String>()
    private val log = StreamStartLog({ now }, { lines += it })

    private val one = "http://server/Audio/a/universal?UserId=u&Container=flac"
    private val two = "http://server/Audio/b/stream.flac?static=true"

    @Test
    fun aFreshStartLogsOneLineWithTheTimesFromTheAsk() {
        log.begin("a", Kind.CONVERTED, Reason.PLAY, one)
        now += 400
        log.responded(one)
        now += 300
        log.firstAudio(one)
        now += 100
        log.playing()
        now += 50
        log.soundStarted()
        assertEquals(listOf("stream start: item=a kind=converted reason=play response=400 firstAudio=700 sound=850"), lines)
        assertFalse(log.pending)
        // Later events of the same stream (a reopen mid-track, a resume after pause) log nothing.
        log.responded(one)
        log.soundStarted()
        assertEquals(1, lines.size)
        assertNull(log.millisUntilCheck())
    }

    @Test
    fun theResponseTimeCanComeFromTheLoadingThread() {
        log.begin("a", Kind.DIRECT, Reason.SKIP, two)
        val opened = now + 250
        now += 600 // Posted to the main thread late.
        log.responded(two, at = opened)
        log.soundStarted()
        assertEquals("stream start: item=a kind=direct reason=skip response=250 firstAudio=- sound=600", lines.single())
    }

    @Test
    fun otherItemsEventsDontCountButMarkThemBuffered() {
        log.begin("a", Kind.DIRECT, Reason.PLAY, one)
        now += 100
        log.responded(one)
        log.firstAudio(one)
        log.soundStarted()
        // The player loads the next track ahead while this one plays.
        now += 60_000
        log.responded(two)
        log.firstAudio(two)
        assertEquals(1, lines.size)
        // Gapless: it moves on while playing.
        now += 120_000
        log.begin("b", Kind.DIRECT, Reason.NEXT, two, alreadyPlaying = true)
        assertEquals("stream start: item=b kind=direct reason=next response=buffered firstAudio=buffered sound=0", lines.last())
    }

    @Test
    fun movingOnIntoATrackNotLoadedAheadWaitsForItsSound() {
        log.responded(two) // Connected, but no audio read yet.
        log.begin("b", Kind.DIRECT, Reason.NEXT, two, alreadyPlaying = true)
        assertTrue(lines.isEmpty())
        // The player stops to load it.
        now += 200
        log.stoppedPlaying()
        now += 1_300
        log.firstAudio(two)
        now += 200
        log.playing()
        now += 40
        log.soundStarted()
        assertEquals("stream start: item=b kind=direct reason=next response=buffered firstAudio=1500 sound=1740", lines.single())
    }

    @Test
    fun movingOnIntoATrackThatKeepsPlayingCountsAsSoundAtOnce() {
        log.begin("b", Kind.CONVERTED, Reason.NEXT, one, alreadyPlaying = true)
        now += 300
        log.firstAudio(one)
        now += 700
        assertNull(log.check(paused = false, state = "ready"))
        assertEquals("stream start: item=b kind=converted reason=next response=- firstAudio=300 sound=0", lines.single())
    }

    @Test
    fun aLoadAfterTheAskReplacesBuffered() {
        log.responded(two)
        log.begin("b", Kind.DIRECT, Reason.SKIP, two)
        now += 300
        log.responded(two)
        now += 200
        log.firstAudio(two)
        log.soundStarted()
        assertEquals("stream start: item=b kind=direct reason=skip response=300 firstAudio=500 sound=500", lines.single())
    }

    @Test
    fun aStallLogsWaitingLinesEveryTenSecondsUntilSound() {
        log.begin("a", Kind.CONVERTED, Reason.SEEK, one)
        assertEquals(10_000, log.millisUntilCheck())
        now += 4_000
        assertEquals(6_000, log.check(paused = false, state = "buffering"))
        assertTrue(lines.isEmpty())
        now += 6_000
        log.responded(one, at = now - 1_000)
        assertEquals(10_000, log.check(paused = false, state = "buffering"))
        assertEquals("stream waiting: item=a kind=converted reason=seek response=9000 firstAudio=- after=10000 state=buffering", lines.last())
        // A late check still keeps the cadence.
        now += 10_500
        assertEquals(9_500, log.check(paused = false, state = "buffering"))
        assertEquals("stream waiting: item=a kind=converted reason=seek response=9000 firstAudio=- after=20500 state=buffering", lines.last())
        now += 2_000
        log.firstAudio(one)
        now += 1_000
        log.soundStarted()
        assertEquals("stream start: item=a kind=converted reason=seek response=9000 firstAudio=22500 sound=23500", lines.last())
        assertEquals(3, lines.size)
        assertNull(log.check(paused = false, state = "ready"))
    }

    @Test
    fun noWaitingLinesWhilePausedAndReadyWhilePausedEndsTheRun() {
        log.begin("a", Kind.LOCAL, Reason.SKIP, "file:///x/a.flac")
        now += 10_000
        assertEquals(10_000, log.check(paused = true, state = "buffering"))
        assertTrue(lines.isEmpty())
        log.responded("file:///x/a.flac")
        log.readyWhilePaused()
        assertEquals("stream start: item=a kind=local reason=skip response=10000 firstAudio=- sound=paused", lines.single())
    }

    @Test
    fun playingWithoutWordFromTheOutputCountsAfterAMoment() {
        log.begin("a", Kind.DIRECT, Reason.RESUME, two)
        now += 700
        log.playing()
        assertEquals(1_000, log.millisUntilCheck())
        now += 1_000
        assertNull(log.check(paused = false, state = "ready"))
        assertEquals("stream start: item=a kind=direct reason=resume response=- firstAudio=- sound=700", lines.single())
    }

    @Test
    fun aFailureEndsTheRunAndANewAskReplacesAStalledOne() {
        log.begin("a", Kind.CONVERTED, Reason.PLAY, one)
        now += 3_000
        log.begin("a", Kind.CONVERTED, Reason.RECONNECT, one)
        now += 2_000
        log.failed("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED")
        assertEquals(
            "stream failed: item=a kind=converted reason=reconnect response=- firstAudio=- after=2000 error=ERROR_CODE_IO_NETWORK_CONNECTION_FAILED",
            lines.single(),
        )
        assertNull(log.check(paused = false, state = "idle"))
        // Stopped: nothing more, and nothing remembered.
        log.responded(two)
        log.cancel()
        log.begin("b", Kind.DIRECT, Reason.PLAY, two)
        log.soundStarted()
        assertEquals("stream start: item=b kind=direct reason=play response=- firstAudio=- sound=0", lines.last())
    }

    @Test
    fun reconnectTriesCountFromTheFirst() {
        log.begin("a", Kind.CONVERTED, Reason.RECONNECT, one)
        now += 4_000
        log.begin("a", Kind.CONVERTED, Reason.RECONNECT, one)
        now += 7_000
        log.check(paused = false, state = "idle")
        assertEquals("stream waiting: item=a kind=converted reason=reconnect response=- firstAudio=- after=11000 state=idle", lines.single())
        log.responded(one)
        now += 500
        log.soundStarted()
        assertEquals("stream start: item=a kind=converted reason=reconnect response=11000 firstAudio=- sound=11500", lines.last())
    }

    @Test
    fun noAddressesInTheLine() {
        log.begin("a", Kind.CONVERTED, Reason.PLAY, one)
        log.responded(one)
        log.soundStarted()
        assertFalse("http" in lines.single())
        assertFalse("UserId" in lines.single())
    }

    @Test
    fun kindsOfStream() {
        assertEquals(Kind.CONVERTED, StreamStartLog.kindOf(one))
        assertEquals(Kind.DIRECT, StreamStartLog.kindOf(two))
        assertEquals(Kind.LOCAL, StreamStartLog.kindOf("file:///data/Downloads/a.flac"))
        assertEquals(Kind.LOCAL, StreamStartLog.kindOf("content://media/a"))
    }
}
