package gg.dekiru.musicplayer.playback

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shared fixtures: albums A (3 × 100 s), B (2 × 200 s), C (2 × 50 s); IDs "u1", "u2"… in order of creation. */
internal class QueueFixture {
    private var next = 0
    val ids: () -> String = { "u${++next}" }
    val random = Random(42)

    companion object {
        fun group(id: String, kind: QueueGroupKind = QueueGroupKind.ALBUM) = QueueGroup(id, kind, "Album $id", "Artist $id", id)

        fun track(id: String, album: String, seconds: Double = 100.0) =
            QueueTrack(id = id, title = "Track $id", artist = "Artist $album", album = "Album $album", albumId = album, durationSeconds = seconds)

        val A = group("A")
        val B = group("B")
        val C = group("C")
        val aTracks = listOf(track("a1", "A"), track("a2", "A"), track("a3", "A"))
        val bTracks = listOf(track("b1", "B", 200.0), track("b2", "B", 200.0))
        val cTracks = listOf(track("c1", "C", 50.0), track("c2", "C", 50.0))
    }
}

class PlayQueueTest {
    private val f = QueueFixture()
    private val A = QueueFixture.A
    private val B = QueueFixture.B
    private val C = QueueFixture.C
    private val aTracks = QueueFixture.aTracks
    private val bTracks = QueueFixture.bTracks
    private val cTracks = QueueFixture.cTracks

    private fun PlayQueue.trackIds() = entries.map { it.track.id }
    private fun PlayQueue.currentTrackId() = currentEntry?.track?.id

    /** The entry ID of the first entry with this track. */
    private fun PlayQueue.idOf(trackId: String) = entries.first { it.track.id == trackId }.id
    private fun PlayQueue.idsOf(vararg trackIds: String) = trackIds.map { idOf(it) }

    /** Invariants every queue must keep. */
    private fun PlayQueue.check(): PlayQueue {
        val ids = entries.map { it.id }
        assertEquals(ids.toSet().size, ids.size, "entry IDs unique")
        assertEquals(ids.toSet(), playOrder.toSet(), "play order covers every entry")
        assertEquals(ids.size, playOrder.size, "play order has each entry once")
        if (!shuffle) assertEquals(ids, playOrder, "unshuffled play order is natural order")
        if (entries.isEmpty()) assertNull(currentEntryId) else assertTrue(entry(currentEntryId!!) != null, "current is an entry")
        return this
    }

    private fun queue(build: PlayQueue.() -> Unit = {}): PlayQueue =
        PlayQueue().apply { replace(A, aTracks, newId = f.ids) }.apply(build).check()

    private fun PlayQueue.add(group: QueueGroup, tracks: List<QueueTrack>) = addToQueue(group, tracks, f.random, f.ids)
    private fun PlayQueue.putNext(group: QueueGroup, tracks: List<QueueTrack>) = playNext(group, tracks, f.random, f.ids)

    // Empty

    @Test
    fun emptyQueue() {
        val q = PlayQueue()
        assertTrue(q.isEmpty)
        assertNull(q.currentEntry)
        assertEquals(emptyList(), q.upcomingEntries)
        assertEquals(emptyList(), q.displayGroups)
        assertNull(q.next())
        assertNull(q.next(trackEnded = true))
        assertNull(q.previous())
        assertEquals(0.0, q.queueRemaining(10.0))
        assertEquals(0.0, q.currentGroupRemaining(10.0))
        assertFalse(q.skipTo("nope"))
        assertEquals(QueueCurrentChange.UNCHANGED, q.remove(listOf("nope")))
        assertFalse(q.move(listOf("nope"), 0))
        q.clearUpcoming()
        q.setShuffle(true, f.random)
        assertTrue(q.check().shuffle)
        assertEquals(PlayQueue(shuffle = true), q)
    }

    @Test
    fun replaceWithNoTracksEmptiesButKeepsModes() {
        val q = queue {
            repeatMode = RepeatMode.ALL
            setShuffle(true, f.random)
            assertEquals(emptyList(), replace(B, emptyList(), newId = f.ids))
        }
        assertTrue(q.isEmpty)
        assertTrue(q.shuffle)
        assertEquals(RepeatMode.ALL, q.repeatMode)
    }

