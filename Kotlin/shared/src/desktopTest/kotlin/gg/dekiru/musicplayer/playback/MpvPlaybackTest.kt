package gg.dekiru.musicplayer.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Records what the listener is told. */
private class Recorder : EngineListener {
    val calls = mutableListOf<String>()
    override fun onIndexChanged(index: Int, automatic: Boolean) { calls += "index $index ${if (automatic) "auto" else "skip"}" }
    override fun onIsPlayingChanged(isPlaying: Boolean) { calls += "playing $isPlaying" }
    override fun onEnded() { calls += "ended" }
    override fun onError(message: String) { calls += "error $message" }
    override fun onStartingChanged(starting: Boolean, reconnecting: Boolean) { calls += "starting $starting" }
}

private fun item(n: Int, url: String = "https://server/Audio/$n/stream.flac?static=true", durationMillis: Long = 200_000) =
    EngineItem("t$n", url, "Track $n", "Artist", "Album", "", durationMillis)

private fun converted(n: Int) = item(n, "https://server/Audio/$n/universal?UserId=u&Container=flac")

/** The queue logic over libmpv, driven by made-up mpv events. */
class MpvPlaybackTest {
    private val sent = mutableListOf<List<String>>()
    private val recorder = Recorder()
    private val playback = MpvPlayback(send = { sent += it }, listener = { recorder })

    private fun queue(count: Int, start: Int = 0, make: (Int) -> EngineItem = { item(it) }) {
        playback.setQueue((0 until count).map(make), start)
        playback.play()
        playback.handle(MpvEvent.StartFile(start))
        sent.clear()
        recorder.calls.clear()
    }

    @Test
    fun aCuedQueueOpensNothingUntilPlayThenSeeksToTheHeldPoint() {
        val items = (0 until 3).map { item(it) }
        playback.cue(items, 1, 42_000)
        assertTrue(sent.none { it.first() == "playlist-play-index" || it.first() == "seek" }, "nothing opened: $sent")
        assertEquals(listOf("loadfile", mpvTarget(items[2].url), "append"), sent.filter { it.first() == "loadfile" }.last())
        assertEquals(1, playback.current)
        assertTrue(playback.unloaded)
        assertEquals(42_000L, playback.heldStartMillis)
        sent.clear()
        playback.play()
        assertEquals(
            listOf(listOf("set", "pause", "no"), listOf("playlist-play-index", "1"), listOf("seek", "42.0", "absolute+exact")),
            sent,
        )
        assertEquals(0L, playback.heldStartMillis)
        assertTrue(recorder.calls.none { it.startsWith("index") }, "a cued start isn't a change of track")
    }

    @Test
    fun aCuedConvertedStreamStartsAtThePointByItsUrl() {
        playback.cue(listOf(converted(0), converted(1)), 0, 49_000)
        assertTrue(sent.none { it.first() == "playlist-play-index" })
        assertEquals(49_000L, playback.currentOffsetMillis)
        assertTrue(sent.any { it.first() == "loadfile" && it[1].endsWith("&StartTimeTicks=490000000") })
        sent.clear()
        playback.play()
        assertEquals(listOf(listOf("set", "pause", "no"), listOf("playlist-play-index", "0")), sent)
    }

    @Test
    fun startingWhileMpvLoadsWithPlayWanted() {
        queue(2)
        playback.handle(MpvEvent.Paused(false))
        playback.handle(MpvEvent.Idle(false))
        playback.handle(MpvEvent.CoreIdle(true))
        assertTrue("starting true" in recorder.calls)
        playback.handle(MpvEvent.CoreIdle(false))
        assertEquals("starting false", recorder.calls.last())
        recorder.calls.clear()
        // Paused: core-idle too, but not starting.
        playback.handle(MpvEvent.Paused(true))
        playback.handle(MpvEvent.CoreIdle(true))
        assertTrue(recorder.calls.none { it.startsWith("starting") })
    }

    @Test
    fun endReasonsComeFromMpvCodes() {
        assertEquals(MpvEndReason.EOF, MpvEndReason.of(0))
        assertEquals(MpvEndReason.STOP, MpvEndReason.of(2))
        assertEquals(MpvEndReason.QUIT, MpvEndReason.of(3))
        assertEquals(MpvEndReason.ERROR, MpvEndReason.of(4))
        assertEquals(MpvEndReason.REDIRECT, MpvEndReason.of(5))
        assertEquals(MpvEndReason.UNKNOWN, MpvEndReason.of(1))
        assertEquals(MpvEndReason.UNKNOWN, MpvEndReason.of(99))
    }

