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

    private fun TestScope.setup(
        tracks: Int = 3,
        maniac: Boolean = false,
        online: () -> Boolean = { true },
        /** An album's tracks wait for its gate; a failed gate is a server error. */
        gates: Map<String, kotlinx.coroutines.CompletableDeferred<Boolean>> = emptyMap(),
    ): Setup {
        val server = FakeServer { request ->
            request.url.parameters["ParentId"]?.let { gates[it] }?.let { gate ->
                if (!gate.await()) return@FakeServer json("", io.ktor.http.HttpStatusCode.InternalServerError)
            }
            when (request.path) {
                // Playlist P: tracks X-t1 and Y-t2.
                "/Playlists/P/Items" -> json("""{"Items":[${track("X", 1)},${track("Y", 2)}]}""")
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
        s.player.playNext(album("C")) // refused
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
        assertEquals(listOf("A-t1", "A-t2", "B-t1", "B-t2"), s.engineTracks)
        // The queue still moves on to the next album.
        s.engine.finishTrack()
        s.engine.finishTrack()
        assertEquals("B-t1", s.player.currentTrack?.id)
    }

    @Test
    fun maniacModeRefusesEveryPlayNextButAllowsAddAndPlay() = runTest {
        val s = setup(tracks = 2, maniac = true)
        s.player.play(album("A"))
        settle()
        val before = s.player.playQueue
        val playlist = gg.dekiru.musicplayer.data.models.JellyfinPlaylist(id = "P", name = "Mix")
        s.player.playNext(album("C"))
        s.player.playNext(playlist)
        s.player.playNext(album("C"), emptyList())
        s.player.playNext(playlist, emptyList())
        settle()
        assertEquals(before, s.player.playQueue, "Play Next changes nothing in Maniac Mode")
        assertEquals(listOf("A-t1", "A-t2"), s.engineTracks)
        assertEquals(null, s.player.queueNotice, "a refusal says nothing, like the other refused edits")
        // Add to Queue still works.
        s.player.addToQueue(album("B"))
        settle()
        assertEquals(listOf("A-t1", "A-t2", "B-t1", "B-t2"), s.engineTracks)
        // Play replaces the queue.
        s.player.play(album("D"))
        settle()
        assertEquals(listOf("D-t1", "D-t2"), s.engineTracks)
    }

    @Test
    fun turningManiacModeOffRestoresPlayNext() = runTest {
        val s = setup(tracks = 2, maniac = true)
        s.player.play(album("A"))
        settle()
        s.player.playNext(album("C"))
        settle()
        assertEquals(listOf("A-t1", "A-t2"), s.engineTracks)
        s.player.changeManiacMode(false)
        s.player.playNext(album("C"))
        settle()
        assertEquals(listOf("A-t1", "A-t2", "C-t1", "C-t2"), s.engineTracks)
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
        // Held at the point, nothing opened until Play.
        assertEquals(42_000L, r.engine.cuedAt)
        assertFalse(r.engine.opened)
        assertEquals(0, r.engine.loads)
        assertFalse(r.engine.playing)
        assertEquals(RepeatMode.ALL, r.engine.repeat)
        assertEquals(42.0, r.player.position)
        assertEquals("A", r.player.queueSourceId)
        settle()
        assertEquals(emptyList(), r.reports(), "nothing reported on restore")
        r.player.resume()
        settle()
        assertTrue(r.engine.playing)
        assertEquals(42_000L, r.engine.openedAt, "Play opens the stream at the saved point")
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

    // Step 3: the menus' playlist and song calls, and when Play asks

    @Test
    fun aPlaylistCardIsFetchedAndQueued() = runTest {
        val s = setup()
        s.player.play(album("A"))
        settle()
        val playlist = gg.dekiru.musicplayer.data.models.JellyfinPlaylist(id = "P", name = "Mix")
        s.player.addToQueue(playlist)
        settle()
        assertEquals(listOf("A-t1", "A-t2", "A-t3", "X-t1", "Y-t2"), s.queueTracks)
        assertEquals(QueueGroupKind.PLAYLIST, s.player.displayGroups.last().group.kind)
        assertEquals(PlayerController.ADDED_TO_QUEUE, s.player.queueNotice?.message)
    }

    @Test
    fun aPlaylistSongIsQueuedAsPartOfThePlaylist() = runTest {
        val s = setup()
        s.player.play(album("A"))
        settle()
        val playlist = gg.dekiru.musicplayer.data.models.JellyfinPlaylist(id = "P", name = "Mix")
        val song = gg.dekiru.musicplayer.data.models.PlaylistEntry(
            entryId = "e1",
            track = gg.dekiru.musicplayer.data.models.JellyfinTrack(id = "S", name = "Song", runTimeTicks = 600_000_000L, albumId = "Z"),
        )
        s.player.playNext(playlist, listOf(song))
        settle()
        assertEquals(listOf("A-t1", "A-t2", "A-t3", "S"), s.queueTracks)
        assertEquals(QueueGroupKind.PLAYLIST, s.player.displayGroups.last().group.kind)
        assertEquals(PlayerController.PLAYING_NEXT, s.player.queueNotice?.message)
    }

    @Test
    fun offlineAPlaylistWithNoDownloadSaysSo() = runTest {
        val s = setup(online = { false })
        s.player.play(album("A"))
        settle()
        s.player.playNext(gg.dekiru.musicplayer.data.models.JellyfinPlaylist(id = "P", name = "Mix"))
        settle()
        assertEquals(PlayerController.NOTHING_DOWNLOADED, s.player.queueNotice?.message)
        assertTrue(s.player.queueNotice?.isError == true)
    }

    @Test
    fun playAsksOnlyWhenMoreThanThePlayingAlbumIsQueued() = runTest {
        val s = setup()
        assertFalse(s.player.asksBeforePlay, "nothing playing")
        s.player.play(album("A"))
        settle()
        assertFalse(s.player.asksBeforePlay, "just the playing album")
        s.player.addToQueue(album("B"))
        settle()
        assertTrue(s.player.asksBeforePlay)
        // Once B is all that's left, nothing else is queued.
        s.player.skipTo(s.player.playQueue.playOrder[3])
        assertFalse(s.player.asksBeforePlay)
        // Repeat All brings A round again: something else is queued.
        s.player.changeRepeatMode(RepeatMode.ALL)
        assertTrue(s.player.asksBeforePlay)
    }

    @Test
    fun theEditorsMovesApplyToThePlayer() = runTest {
        val s = setup(tracks = 2)
        threeAlbums(s) // A1 A2 | C1 C2 | B1 B2, A1 playing
        val editor = QueueEditor.from(s.player.playQueue)
        // B's group up, above C (allowed: not above the rest of A).
        val bRun = editor.runs.indexOfFirst { it.first().group.id == "B" }
        val slot = editor.groupUpSlot(bRun)!!
        s.player.moveQueue(editor.runs[bRun].map { it.id }, QueueEditor.toIndex(s.player.playQueue.currentPlayIndex, slot))
        assertEquals(listOf("A-t1", "A-t2", "B-t1", "B-t2", "C-t1", "C-t2"), s.queueTracks)
        assertEquals(s.queueTracks, s.engineTracks)
        // A track down one.
        val e2 = QueueEditor.from(s.player.playQueue)
        val b1 = e2.upcoming.first { it.track.id == "B-t1" }.id
        s.player.moveQueue(listOf(b1), QueueEditor.toIndex(s.player.playQueue.currentPlayIndex, e2.trackDownSlot(b1)!!))
        assertEquals(listOf("A-t1", "A-t2", "B-t2", "B-t1", "C-t1", "C-t2"), s.queueTracks)
        assertEquals("A-t1", s.player.currentTrack?.id, "the playing track stays")
    }

    // Adds apply in the order they were tapped

    private fun gate() = kotlinx.coroutines.CompletableDeferred<Boolean>()

    @Test
    fun twoAddsFinishingInReverseLandInTapOrder() = runTest {
        val b = gate()
        val c = gate()
        val s = setup(tracks = 1, gates = mapOf("B" to b, "C" to c))
        s.player.play(album("A"))
        settle()
        s.player.addToQueue(album("B"))
        s.player.addToQueue(album("C"))
        c.complete(true)
        settle()
        assertEquals(listOf("A-t1"), s.queueTracks, "C waits for B")
        b.complete(true)
        settle()
        assertEquals(listOf("A-t1", "B-t1", "C-t1"), s.queueTracks)
        assertEquals(s.queueTracks, s.engineTracks)
    }

    @Test
    fun aFailedAddInTheMiddleIsDroppedAndTheRestApply() = runTest {
        val b = gate()
        val c = gate()
        val d = gate()
        val s = setup(tracks = 1, gates = mapOf("B" to b, "C" to c, "D" to d))
        s.player.play(album("A"))
        settle()
        s.player.addToQueue(album("B"))
        s.player.addToQueue(album("C"))
        s.player.addToQueue(album("D"))
        d.complete(true)
        c.complete(false)
        settle()
        assertEquals(PlayerController.ALBUM_NOT_QUEUED, s.player.queueNotice?.message, "the failure says so at once")
        assertEquals(listOf("A-t1"), s.queueTracks)
        b.complete(true)
        settle()
        assertEquals(listOf("A-t1", "B-t1", "D-t1"), s.queueTracks)
    }

    @Test
    fun aFailedFirstAddLetsTheNextApply() = runTest {
        val b = gate()
        val s = setup(tracks = 1, gates = mapOf("B" to b))
        s.player.play(album("A"))
        settle()
        s.player.addToQueue(album("B"))
        s.player.addToQueue(album("C"))
        settle()
        assertEquals(listOf("A-t1"), s.queueTracks)
        b.complete(false)
        settle()
        assertEquals(listOf("A-t1", "C-t1"), s.queueTracks)
    }

    @Test
    fun playNextTwiceputsTheSecondInFront() = runTest {
        val b = gate()
        val s = setup(tracks = 1, gates = mapOf("B" to b))
        s.player.play(album("A"))
        settle()
        s.player.playNext(album("B"))
        s.player.playNext(album("C"))
        settle()
        b.complete(true)
        settle()
        assertEquals(listOf("A-t1", "C-t1", "B-t1"), s.queueTracks)
    }

    @Test
    fun playCancelsWaitingAddsButNotLaterOnes() = runTest {
        val b = gate()
        val s = setup(tracks = 1, gates = mapOf("B" to b))
        s.player.addToQueue(album("B"))
        s.player.play(album("A"))
        settle()
        s.player.addToQueue(album("C"))
        settle()
        b.complete(true)
        settle()
        assertEquals(listOf("A-t1", "C-t1"), s.queueTracks, "B was dropped by the new Play; C applied")
    }

    @Test
    fun anAddAfterPlayLandsAfterTheReplaceEvenIfItsTracksComeFirst() = runTest {
        val a = gate()
        val s = setup(tracks = 1, gates = mapOf("A" to a))
        s.player.play(album("Z"))
        settle()
        s.player.play(album("A"))
        s.player.addToQueue(album("B"))
        settle()
        assertEquals(listOf("Z-t1"), s.queueTracks, "B waits for A's Play")
        a.complete(true)
        settle()
        assertEquals(listOf("A-t1", "B-t1"), s.queueTracks)
    }

    @Test
    fun aLaterPlaySupersedesAnEarlierOneStillFetching() = runTest {
        val a = gate()
        val s = setup(tracks = 1, gates = mapOf("A" to a))
        s.player.play(album("A"))
        s.player.play(album("B"))
        settle()
        assertEquals(listOf("B-t1"), s.queueTracks)
        a.complete(false)
        settle()
        assertEquals(listOf("B-t1"), s.queueTracks)
        assertNull(s.player.lastError, "the earlier Play's failure isn't shown")
    }
}