    // Replace

    @Test
    fun replaceStartsAtFirstOrTheGivenIndex() {
        val q = queue()
        assertEquals(listOf("a1", "a2", "a3"), q.trackIds())
        assertEquals("a1", q.currentTrackId())
        assertEquals(1, q.entries.map { it.batchId }.toSet().size, "one batch")
        assertTrue(q.entries.all { it.group == A })

        val fromA2 = q.copy().apply { replace(A, aTracks, startIndex = 1, newId = f.ids) }.check()
        assertEquals("a2", fromA2.currentTrackId())
        assertEquals(listOf("a3"), fromA2.upcomingEntries.map { it.track.id })
        assertNotEquals(q.entries.first().batchId, fromA2.entries.first().batchId, "a new batch")

        val outOfRange = q.copy().apply { replace(B, bTracks, startIndex = 9, newId = f.ids) }.check()
        assertEquals("b1", outOfRange.currentTrackId())
        assertEquals(listOf("a1", "a2", "a3"), q.trackIds(), "copies are independent")
    }

    @Test
    fun replaceWhileShuffledPutsTheStartFirst() {
        val q = PlayQueue(shuffle = true).apply { replace(A, aTracks, startIndex = 2, random = f.random, newId = f.ids) }.check()
        assertTrue(q.shuffle)
        assertEquals("a3", q.currentTrackId())
        assertEquals(0, q.currentPlayIndex)
        assertEquals(listOf("a1", "a2", "a3"), q.trackIds(), "natural order kept")

        val noStart = PlayQueue(shuffle = true).apply { replace(A, aTracks, random = Random(1), newId = f.ids) }.check()
        assertEquals(0, noStart.currentPlayIndex)
        assertEquals(noStart.playOrder.first(), noStart.currentEntryId)
    }

    @Test
    fun duplicateTracksGetTheirOwnEntries() {
        val p = QueueFixture.group("P", QueueGroupKind.PLAYLIST)
        val tracks = listOf(QueueFixture.track("x", "P"), QueueFixture.track("y", "P"), QueueFixture.track("x", "P"))
        val q = PlayQueue().apply { replace(p, tracks, startIndex = 2, newId = f.ids) }.check()
        assertEquals(listOf("x", "y", "x"), q.trackIds())
        assertEquals(q.entries[2].id, q.currentEntryId, "the start is an index, so the second x")
        assertEquals(emptyList(), q.upcomingEntries)
        // Removing one copy leaves the other.
        q.remove(listOf(q.entries[0].id))
        assertEquals(listOf("y", "x"), q.check().trackIds())
        assertEquals("x", q.currentTrackId())
    }

    // Play next / add to queue

    @Test
    fun playNextGoesAfterTheCurrentBatchNotMidAlbum() {
        val q = queue { add(C, cTracks) } // A A A C C, playing a1
        val added = q.putNext(B, bTracks)
        assertEquals(listOf("b1", "b2"), added.map { it.track.id })
        assertEquals(1, added.map { it.batchId }.toSet().size)
        assertEquals(listOf("a1", "a2", "a3", "b1", "b2", "c1", "c2"), q.check().trackIds())
        assertEquals("a1", q.currentTrackId())
    }

    @Test
    fun aSecondPlayNextGoesBeforeTheFirst() {
        val q = queue {
            putNext(B, bTracks)
            putNext(C, cTracks)
        }
        assertEquals(listOf("a1", "a2", "a3", "c1", "c2", "b1", "b2"), q.trackIds())
    }

    @Test
    fun playNextWhenCurrentIsTheLastEntry() {
        val q = queue { skipTo(idOf("a3")) }
        q.putNext(B, bTracks)
        assertEquals(listOf("a1", "a2", "a3", "b1", "b2"), q.check().trackIds())
        assertEquals(listOf("b1", "b2"), q.upcomingEntries.map { it.track.id })
    }

    @Test
    fun theSameAlbumQueuedTwiceIsTwoBatches() {
        val q = queue { add(A, aTracks) }
        assertEquals(2, q.displayGroups.size)
        q.putNext(B, bTracks)
        assertEquals(listOf("a1", "a2", "a3", "b1", "b2", "a1", "a2", "a3"), q.check().trackIds(), "after the first copy only")
        assertEquals(300.0, q.currentGroupRemaining(0.0))
    }

