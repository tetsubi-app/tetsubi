package gg.dekiru.musicplayer.playback

import gg.dekiru.musicplayer.data.api.FakeServer
import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import gg.dekiru.musicplayer.data.api.path
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinPerson
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The player playing from the listening queue: several albums, edits, shuffle, repeat, Maniac Mode, saving and restoring. */
class QueuePlaybackTest {

    /** Album `a` has `count` tracks `a-t1`…, track i lasting i minutes. */
    private fun track(album: String, i: Int) = """
        {"Id":"$album-t$i","Name":"$album $i","IndexNumber":$i,"AlbumId":"$album","RunTimeTicks":${i * 600_000_000L},
         "AlbumArtists":[{"Name":"Band"}],"MediaSources":[{"Container":"flac","Codecs":"flac"}]}
    """

    private fun album(id: String) = JellyfinAlbum(id = id, name = "Album $id", albumArtists = listOf(JellyfinPerson(name = "Band")))

    private class Setup(val server: FakeServer, val engine: FakeEngine, val player: PlayerController, val saved: MutableList<PlayQueueSnapshot>)

    private fun TestScope.setup(tracks: Int = 3, maniac: Boolean = false, online: () -> Boolean = { true }): Setup {
        val server = FakeServer { request ->
            when (request.path) {
                "/Items" -> {
                    val parent = request.url.parameters["ParentId"]!!
                    json("""{"Items":[${(1..tracks).joinToString(",") { track(parent, it) }}]}""")
                }
                else -> json("")
            }
        }
        val engine = FakeEngine()
        val settings = PlaybackSettings(InMemoryPreferences()).also { it.maniacMode = maniac }
        val saved = mutableListOf<PlayQueueSnapshot>()
        val saver = PlayQueueSaver(backgroundScope, { saved += it }, intervalMillis = 10, ioDispatcher = Dispatchers.Unconfined)
        val player = PlayerController(
            server.api(token = "tok", userId = "u1"), engine, settings, backgroundScope,
            isOnline = online, random = Random(7), queueSaver = saver,
        )
        return Setup(server, engine, player, saved)
    }

    private fun Setup.reports() = server.requests.filter { it.path.startsWith("/Sessions/Playing") }
        .map { it.path.removePrefix("/Sessions/Playing").ifEmpty { "start" } }

    private val Setup.engineTracks get() = engine.items.map { it.id }
    private val Setup.queueTracks get() = player.queue.map { it.id }

    /** Plays album A, then adds B (Add to Queue) and C (Play Next). */
    private suspend fun TestScope.threeAlbums(s: Setup) {
        s.player.play(album("A"))
        settle()
        s.player.addToQueue(album("B"))
        settle()
        s.player.playNext(album("C"))
        settle()
    }

    @Test
    fun playReplacesTheQueueWithTheAlbum() = runTest {
        val s = setup()
        s.player.play(album("A"), startTrackId = "A-t2")
        settle()
        assertEquals(listOf("A-t1", "A-t2", "A-t3"), s.queueTracks)
        assertEquals(s.player.playQueue.playOrder, s.engine.items.map { it.entryId }, "engine items are the queue's entries")
        assertEquals("A-t2", s.player.currentTrack?.id)
        assertEquals(1, s.engine.index)
        assertEquals("A", s.player.queueSourceId)
        assertTrue(s.player.isPlaying(album("A")))
        // Playing another album replaces it.
        s.player.play(album("B"))
        settle()
        assertEquals(listOf("B-t1", "B-t2", "B-t3"), s.queueTracks)
        assertEquals(1, s.player.displayGroups.size)
    }

    @Test
    fun playNextAndAddToQueueFeedTheEngineWithoutInterrupting() = runTest {
        val s = setup()
        s.player.play(album("A"))
        settle()
        s.player.addToQueue(album("B"))
        settle()
        assertEquals(PlayerController.ADDED_TO_QUEUE, s.player.queueNotice?.message)
        s.player.playNext(album("C"))
        settle()
        assertEquals(PlayerController.PLAYING_NEXT, s.player.queueNotice?.message)
        // C goes after the playing album, before B.
        val expected = listOf("A-t1", "A-t2", "A-t3", "C-t1", "C-t2", "C-t3", "B-t1", "B-t2", "B-t3")
        assertEquals(expected, s.queueTracks)
        assertEquals(expected, s.engineTracks)
        assertEquals(1, s.engine.loads, "one load: the edits went round the playing track")
        assertEquals(0, s.engine.index)
        assertEquals("A-t1", s.player.currentTrack?.id)
        assertEquals(listOf("A", "C", "B"), s.player.displayGroups.map { it.group.id })
        assertEquals(expected.drop(1), s.player.upcomingEntries.map { it.track.id })
        assertEquals(listOf("start"), s.reports(), "nothing new reported")
    }