    @Test
    fun repeatModesMapToMpvLoops() {
        assertEquals("no" to "no", mpvLoopOptions(RepeatMode.OFF))
        assertEquals("no" to "inf", mpvLoopOptions(RepeatMode.ALL))
        assertEquals("inf" to "no", mpvLoopOptions(RepeatMode.ONE))
        playback.setRepeatMode(RepeatMode.ONE)
        assertEquals(listOf(listOf("set", "loop-file", "inf"), listOf("set", "loop-playlist", "no")), sent)
    }

    @Test
    fun everyFormatPlaysAsItIs() {
        // The server then sends originals, which join gaplessly; converted tracks carry padding.
        assertEquals(AudioFormat.entries.toSet(), MpvEngine.DIRECT_PLAY_FORMATS)
        assertTrue(AudioFormat.ALAC in MpvEngine.DIRECT_PLAY_FORMATS)
    }

    @Test
    fun libraryNamesIncludeRuntimeOnlyPackages() {
        assertEquals(listOf("mpv", "libmpv.so.2", "libmpv.so.1"), MpvLibrary.candidateNames("Linux"))
        assertEquals("mpv", MpvLibrary.candidateNames("Mac OS X").first())
    }

    @Test
    fun optionsAreGaplessAndAudioOnly() {
        val options = MpvEngine.baseOptions()
        assertEquals("yes", options["gapless-audio"])
        assertEquals("yes", options["prefetch-playlist"])
        assertEquals("yes", options["idle"])
        assertEquals("no", options["keep-open"])
        assertEquals("null", options["vo"])
        assertEquals("no", options["video"])
        assertEquals("no", options["config"])
        assertEquals("Tetsubi", options["audio-client-name"])
    }

    @Test
    fun filesArePathsAndStreamsAreUrls() {
        assertEquals("/home/me/Music/A B/01 x.flac", mpvTarget("file:///home/me/Music/A B/01 x.flac"))
        assertEquals("https://s/Audio/1/stream.flac?static=true", mpvTarget("https://s/Audio/1/stream.flac?static=true"))
    }

    @Test
    fun volumeIsAPercentage() {
        assertEquals("100.0", mpvVolume(1f))
        assertEquals("50.0", mpvVolume(0.5f))
        assertEquals("0.0", mpvVolume(-1f))
    }

    @Test
    fun authorizationIsOneHeaderWithItsCommas() {
        val header = "MediaBrowser Client=\"Tetsubi\", Device=\"x\", Token=\"t\""
        assertEquals(
            listOf(
                listOf("change-list", "http-header-fields", "clr", ""),
                listOf("change-list", "http-header-fields", "append", "Authorization: $header"),
            ),
            authorizationCommands(header),
        )
        assertEquals(listOf(listOf("change-list", "http-header-fields", "clr", "")), authorizationCommands(null))
    }

    @Test
    fun replacingAfterCurrentRemovesFromTheEndThenAppends() {
        assertEquals(
            listOf(
                listOf("playlist-remove", "4"),
                listOf("playlist-remove", "3"),
                listOf("playlist-remove", "2"),
                listOf("loadfile", "u1", "append"),
                listOf("loadfile", "u2", "append"),
            ),
            replaceAfterCommands(size = 5, current = 1, urls = listOf("u1", "u2")),
        )
        // Playing the last: nothing to remove.
        assertEquals(listOf(listOf("loadfile", "u", "append")), replaceAfterCommands(size = 3, current = 2, urls = listOf("u")))
    }

    @Test
    fun replacingAnEntryNeverRemovesThePlayingOne() {
        assertEquals(
            listOf(
                listOf("loadfile", "new", "append"),
                listOf("playlist-move", "4", "1"),
                listOf("playlist-play-index", "1"),
                listOf("playlist-remove", "2"),
            ),
            replaceEntryCommands(size = 4, index = 1, url = "new", play = true),
        )
    }

    @Test
    fun aNewQueueFillsMpvsPlaylistAndIsntReportedAsAChange() {
        playback.setQueue(listOf(item(0), item(1), item(2)), 1)
        assertEquals(listOf("stop"), sent.first())
        assertEquals(3, sent.count { it.firstOrNull() == "loadfile" && it.last() == "append" })
        assertEquals(listOf("playlist-play-index", "1"), sent.last())
        playback.handle(MpvEvent.StartFile(1))
        assertEquals(emptyList(), recorder.calls)
        assertEquals(1, playback.current)
        assertNull(playback.pending)
    }

    @Test
    fun mpvMovingOnIsAnAutomaticChange() {
        queue(3)
        playback.handle(MpvEvent.EndFile(MpvEndReason.EOF))
        playback.handle(MpvEvent.StartFile(1))
        assertEquals(listOf("index 1 auto"), recorder.calls)
        assertEquals(1, playback.current)
    }