    @Test
    fun playNextAndAddOnAnEmptyQueueStartPlaying() {
        val next = PlayQueue().apply { putNext(B, bTracks) }.check()
        assertEquals("b1", next.currentTrackId())
        val add = PlayQueue().apply { add(C, cTracks) }.check()
        assertEquals("c1", add.currentTrackId())
        assertEquals(listOf("c1", "c2"), add.trackIds())
    }

    @Test
    fun addingNothingChangesNothing() {
        val q = queue()
        val before = q.copy()
        assertEquals(emptyList(), q.add(B, emptyList()))
        assertEquals(emptyList(), q.putNext(B, emptyList()))
        assertEquals(before, q)
    }

    @Test
    fun addToQueueAppends() {
        val q = queue {
            add(B, bTracks)
            add(C, cTracks)
        }
        assertEquals(listOf("a1", "a2", "a3", "b1", "b2", "c1", "c2"), q.trackIds())
        assertEquals("a1", q.currentTrackId())
    }

    @Test
    fun addToQueueWhileShuffledGoesAfterCurrentInPlayOrder() {
        val q = queue {
            add(B, bTracks)
            skipTo(idOf("a2"))
            setShuffle(true, f.random)
        }
        val before = q.copy()
        val played = q.playOrder.take(q.currentPlayIndex + 1)
        q.add(C, cTracks)
        q.check()
        assertEquals(listOf("a1", "a2", "a3", "b1", "b2", "c1", "c2"), q.trackIds(), "natural order: at the end")
        assertEquals(played, q.playOrder.take(q.currentPlayIndex + 1), "nothing before current")
        assertEquals("a2", q.currentTrackId())
        val oldUpcoming = before.playOrder.drop(before.currentPlayIndex + 1)
        assertEquals(oldUpcoming, q.playOrder.drop(q.currentPlayIndex + 1).filter { it in oldUpcoming }, "others keep their order")
    }

    @Test
    fun entriesAddedWhileShuffledLandAtVariedPlaces() {
        val base = queue {
            add(B, bTracks)
            setShuffle(true, Random(3))
        }
        val places = (0 until 40).map { seed ->
            val q = base.copy().apply { addToQueue(C, cTracks.take(1), Random(seed), f.ids) }.check()
            q.playOrder.indexOf(q.idOf("c1")) - q.currentPlayIndex
        }.toSet()
        assertTrue(places.size > 1, "random places: $places")
        assertTrue(places.all { it >= 1 })
    }

    @Test
    fun playNextWhileShuffledGoesRightAfterCurrentInOrder() {
        val q = queue {
            add(B, bTracks)
            skipTo(idOf("a2"))
            setShuffle(true, f.random)
        }
        q.putNext(C, cTracks)
        q.check()
        assertEquals(listOf("a1", "a2", "a3", "c1", "c2", "b1", "b2"), q.trackIds(), "natural: after the current batch")
        assertEquals(listOf("c1", "c2"), q.upcomingEntries.take(2).map { it.track.id })
        // A played-next batch reads as one group even while shuffled.
        assertTrue(q.displayGroups.any { g -> g.entries.map { it.track.id } == listOf("c1", "c2") })
    }

    // Move

    @Test
    fun moveOneEntry() {
        fun moved(trackIds: List<String>, to: Int): Pair<Boolean, List<String>> {
            val q = queue { add(B, bTracks) }
            val ok = q.move(trackIds.map { q.idOf(it) }, to)
            q.check()
            return ok to q.trackIds()
        }
        // toIndex counts in the list without the moved entries.
        assertEquals(true to listOf("a2", "a3", "b1", "a1", "b2"), moved(listOf("a1"), 3))
        assertEquals(true to listOf("b2", "a1", "a2", "a3", "b1"), moved(listOf("b2"), 0))
        assertEquals(true to listOf("a2", "a3", "b1", "b2", "a1"), moved(listOf("a1"), 99), "clamped")
        assertEquals(false to listOf("a1", "a2", "a3", "b1", "b2"), moved(listOf("a2"), 1), "its own place")
        val q = queue { add(B, bTracks) }
        q.move(listOf(q.idOf("a1")), 4)
        assertEquals("a1", q.currentTrackId(), "current stays current")
    }