    @Test
    fun theSameTrackQueuedTwiceIsTwoEntries() = runTest {
        val s = setup(tracks = 2)
        s.player.play(album("A"))
        settle()
        s.player.addToQueue(album("A"))
        settle()
        assertEquals(listOf("A-t1", "A-t2", "A-t1", "A-t2"), s.engineTracks)
        assertEquals(4, s.engine.items.map { it.entryId }.toSet().size)
        s.player.skipTo(s.player.playQueue.playOrder[2])
        assertEquals(2, s.engine.index)
        assertEquals(2, s.player.currentIndex)
        assertEquals(0, s.player.currentPlace, "first of its album")
    }

    @Test
    fun anEmptyQueueStartsPlayingWhatsAdded() = runTest {
        val s = setup()
        s.player.addToQueue(album("A"))
        settle()
        assertEquals("A-t1", s.player.currentTrack?.id)
        assertTrue(s.engine.playing)
        assertFalse(s.player.isNowPlayingOpen)
    }

    @Test
    fun gaplessIntoTheNextAlbumMovesTheQueueOn() = runTest {
        val s = setup(tracks = 2)
        s.player.play(album("A"))
        settle()
        s.player.addToQueue(album("B"))
        settle()
        s.engine.finishTrack()
        s.engine.finishTrack()
        settle()
        assertEquals("B-t1", s.player.currentTrack?.id)
        assertTrue(s.player.isPlaying(album("B")))
        assertFalse(s.player.isPlaying(album("A")))
        assertEquals(listOf("B"), s.player.playingDiscIds)
        assertEquals(listOf("start", "/Stopped", "start", "/Stopped", "start"), s.reports())
        assertEquals(1, s.engine.loads)
    }

    @Test
    fun removingThePlayingEntryPlaysTheNextOrEnds() = runTest {
        val s = setup(tracks = 2)
        threeAlbums(s)
        val first = s.player.playQueue.currentEntryId!!
        s.player.removeFromQueue(listOf(first))
        assertEquals("A-t2", s.player.currentTrack?.id, "MOVED: the next one")
        assertEquals(listOf("A-t2", "C-t1", "C-t2", "B-t1", "B-t2"), s.engineTracks)
        assertEquals(0, s.engine.index)
        assertEquals(1, s.engine.loads)
        assertEquals(0.0, s.player.position)

        // The last one playing and removed: stop at the first remaining, paused; Now Playing stays.
        s.player.isNowPlayingOpen = true
        val last = s.player.playQueue.playOrder.last()
        s.player.skipTo(last)
        settle()
        s.player.removeFromQueue(listOf(last))
        settle()
        assertEquals("A-t2", s.player.currentTrack?.id)
        assertFalse(s.engine.playing)
        assertFalse(s.player.isPlaying)
        assertTrue(s.player.isNowPlayingOpen)
        assertEquals(listOf("A-t2", "C-t1", "C-t2", "B-t1"), s.engineTracks)
        assertEquals(0, s.engine.index)
    }

    @Test
    fun removingEverythingEmptiesThePlayer() = runTest {
        val s = setup(tracks = 1)
        s.player.play(album("A"))
        settle()
        s.player.removeFromQueue(s.player.playQueue.playOrder)
        assertNull(s.player.currentTrack)
        assertTrue(s.engine.items.isEmpty())
    }

    @Test
    fun removingOtherEntriesKeepsPlaying() = runTest {
        val s = setup(tracks = 2)
        threeAlbums(s)
        val c = s.player.displayGroups[1].entries.map { it.id }
        s.player.removeFromQueue(c)
        assertEquals(listOf("A-t1", "A-t2", "B-t1", "B-t2"), s.engineTracks)
        assertEquals(1, s.engine.loads)
        assertEquals("A-t1", s.player.currentTrack?.id)
    }

    @Test
    fun movingAGroupReordersTheEngine() = runTest {
        val s = setup(tracks = 2)
        threeAlbums(s)
        // B's group to just after the playing album.
        val b = s.player.displayGroups[2].entries.map { it.id }
        s.player.moveQueue(b, 2)
        assertEquals(listOf("A-t1", "A-t2", "B-t1", "B-t2", "C-t1", "C-t2"), s.engineTracks)
        assertEquals(1, s.engine.loads)
        // Something before the playing entry: still not interrupted.
        s.engine.finishTrack()
        s.player.moveQueue(listOf(s.player.playQueue.playOrder[0]), 5)
        assertEquals(listOf("A-t2", "B-t1", "B-t2", "C-t1", "C-t2", "A-t1"), s.engineTracks)
        assertEquals(0, s.engine.index)
        assertEquals("A-t2", s.player.currentTrack?.id)
        assertEquals(1, s.engine.loads)
    }