    @Test
    fun aSkipIsReportedOnceAndStaleStartsAreIgnored() {
        queue(5)
        playback.skipTo(2)
        assertEquals(listOf(listOf("playlist-play-index", "2")), sent)
        playback.skipTo(3)
        assertEquals(listOf("index 2 skip", "index 3 skip"), recorder.calls)
        // mpv's starts arrive late: the one for the first skip is stale.
        playback.handle(MpvEvent.EndFile(MpvEndReason.STOP))
        playback.handle(MpvEvent.StartFile(2))
        playback.handle(MpvEvent.StartFile(3))
        assertEquals(listOf("index 2 skip", "index 3 skip"), recorder.calls)
        assertEquals(3, playback.current)
        // Then mpv moves on by itself again.
        playback.handle(MpvEvent.EndFile(MpvEndReason.EOF))
        playback.handle(MpvEvent.StartFile(4))
        assertEquals("index 4 auto", recorder.calls.last())
    }

    @Test
    fun skippingToThePlayingTrackGoesBackToItsStart() {
        queue(2)
        playback.skipTo(0)
        assertEquals(listOf(listOf("seek", "0", "absolute+exact")), sent)
        assertEquals(emptyList(), recorder.calls)
    }

    @Test
    fun theLastTrackEndingEndsTheQueueWithRepeatOff() {
        queue(2)
        playback.handle(MpvEvent.EndFile(MpvEndReason.EOF))
        playback.handle(MpvEvent.StartFile(1))
        playback.handle(MpvEvent.EndFile(MpvEndReason.EOF))
        assertEquals(listOf("index 1 auto", "ended"), recorder.calls)
        assertTrue(playback.unloaded)
        // The controller then pauses and goes back to track 1: mpv loads it (paused).
        sent.clear()
        playback.pause()
        playback.skipTo(0)
        assertEquals(listOf(listOf("set", "pause", "yes"), listOf("playlist-play-index", "0")), sent)
        assertEquals("index 0 skip", recorder.calls.last())
        assertFalse(playback.unloaded)
    }

    @Test
    fun aOneTrackQueueEndingReloadsWithoutAChange() {
        queue(1)
        playback.handle(MpvEvent.EndFile(MpvEndReason.EOF))
        assertEquals(listOf("ended"), recorder.calls)
        playback.skipTo(0)
        assertEquals(listOf("playlist-play-index", "0"), sent.last())
        assertEquals(listOf("ended"), recorder.calls)
    }

    @Test
    fun repeatAllGoesRoundAsAnAutomaticChange() {
        queue(2)
        playback.setRepeatMode(RepeatMode.ALL)
        playback.handle(MpvEvent.EndFile(MpvEndReason.EOF))
        playback.handle(MpvEvent.StartFile(1))
        playback.handle(MpvEvent.EndFile(MpvEndReason.EOF))
        playback.handle(MpvEvent.StartFile(0))
        assertEquals(listOf("index 1 auto", "index 0 auto"), recorder.calls)
    }

    @Test
    fun repeatOneGoingRoundIsReported() {
        queue(2)
        playback.handle(MpvEvent.Repeated)
        assertEquals(emptyList(), recorder.calls)
        playback.setRepeatMode(RepeatMode.ONE)
        playback.handle(MpvEvent.Repeated)
        assertEquals(listOf("index 0 auto"), recorder.calls)
    }

    @Test
    fun aFailedTrackStopsThereAndPlayRetriesIt() {
        queue(3)
        playback.handle(MpvEvent.EndFile(MpvEndReason.EOF))
        playback.handle(MpvEvent.StartFile(1))
        sent.clear()
        playback.handle(MpvEvent.EndFile(MpvEndReason.ERROR, "loading failed"))
        assertEquals("error Playback failed (loading failed).", recorder.calls.last())
        assertEquals(listOf(listOf("set", "pause", "yes"), listOf("stop", "keep-playlist")), sent)
        // mpv had already started the next one: not a change.
        playback.handle(MpvEvent.StartFile(2))
        playback.handle(MpvEvent.EndFile(MpvEndReason.STOP))
        assertEquals(1, playback.current)
        assertEquals(2, recorder.calls.size)
        sent.clear()
        playback.play()
        assertEquals(listOf(listOf("set", "pause", "no"), listOf("playlist-play-index", "1")), sent)
        playback.handle(MpvEvent.StartFile(1))
        assertEquals(2, recorder.calls.size)
    }

    @Test
    fun replacingAfterCurrentKeepsThePlayingTrack() {
        queue(4, start = 1)
        playback.replaceAfterCurrent(listOf(item(7), item(8)))
        assertEquals(
            listOf(
                listOf("playlist-remove", "3"),
                listOf("playlist-remove", "2"),
                listOf("loadfile", mpvTarget(item(7).url), "append"),
                listOf("loadfile", mpvTarget(item(8).url), "append"),
            ),
            sent,
        )
        assertTrue(sent.none { it.first() == "playlist-play-index" || it.first() == "stop" })
        assertEquals(listOf("t0", "t1", "t7", "t8"), playback.items.map { it.id })
        assertEquals(1, playback.current)
        assertEquals(emptyList(), recorder.calls)
    }