    @Test
    fun moveAWholeGroupKeepsItsOrderWhateverTheIdOrder() {
        val q = queue {
            add(B, bTracks)
            add(C, cTracks)
        }
        assertTrue(q.move(q.idsOf("c2", "c1"), 3))
        assertEquals(listOf("a1", "a2", "a3", "c1", "c2", "b1", "b2"), q.check().trackIds())
        assertEquals(listOf(A, C, B), q.displayGroups.map { it.group })
        assertTrue(q.move(q.idsOf("b1", "b2") + "zz", 0), "unknown IDs ignored")
        assertEquals(listOf("b1", "b2", "a1", "a2", "a3", "c1", "c2"), q.check().trackIds())
    }

    @Test
    fun moveWhileShuffledChangesPlayOrderOnly() {
        val q = queue {
            add(B, bTracks)
            setShuffle(true, f.random)
        }
        val entries = q.entries
        val last = q.playOrder.last()
        assertTrue(q.move(listOf(last), 1))
        assertEquals(entries, q.check().entries)
        assertEquals(last, q.playOrder[1])
        assertEquals(last, q.upcomingEntries.first().id)
    }

    @Test
    fun moveUnknownIdsChangesNothing() {
        assertFalse(queue().move(listOf("zz"), 0))
    }

    // Remove

    @Test
    fun removeUpcomingEntry() {
        val q = queue { add(B, bTracks) }
        val current = q.currentEntryId
        assertEquals(QueueCurrentChange.UNCHANGED, q.remove(listOf(q.idOf("a2"))))
        assertEquals(listOf("a1", "a3", "b1", "b2"), q.check().trackIds())
        assertEquals(current, q.currentEntryId)
    }

    @Test
    fun removingCurrentMovesToTheNext() {
        val q = queue {
            add(B, bTracks)
            skipTo(idOf("a3"))
        }
        val one = q.copy()
        assertEquals(QueueCurrentChange.MOVED, one.remove(listOf(one.idOf("a3"))))
        assertEquals("b1", one.check().currentTrackId())
        // The whole current group.
        assertEquals(QueueCurrentChange.MOVED, q.remove(q.idsOf("a1", "a2", "a3")))
        assertEquals("b1", q.currentTrackId())
        assertEquals(listOf("b1", "b2"), q.check().trackIds())
    }

    @Test
    fun removingCurrentSkipsUnplayableEntries() {
        val q = queue { add(B, bTracks) }
        val b1 = q.idOf("b1")
        assertEquals(QueueCurrentChange.MOVED, q.remove(q.idsOf("a1", "a2", "a3")) { it.id != b1 })
        assertEquals("b2", q.currentTrackId())
    }

    @Test
    fun removingCurrentAndEverythingAfterEnds() {
        val q = queue {
            add(B, bTracks)
            skipTo(idOf("b1"))
        }
        assertEquals(QueueCurrentChange.ENDED, q.remove(q.idsOf("b1", "b2")))
        assertEquals("a1", q.currentTrackId(), "the first remaining in play order")
        assertEquals(listOf("a1", "a2", "a3"), q.check().trackIds())
    }

    @Test
    fun removingTheLastWithRepeatAllWraps() {
        val q = queue {
            repeatMode = RepeatMode.ALL
            skipTo(idOf("a3"))
        }
        assertEquals(QueueCurrentChange.MOVED, q.remove(listOf(q.idOf("a3"))))
        assertEquals("a1", q.currentTrackId())
    }

    @Test
    fun removingEverythingEnds() {
        val q = queue { repeatMode = RepeatMode.ONE }
        assertEquals(QueueCurrentChange.ENDED, q.remove(q.entries.map { it.id }))
        assertTrue(q.check().isEmpty)
        assertNull(q.currentEntryId)
        assertEquals(RepeatMode.ONE, q.repeatMode)
    }

    @Test
    fun removingCurrentWhileShuffledFollowsPlayOrder() {
        val q = queue {
            add(B, bTracks)
            setShuffle(true, f.random)
        }
        val expected = q.playOrder[1]
        assertEquals(QueueCurrentChange.MOVED, q.remove(listOf(q.currentEntryId!!)))
        assertEquals(expected, q.check().currentEntryId)
        assertTrue(q.shuffle)
    }