    @Test
    fun clearUpcomingKeepsThePlayingEntry() = runTest {
        val s = setup(tracks = 2)
        threeAlbums(s)
        s.engine.finishTrack()
        s.player.clearUpcoming()
        assertEquals(listOf("A-t2"), s.engineTracks)
        assertEquals(listOf("A-t2"), s.queueTracks)
        assertEquals(0, s.engine.index)
        assertEquals(1, s.engine.loads)
    }

    @Test
    fun shuffleShufflesTheWholeQueueAndOffRestoresIt() = runTest {
        val s = setup(tracks = 3)
        threeAlbums(s)
        val natural = s.queueTracks
        s.engine.finishTrack()
        s.player.changeShuffle(true)
        assertEquals("A-t2", s.engineTracks.first(), "the playing track first")
        assertEquals(0, s.engine.index)
        assertEquals(natural.toSet(), s.engineTracks.toSet())
        assertNotEquals(natural.drop(1), s.engineTracks.drop(1))
        assertEquals(s.queueTracks, s.engineTracks)
        s.player.changeShuffle(false)
        assertEquals(natural, s.engineTracks)
        assertEquals(1, s.engine.index)
        assertEquals("A-t2", s.player.currentTrack?.id)
        assertEquals(1, s.engine.loads)
    }

    @Test
    fun playWithShuffleOnShufflesTheAlbum() = runTest {
        val s = setup(tracks = 8)
        s.player.changeShuffle(true)
        s.player.play(album("A"), startTrackId = "A-t5")
        settle()
        assertEquals("A-t5", s.engineTracks.first())
        assertEquals((1..8).map { "A-t$it" }.toSet(), s.engineTracks.toSet())
    }

    @Test
    fun repeatAllGoesRoundTheWholeQueue() = runTest {
        val s = setup(tracks = 2)
        threeAlbums(s)
        s.player.changeRepeatMode(RepeatMode.ALL)
        assertEquals(RepeatMode.ALL, s.engine.repeat)
        s.player.skipTo(s.player.playQueue.playOrder.last())
        assertEquals("B-t2", s.player.currentTrack?.id)
        assertEquals(listOf("A-t1", "A-t2", "C-t1", "C-t2", "B-t1"), s.player.upNext.map { it.second.id }, "then round again")
        s.player.skipToNext()
        assertEquals("A-t1", s.player.currentTrack?.id)
        assertEquals(0, s.engine.index)
        s.player.skipToPrevious()
        assertEquals("B-t2", s.player.currentTrack?.id, "back round to the end")
    }

    @Test
    fun repeatOneRepeatsTheTrackButNextSkips() = runTest {
        val s = setup(tracks = 2)
        threeAlbums(s)
        s.player.changeRepeatMode(RepeatMode.ONE)
        assertEquals(RepeatMode.ONE, s.engine.repeat)
        // The engine going round the same track: a new play.
        s.engine.listener?.onIndexChanged(0, automatic = true)
        settle()
        assertEquals("A-t1", s.player.currentTrack?.id)
        assertEquals(listOf("start", "/Stopped", "start"), s.reports())
        s.player.skipToNext()
        assertEquals("A-t2", s.player.currentTrack?.id)
    }

    @Test
    fun maniacModeRefusesEditsButAllowsAdding() = runTest {
        val s = setup(tracks = 2, maniac = true)
        s.player.play(album("A"))
        settle()
        s.player.addToQueue(album("B"))
        settle()
        s.player.playNext(album("C"))
        settle()
        val before = s.player.playQueue
        val ids = before.playOrder
        s.player.moveQueue(listOf(ids.last()), 0)
        s.player.removeFromQueue(listOf(ids[1]))
        s.player.clearUpcoming()
        s.player.skipTo(ids[3])
        s.player.changeShuffle(true)
        s.player.changeRepeatMode(RepeatMode.ALL)
        assertEquals(before, s.player.playQueue)
        assertEquals(listOf("A-t1", "A-t2", "C-t1", "C-t2", "B-t1", "B-t2"), s.engineTracks)
        // The queue still moves on to the next album.
        s.engine.finishTrack()
        s.engine.finishTrack()
        assertEquals("C-t1", s.player.currentTrack?.id)
    }

    @Test
    fun maniacModeOnPutsTheWholeQueueBackInOrder() = runTest {
        val s = setup(tracks = 3)
        threeAlbums(s)
        val natural = s.queueTracks
        s.player.changeShuffle(true)
        s.engine.finishTrack()
        val playing = s.player.currentTrack?.id
        s.player.changeManiacMode(true)
        assertFalse(s.player.shuffle)
        assertEquals(natural, s.engineTracks)
        assertEquals(playing, s.player.currentTrack?.id)
    }

