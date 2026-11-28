package gg.dekiru.musicplayer.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The queue screens' pure parts: when Play asks, the end times, the editor's rows and move maths, Maniac Mode. */
class QueueEditingTest {

    private var ids = 0
    private fun newId() = "id${ids++}"

    private fun group(id: String, artist: String? = "Band") = QueueGroup(id, QueueGroupKind.ALBUM, "Album $id", artist, id)

    private fun tracks(album: String, count: Int, artist: String = "Band") = (1..count).map {
        QueueTrack(id = "$album$it", title = "$album $it", artist = artist, album = "Album $album", albumId = album, durationSeconds = 60.0 * it)
    }

    /** A1 A2 A3 playing A1, then B1 B2 and C1 added. */
    private fun threeAlbums(): PlayQueue {
        val q = PlayQueue()
        q.replace(group("A"), tracks("A", 3), newId = ::newId)
        q.addToQueue(group("B"), tracks("B", 2), newId = ::newId)
        q.addToQueue(group("C"), tracks("C", 1), newId = ::newId)
        return q
    }

    private fun PlayQueue.trackIds() = entriesInPlayOrder.map { it.track.id }
    private fun PlayQueue.idOf(track: String) = entries.first { it.track.id == track }.id

    // When Play asks

    @Test
    fun playAsksOnlyWithAnotherGroupQueued() {
        val q = PlayQueue()
        assertFalse(q.asksBeforeReplacing, "empty")
        q.replace(group("A"), tracks("A", 3), newId = ::newId)
        assertFalse(q.asksBeforeReplacing, "only the playing album")
        q.addToQueue(group("B"), tracks("B", 1), newId = ::newId)
        assertTrue(q.asksBeforeReplacing)
        q.skipTo(q.idOf("B1"))
        assertFalse(q.asksBeforeReplacing, "B is all that's left")
        q.repeatMode = RepeatMode.ALL
        assertTrue(q.asksBeforeReplacing, "Repeat All plays A again")
    }

    @Test
    fun theQueueEndShowsOnlyWithAnotherGroupStillToPlay() {
        val q = threeAlbums()
        assertTrue(q.showsQueueEnd)
        q.skipTo(q.idOf("C1"))
        assertFalse(q.showsQueueEnd)
        q.repeatMode = RepeatMode.ALL
        assertFalse(q.showsQueueEnd, "the end doesn't wrap")
        val shuffled = threeAlbums()
        shuffled.setShuffle(true, kotlin.random.Random(1))
        assertFalse(shuffled.showsQueueEnd, "shuffled: just Ends:")
    }

    // End times

    @Test
    fun endTimesText() {
        assertEquals("09:05", EndTimes.clock(9, 5))
        assertEquals("Ends: 13:02", EndTimes.text("13:02", null))
        assertEquals("Ends: 13:02", EndTimes.text("13:02", null, short = true))
        assertEquals("Album ends 13:02 · Queue ends 15:40", EndTimes.text("13:02", "15:40"))
        assertEquals("Queue ends 15:40", EndTimes.text("13:02", "15:40", short = true), "narrow: the album's end goes first")
        assertEquals("Playlist ends 13:02 · Queue ends 15:40", EndTimes.text("13:02", "15:40", groupLabel = EndTimes.groupLabel(QueueGroupKind.PLAYLIST, 9)))
        assertEquals("Album ends", EndTimes.groupLabel(QueueGroupKind.ALBUM, 1))
        assertEquals("Song ends", EndTimes.groupLabel(QueueGroupKind.TRACKS, 1))
        assertEquals("Songs end", EndTimes.groupLabel(QueueGroupKind.TRACKS, 2))
    }

    // Rows

    @Test
    fun rowsGroupUnderHeadersWithThePlayingTrackPinned() {
        val q = threeAlbums()
        val editor = QueueEditor.from(q)
        val shape = editor.rows.map {
            when (it) {
                is QueueHeaderRow -> "[${it.group.id}]"
                is QueueTrackRow -> it.entry.track.id + if (it.isCurrent) "*" else ""
            }
        }
        assertEquals(listOf("A1*", "[A]", "A2", "A3", "[B]", "B1", "B2", "[C]", "C1"), shape)
        val a = editor.rows[1] as QueueHeaderRow
        assertEquals(120.0 + 180, a.seconds, "the sum of its tracks")
        assertEquals(listOf(q.idOf("A2"), q.idOf("A3")), a.entryIds)
        val b = editor.rows.filterIsInstance<QueueHeaderRow>().first { it.group.id == "B" }
        assertEquals(180.0, b.seconds)
        assertEquals(1, b.runIndex)
        assertTrue(editor.runCanMove(0))
        // Played entries aren't shown.
        q.skipTo(q.idOf("B1"))
        assertEquals(listOf("B1", "B2", "C1"), QueueEditor.from(q).rows.filterIsInstance<QueueTrackRow>().map { it.entry.track.id })
    }

    @Test
    fun theArtistShowsOnlyWhenItIsntTheGroups() {
        val q = PlayQueue()
        q.replace(group("Z"), tracks("Z", 1), newId = ::newId)
        q.addToQueue(group("A", artist = "Various Artists"), tracks("A", 1, artist = "Someone"), newId = ::newId)
        q.addToQueue(group("B"), tracks("B", 1), newId = ::newId)
        val rows = QueueEditor.from(q).rows.filterIsInstance<QueueTrackRow>().filter { !it.isCurrent }
        assertEquals(listOf(true, false), rows.map { it.showArtist })
    }