    // Clear, skip

    @Test
    fun clearUpcomingKeepsOnlyTheCurrentEntry() {
        val q = queue {
            add(B, bTracks)
            skipTo(idOf("a2"))
            clearUpcoming()
        }
        assertEquals(listOf("a2"), q.trackIds())
        assertEquals(emptyList(), q.upcomingEntries)

        val s = queue {
            add(B, bTracks)
            setShuffle(true, f.random)
            clearUpcoming()
        }
        assertEquals(listOf(s.currentEntryId), s.playOrder)
    }

    @Test
    fun skipToEntry() {
        val q = queue { add(B, bTracks) }
        val order = q.playOrder
        assertTrue(q.skipTo(q.idOf("b2")))
        assertEquals("b2", q.currentTrackId())
        assertEquals(order, q.playOrder)
        assertFalse(q.skipTo("missing"))
        assertEquals("b2", q.currentTrackId())
    }

    // Next / previous / repeat

    @Test
    fun nextAndPreviousWithRepeatOff() {
        val q = queue { add(B, bTracks) }
        val seen = mutableListOf(q.currentTrackId())
        while (q.next() != null) seen += q.currentTrackId()
        assertEquals(listOf<String?>("a1", "a2", "a3", "b1", "b2"), seen)
        assertEquals("b2", q.currentTrackId(), "unchanged at the end")
        assertNull(q.next(trackEnded = true))
        assertEquals(q.idOf("b1"), q.previous())
        q.skipTo(q.idOf("a1"))
        assertNull(q.previous())
        assertEquals("a1", q.currentTrackId())
    }

    @Test
    fun nextAndPreviousSkipUnplayableEntries() {
        val q = queue { add(B, bTracks) }
        val playable: (QueueEntry) -> Boolean = { it.track.albumId != "A" || it.track.id == "a1" }
        assertEquals(q.idOf("b1"), q.next(isPlayable = playable))
        assertEquals(q.idOf("a1"), q.previous(isPlayable = playable))
        q.skipTo(q.idOf("b2"))
        assertNull(q.next(isPlayable = { false }))
        assertEquals("b2", q.currentTrackId())
    }

    @Test
    fun repeatAllWrapsTheWholeQueue() {
        val q = queue {
            add(B, bTracks)
            repeatMode = RepeatMode.ALL
            skipTo(idOf("b2"))
        }
        assertEquals(q.idOf("a1"), q.next())
        assertEquals(q.idOf("b2"), q.previous(), "and back from the start to the end")
        assertEquals(q.idOf("a1"), q.next(trackEnded = true))
        assertEquals(listOf("a2", "a3", "b1", "b2"), q.upcomingEntries.map { it.track.id })
        q.skipTo(q.idOf("b1"))
        assertEquals(listOf("b2", "a1", "a2", "a3"), q.upcomingEntries.map { it.track.id }, "earlier ones follow with Repeat All")
    }

    @Test
    fun repeatAllOnAOneEntryQueueRepeatsIt() {
        val q = PlayQueue(repeatMode = RepeatMode.ALL).apply { replace(A, aTracks.take(1), newId = f.ids) }
        assertEquals(q.currentEntryId, q.next())
        assertEquals(q.currentEntryId, q.previous())
    }

    @Test
    fun repeatAllWrapsInTheSameShuffledOrder() {
        val q = queue {
            add(B, bTracks)
            repeatMode = RepeatMode.ALL
            setShuffle(true, f.random)
        }
        val order = q.playOrder
        q.skipTo(order.last())
        assertEquals(order.first(), q.next())
        assertEquals(order, q.playOrder, "no reshuffle")
    }

    @Test
    fun repeatOneKeepsTheTrackOnlyWhenItEnds() {
        val q = queue { repeatMode = RepeatMode.ONE }
        assertEquals(q.idOf("a1"), q.next(trackEnded = true))
        assertEquals("a1", q.currentTrackId())
        assertEquals(q.idOf("a2"), q.next())
        q.skipTo(q.idOf("a3"))
        assertNull(q.next(), "a skip from the last with Repeat One: as off")
    }