    @Test
    fun timesCoverTheGroupAndTheQueue() = runTest {
        val s = setup(tracks = 3) // 1 + 2 + 3 minutes an album
        threeAlbums(s)
        assertEquals(18 * 60.0, s.player.queueLength)
        assertEquals(18 * 60.0, s.player.queueRemaining)
        assertEquals(6 * 60.0, s.player.currentGroupRemaining)
        s.player.removeFromQueue(s.player.displayGroups[2].entries.map { it.id })
        assertEquals(12 * 60.0, s.player.queueRemaining)
        s.engine.finishTrack()
        assertEquals(11 * 60.0, s.player.queueRemaining)
        assertEquals(5 * 60.0, s.player.currentGroupRemaining)
    }

    @Test
    fun changesAreSaved() = runTest {
        val s = setup(tracks = 2)
        threeAlbums(s)
        settle()
        val last = s.saved.last()
        assertEquals(6, last.entries.size)
        assertEquals(s.player.playQueue.currentEntryId, last.currentEntryId)
        assertEquals("http://jellyfin-box:8096", last.serverId)
        assertEquals("u1", last.userId)
        s.player.changeRepeatMode(RepeatMode.ALL)
        settle()
        assertEquals(RepeatMode.ALL, s.saved.last().repeatMode)
        s.engine.finishTrack()
        settle()
        assertEquals(s.player.playQueue.currentEntryId, s.saved.last().currentEntryId)
        assertEquals(0.0, s.saved.last().positionSeconds, "a new entry starts at 0")
        // Stopped (signed out): nothing more is saved, a save that was waiting included.
        val count = s.saved.size
        s.player.changeRepeatMode(RepeatMode.ONE)
        s.player.stop()
        settle()
        assertEquals(count, s.saved.size)
    }

    @Test
    fun anAlbumStillLoadingWhenStoppedIsNotPlayed() = runTest {
        val s = setup()
        // Its tracks arrive after the player was stopped (signed out).
        s.player.addToQueue(album("A"))
        s.player.stop()
        settle()
        assertEquals(0, s.engine.loads)
        assertFalse(s.engine.playing)
        assertNull(s.player.currentTrack)
        assertNull(s.player.queueNotice)
    }

    @Test
    fun aSavedQueueComesBackPausedWhereItWas() = runTest {
        val s = setup(tracks = 2)
        threeAlbums(s)
        s.engine.finishTrack()
        s.player.changeRepeatMode(RepeatMode.ALL)
        val snapshot = s.player.playQueue.snapshot(42.0, "http://jellyfin-box:8096", "u1")

        val r = setup(tracks = 2)
        r.player.restore(snapshot)
        assertEquals(snapshot.currentEntryId, r.player.playQueue.currentEntryId)
        assertEquals("A-t2", r.player.currentTrack?.id)
        assertEquals(listOf("A-t1", "A-t2", "C-t1", "C-t2", "B-t1", "B-t2"), r.engineTracks)
        assertEquals(1, r.engine.index)
        assertEquals(listOf(42_000L), r.engine.seeks)
        assertFalse(r.engine.playing)
        assertEquals(RepeatMode.ALL, r.engine.repeat)
        assertEquals(42.0, r.player.position)
        assertEquals("A", r.player.queueSourceId)
        settle()
        assertEquals(emptyList(), r.reports(), "nothing reported on restore")
        r.player.resume()
        settle()
        assertTrue(r.engine.playing)
        assertEquals(listOf("start"), r.reports())
    }

    @Test
    fun offlineARestoreSkipsWhatIsntDownloaded() = runTest {
        val s = setup(tracks = 2)
        threeAlbums(s)
        val snapshot = s.player.playQueue.snapshot(30.0)
        // Nothing is downloaded and there's no connection: nothing comes back.
        val r = setup(tracks = 2, online = { false })
        r.player.restore(snapshot)
        assertNull(r.player.currentTrack)
        assertTrue(r.engine.items.isEmpty())
    }

    @Test
    fun maniacModeRestoresInOrderWithoutRepeat() = runTest {
        val s = setup(tracks = 3)
        threeAlbums(s)
        s.player.changeShuffle(true)
        s.player.changeRepeatMode(RepeatMode.ONE)
        val snapshot = s.player.playQueue.snapshot(0.0)
        val r = setup(tracks = 3, maniac = true)
        r.player.restore(snapshot)
        assertFalse(r.player.shuffle)
        assertEquals(RepeatMode.OFF, r.player.repeatMode)
        assertEquals(r.player.playQueue.entries.map { it.track.id }, r.engineTracks)
    }

    @Test
    fun offlineAnAlbumThatIsntDownloadedIsntQueued() = runTest {
        val s = setup(tracks = 2, online = { false })
        s.player.addToQueue(album("A"))
        settle()
        assertNull(s.player.currentTrack)
        assertEquals(true, s.player.queueNotice?.isError)
    }
}