    @Test
    fun shuffledIsOneListWithAlbums() {
        val q = threeAlbums()
        q.setShuffle(true, kotlin.random.Random(3))
        val editor = QueueEditor.from(q)
        assertTrue(editor.rows.drop(1).all { it is QueueTrackRow && it.showAlbum })
        assertEquals(q.trackIds(), editor.rows.map { (it as QueueTrackRow).entry.track.id })
        assertTrue((editor.rows.first() as QueueTrackRow).isCurrent)
        assertFalse(editor.runCanMove(1), "no groups while shuffled")
    }

    @Test
    fun nothingQueuedAfterTheLastTrack() {
        val q = PlayQueue()
        q.replace(group("A"), tracks("A", 1), newId = ::newId)
        val editor = QueueEditor.from(q)
        assertTrue(editor.upcoming.isEmpty())
        assertEquals(1, editor.rows.size, "just the playing track")
        assertEquals("Nothing queued. Use Play Next or Add to Queue on any album.", QueueEditor.EMPTY_NOTE)
    }

    // Move maths

    @Test
    fun trackMovesUpAndDown() {
        val q = threeAlbums()
        val editor = QueueEditor.from(q)
        val a2 = q.idOf("A2")
        assertNull(editor.trackUpSlot(a2), "already first after the playing track")
        val b1 = q.idOf("B1")
        q.move(listOf(b1), QueueEditor.toIndex(q.currentPlayIndex, editor.trackUpSlot(b1)!!))
        assertEquals(listOf("A1", "A2", "B1", "A3", "B2", "C1"), q.trackIds())
        val e2 = QueueEditor.from(q)
        q.move(listOf(b1), QueueEditor.toIndex(q.currentPlayIndex, e2.trackDownSlot(b1)!!))
        assertEquals(listOf("A1", "A2", "A3", "B1", "B2", "C1"), q.trackIds())
        assertNull(QueueEditor.from(q).trackDownSlot(q.idOf("C1")), "already last")
        assertEquals("A1", q.currentEntry?.track?.id)
    }

    @Test
    fun groupMovesUpAndDown() {
        val q = threeAlbums()
        val editor = QueueEditor.from(q)
        // Run 0 is the rest of A, B run 1, C run 2.
        assertNull(editor.groupUpSlot(0), "already first")
        assertNull(editor.groupDownSlot(2), "already last")
        val c = editor.runs[2].map { it.id }
        q.move(c, QueueEditor.toIndex(q.currentPlayIndex, editor.groupUpSlot(2)!!))
        assertEquals(listOf("A1", "A2", "A3", "C1", "B1", "B2"), q.trackIds())
        val e2 = QueueEditor.from(q)
        q.move(c, QueueEditor.toIndex(q.currentPlayIndex, e2.groupDownSlot(1)!!))
        assertEquals(listOf("A1", "A2", "A3", "B1", "B2", "C1"), q.trackIds())
        // B above the rest of A: it plays right after the playing track.
        val e3 = QueueEditor.from(q)
        q.move(e3.runs[1].map { it.id }, QueueEditor.toIndex(q.currentPlayIndex, e3.groupUpSlot(1)!!))
        assertEquals(listOf("A1", "B1", "B2", "A2", "A3", "C1"), q.trackIds())
        assertEquals("A1", q.currentEntry?.track?.id)
    }

    @Test
    fun aDraggedGroupSnapsBetweenGroups() {
        // Two other groups spanning 0–100 and 100–300.
        val tops = listOf(0f, 100f)
        val bottoms = listOf(100f, 300f)
        // Down: once its middle passes a group's top it goes after it.
        assertEquals(1, QueueEditor.groupDropSlot(tops, bottoms, 50f, movingDown = true))
        assertEquals(2, QueueEditor.groupDropSlot(tops, bottoms, 120f, movingDown = true))
        // Up: once its middle passes a group's bottom it goes before it.
        assertEquals(0, QueueEditor.groupDropSlot(tops, bottoms, 50f, movingDown = false))
        assertEquals(1, QueueEditor.groupDropSlot(tops, bottoms, 120f, movingDown = false))
        val q = threeAlbums()
        val editor = QueueEditor.from(q)
        assertEquals(0, editor.groupSlot(2, unitSlot = 0))
        assertEquals(3, editor.groupSlot(1, unitSlot = 2), "after A2 A3 and C1")
        q.move(editor.runs[1].map { it.id }, QueueEditor.toIndex(q.currentPlayIndex, 3))
        assertEquals(listOf("A1", "A2", "A3", "C1", "B1", "B2"), q.trackIds())
    }

    @Test
    fun aTrackDropsByItsMiddle() {
        assertEquals(0, QueueEditor.dropSlot(listOf(100f, 200f), 50f))
        assertEquals(1, QueueEditor.dropSlot(listOf(100f, 200f), 150f))
        assertEquals(2, QueueEditor.dropSlot(listOf(100f, 200f), 250f))
    }

    // Maniac Mode

    @Test
    fun maniacModeMakesTheQueueReadOnly() {
        assertTrue(QueueEditor.isReadOnly(maniacMode = true))
        assertFalse(QueueEditor.isReadOnly(maniacMode = false))
        // Adding at the end is still allowed; Play Next is not.
        assertFalse(PlayQueue.canEdit(QueueEdit.PLAY_NEXT, maniacMode = true))
        assertTrue(PlayQueue.canEdit(QueueEdit.PLAY_NEXT, maniacMode = false))
        assertTrue(PlayQueue.canEdit(QueueEdit.ADD_TO_QUEUE, maniacMode = true))
        assertFalse(PlayQueue.canEdit(QueueEdit.CLEAR_UPCOMING, maniacMode = true))
        assertEquals("Maniac Mode: the queue plays in order.", QueueEditor.MANIAC_NOTE)
    }
}