    @Test
    fun isPlayingFollowsPauseAndIdle() {
        playback.handle(MpvEvent.Paused(false))
        assertEquals(emptyList(), recorder.calls)
        playback.handle(MpvEvent.Idle(false))
        playback.handle(MpvEvent.Paused(true))
        playback.handle(MpvEvent.Paused(true))
        assertEquals(listOf("playing true", "playing false"), recorder.calls)
    }

    @Test
    fun aConvertedStreamIsSeekedByRestartingIt() {
        queue(3, make = { converted(it) })
        assertFalse(playback.seekTo(60_000))
        assertEquals(60_000, playback.currentOffsetMillis)
        val restarted = StreamUrls.startingAt(converted(0).url, 60_000)
        assertEquals(replaceEntryCommands(3, 0, restarted, play = true), sent)
        assertEquals(0, playback.pending)
        // Its start isn't a change of track.
        playback.handle(MpvEvent.EndFile(MpvEndReason.STOP))
        playback.handle(MpvEvent.StartFile(0))
        assertEquals(emptyList(), recorder.calls)
        // Moving on puts its own stream back.
        sent.clear()
        playback.handle(MpvEvent.EndFile(MpvEndReason.EOF))
        playback.handle(MpvEvent.StartFile(1))
        assertEquals(listOf("index 1 auto"), recorder.calls)
        assertEquals(replaceEntryCommands(3, 0, converted(0).url, play = false), sent)
        assertEquals(0, playback.currentOffsetMillis)
    }

    @Test
    fun anOrdinaryStreamIsSeekedByMpv() {
        queue(2)
        assertTrue(playback.seekTo(61_500))
        assertEquals(listOf(listOf("seek", "61.5", "absolute+exact")), sent)
    }

    @Test
    fun stopEmptiesTheQueue() {
        queue(2)
        playback.stop()
        assertEquals(listOf(listOf("stop")), sent)
        assertTrue(playback.items.isEmpty())
        assertTrue(playback.unloaded)
        playback.play()
        assertEquals(1, sent.size)
    }

    // The listening queue: edits round the playing entry

    @Test
    fun replacingKeepsTheEntriesThatStayTheSame() {
        queue(4, start = 1)
        // t2 stays next (opened ahead for a gapless join); t3 goes, t7 comes.
        playback.replaceAfterCurrent(listOf(item(2), item(7)))
        assertEquals(
            listOf(
                listOf("playlist-remove", "3"),
                listOf("loadfile", mpvTarget(item(7).url), "append"),
            ),
            sent,
        )
        assertEquals(listOf("t0", "t1", "t2", "t7"), playback.items.map { it.id })
    }

    @Test
    fun replacingBeforeTheCurrentMovesItWithoutStoppingIt() {
        queue(4, start = 2)
        playback.replaceAroundCurrent(listOf(item(9)), listOf(item(3)))
        assertEquals(
            listOf(
                listOf("playlist-remove", "0"),
                listOf("playlist-remove", "0"),
                listOf("loadfile", mpvTarget(item(9).url), "append"),
                listOf("playlist-move", "2", "0"),
            ),
            sent,
        )
        assertTrue(sent.none { it.first() == "playlist-play-index" || it.first() == "stop" })
        assertEquals(listOf("t9", "t2", "t3"), playback.items.map { it.id })
        assertEquals(1, playback.current)
        assertEquals(emptyList(), recorder.calls)
    }

    @Test
    fun aStartReadBeforeAnEditIsFoundByItsPath() {
        queue(4, start = 2)
        playback.replaceAroundCurrent(emptyList(), listOf(item(3)))
        // mpv moved on to t3 while the old entries before it were still there: it said position 3.
        playback.handle(MpvEvent.StartFile(3, mpvTarget(item(3).url)))
        assertEquals(listOf("index 1 auto"), recorder.calls)
        assertEquals(1, playback.current)
        assertTrue(isStreamOf(StreamUrls.startingAt(converted(1).url, 5_000), converted(1).url))
        assertFalse(isStreamOf(converted(2).url, converted(1).url))
    }

    @Test
    fun replacingTheQueueBeforeAnyEntriesKeepsNothing() {
        assertEquals(
            listOf(listOf("loadfile", "a", "append"), listOf("playlist-move", "1", "0"), listOf("loadfile", "b", "append"), listOf("playlist-move", "2", "1")),
            replaceBeforeCommands(size = 1, current = 0, urls = listOf("a", "b")),
        )
    }
}