    // Shuffle

    @Test
    fun shuffleRoundTrip() {
        val q = queue {
            add(B, bTracks)
            add(C, cTracks)
            skipTo(idOf("b1"))
        }
        val natural = q.entries
        val b1 = q.idOf("b1")
        q.setShuffle(true, f.random)
        q.check()
        assertTrue(q.shuffle)
        assertEquals(b1, q.currentEntryId)
        assertEquals(0, q.currentPlayIndex, "current first")
        assertEquals(6, q.upcomingEntries.size, "the whole queue, across groups and played ones")
        assertEquals(natural, q.entries, "natural order kept")
        assertNotEquals(natural.map { it.id } - b1, q.playOrder.drop(1), "seed 42 changes the order")
        val order = q.playOrder
        q.setShuffle(true, f.random)
        assertEquals(order, q.playOrder, "on again: no change")

        q.next()
        val current = q.currentEntryId
        q.setShuffle(false)
        q.check()
        assertFalse(q.shuffle)
        assertEquals(natural, q.entries)
        assertEquals(current, q.currentEntryId, "current kept")
        val index = q.entries.indexOfFirst { it.id == current }
        assertEquals(q.entries.drop(index + 1), q.upcomingEntries, "natural order after it")
    }

    @Test
    fun shuffleIsDeterministicForASeed() {
        val q = queue { add(B, bTracks) }
        val one = q.copy().apply { setShuffle(true, Random(7)) }
        val two = q.copy().apply { setShuffle(true, Random(7)) }
        assertEquals(one.playOrder, two.playOrder)
    }

    // Display groups

    @Test
    fun displayGroupsAreBatchRunsInPlayOrder() {
        val q = queue {
            add(B, bTracks)
            add(A, aTracks.take(1))
        }
        val groups = q.displayGroups
        assertEquals(listOf(A, B, A), groups.map { it.group })
        assertEquals(listOf(3, 2, 1), groups.map { it.entries.size })
        assertEquals(400.0, groups[1].durationSeconds)
        assertEquals(groups[1].entries.first().id, groups[1].id)
        assertEquals(q.entries[3].batchId, groups[1].batchId)
    }

    @Test
    fun displayGroupsWhileShuffledFollowPlayOrder() {
        val q = queue {
            add(B, bTracks)
            setShuffle(true, f.random)
        }
        assertEquals(q.playOrder, q.displayGroups.flatMap { g -> g.entries.map { it.id } })
        assertTrue(q.displayGroups.all { g -> g.entries.all { it.batchId == g.batchId && it.group == g.group } })
    }

    // Times

    @Test
    fun remainingTimes() {
        // a 100 s each, b 200 s, c 50 s; playing a2 at 30 s.
        val q = queue {
            add(B, bTracks)
            add(C, cTracks)
            skipTo(idOf("a2"))
        }
        assertEquals(70.0 + 100.0, q.currentGroupRemaining(30.0))
        assertEquals(70.0 + 100.0 + 400.0 + 100.0, q.queueRemaining(30.0))
        assertEquals(100.0, q.currentGroupRemaining(500.0), "never negative")
        q.skipTo(q.idOf("c2"))
        assertEquals(40.0, q.currentGroupRemaining(10.0))
        assertEquals(40.0, q.queueRemaining(10.0))
        q.repeatMode = RepeatMode.ALL
        assertEquals(40.0, q.queueRemaining(10.0), "no wrap")
    }

    @Test
    fun remainingTimesWhileShuffledFollowPlayOrder() {
        val q = queue {
            add(B, bTracks)
            setShuffle(true, f.random)
        }
        assertEquals(700.0, q.queueRemaining(0.0))
        val current = q.currentEntry!!
        val run = q.entriesInPlayOrder.drop(1).takeWhile { it.batchId == current.batchId }.sumOf { it.track.durationSeconds }
        assertEquals(current.track.durationSeconds + run, q.currentGroupRemaining(0.0))
    }

    // Maniac Mode

    @Test
    fun maniacModeAllowsOnlyReplacingAndAdding() {
        val allowed = QueueEdit.entries.filter { PlayQueue.canEdit(it, maniacMode = true) }
        assertEquals(listOf(QueueEdit.REPLACE, QueueEdit.ADD_TO_QUEUE), allowed)
        assertTrue(QueueEdit.entries.all { PlayQueue.canEdit(it, maniacMode = false) })
    }

    @Test
    fun theQueueStillAdvancesToTheNextAlbum() {
        val q = queue {
            add(B, bTracks)
            skipTo(idOf("a3"))
        }
        assertEquals(q.idOf("b1"), q.next(trackEnded = true))
    }

    // Snapshot repair

    @Test
    fun snapshotRoundTripAndRepair() {
        val q = queue {
            add(B, bTracks)
            repeatMode = RepeatMode.ALL
            setShuffle(true, f.random)
        }
        assertEquals(q, PlayQueue.from(q.snapshot(5.0)))
        assertEquals(0.0, q.snapshot(-3.0).positionSeconds)
        assertEquals(0.0, q.snapshot(Double.NaN).positionSeconds)

        val snap = q.snapshot(0.0)
        val dup = snap.copy(entries = snap.entries + snap.entries.first().copy(track = QueueFixture.track("zz", "Z")))
        assertEquals(q.entries, PlayQueue.from(dup).entries, "later duplicates dropped")

        val badOrder = PlayQueue.from(snap.copy(playOrder = q.playOrder.drop(1) + "zz", currentEntryId = "gone"))
        badOrder.check()
        assertFalse(badOrder.shuffle, "a bad play order: natural, shuffle off")
        assertEquals(q.entries.first().id, badOrder.currentEntryId, "unknown current: the first in play order")

        val unshuffledButOutOfOrder = PlayQueue.from(snap.copy(shuffle = false))
        assertEquals(q.entries.map { it.id }, unshuffledButOutOfOrder.check().playOrder)
        assertEquals(q.currentEntryId, unshuffledButOutOfOrder.currentEntryId)
    }

    // Tracks and groups

    @Test
    fun queueTrackRebuildsWhatStreamingNeeds() {
        val t = QueueTrack(
            id = "t1", title = "Song", artist = "Band", album = "Record", albumId = "al", artItemId = "t1",
            durationSeconds = 241.0, trackNumber = 4, discNumber = 2, container = "flac", codec = "flac", bitrate = 905_000,
            sampleRate = 44_100, bitDepth = 16, artistId = "ar",
        )
        val jt = t.toJellyfinTrack()
        assertEquals(AudioFormat.FLAC, AudioFormat.source(jt))
        assertEquals(241L, jt.durationSeconds)
        assertEquals(2, jt.discNumber)
        assertTrue(jt.hasOwnArt)
        val pt = t.toPlaybackTrack()
        assertEquals("FLAC 905 kbps", pt.qualityLabel)
        assertEquals("ar", pt.albumArtistId)
        assertEquals("44.1kHz/16-bit", pt.sampleRateLabel)
        assertEquals(t, QueueTrack.from(pt, jt.mediaSource, discNumber = 2))
        assertNull(QueueTrack(id = "x", title = "X", artist = "", album = "", albumId = "al", durationSeconds = 1.0).toJellyfinTrack().mediaSource)
    }

    @Test
    fun theCodecTellsAacFromAlacInAnM4a() {
        val base = QueueTrack(id = "t", title = "T", artist = "", album = "", albumId = "al", durationSeconds = 1.0, container = "m4a")
        assertEquals(AudioFormat.AAC, AudioFormat.source(base.copy(codec = "aac").toJellyfinTrack()))
        assertEquals(AudioFormat.ALAC, AudioFormat.source(base.copy(codec = "alac").toJellyfinTrack()))
        val fromServer = QueueTrack.from(base.toPlaybackTrack(), gg.dekiru.musicplayer.data.models.MediaSource(container = "m4a", codecs = "AAC"))
        assertEquals("aac", fromServer.codec, "lower-cased")
    }

    @Test
    fun tracksGroupIsNamedByTheFirstTrack() {
        assertEquals(QueueGroup("b1", QueueGroupKind.TRACKS, "Track b1", "Artist B", "B"), QueueGroup.tracks(bTracks))
        assertNull(QueueGroup.tracks(emptyList()))
    }
}
