package gg.dekiru.musicplayer.playback

import gg.dekiru.musicplayer.data.api.FakeServer
import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import gg.dekiru.musicplayer.data.api.path
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinArtist
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Play all and Shuffle on an artist: the order, the cap and the queue rules. */
class ArtistPlaybackTest {
    private val artist = JellyfinArtist(id = "ar", name = "Band")

    private fun album(id: String, year: Int?, name: String = "Album $id") = JellyfinAlbum(id = id, name = name, productionYear = year)

    // Pure rules

    @Test
    fun albumsPlayOldestFirstUnknownLastTiesByTitle() {
        val albums = listOf(
            album("new", 2010), album("none", null), album("b", 1999, "B side"), album("old", 1980), album("a", 1999, "A side"),
        )
        assertEquals(listOf("old", "a", "b", "new", "none"), ArtistTracks.albumOrder(albums).map { it.id })
    }

    @Test
    fun aFullReleaseDateBeatsTheYearWithinTheYear() {
        val late = JellyfinAlbum(id = "late", name = "Late", productionYear = 2001, premiereDate = "2001-11-05T00:00:00.0000000Z")
        val early = JellyfinAlbum(id = "early", name = "Zed", productionYear = 2001, premiereDate = "2001-02-01T00:00:00.0000000Z")
        assertEquals(listOf("early", "late"), ArtistTracks.albumOrder(listOf(late, early)).map { it.id })
    }

    @Test
    fun theCapCutsAndSaysSo() {
        val (kept, capped) = ArtistTracks.limit((1..7).toList(), cap = 5)
        assertEquals(listOf(1, 2, 3, 4, 5), kept)
        assertTrue(capped)
        val (all, cappedNone) = ArtistTracks.limit((1..5).toList(), cap = 5)
        assertEquals(5, all.size)
        assertFalse(cappedNone)
        assertEquals(500, ArtistTracks.CAP)
    }

    @Test
    fun moreAlbumsAreFetchedOnlyWhileWithinTheCap() {
        assertTrue(ArtistTracks.needsMore(0))
        assertTrue(ArtistTracks.needsMore(500))
        assertFalse(ArtistTracks.needsMore(501))
    }

    @Test
    fun shuffledOrderIsAPermutationThatDiffers() {
        val tracks = (1..30).toList()
        val (shuffled, start) = planQueue(tracks, null, shuffle = true, Random(3))
        assertEquals(0, start)
        assertEquals(tracks, shuffled.sorted())
        assertNotEquals(tracks, shuffled)
    }

    // The player

    private class Setup(val server: FakeServer, val engine: FakeEngine, val player: PlayerController)

    /** Each album has `perAlbum` tracks, "<album>-<n>". */
    private fun TestScope.setup(perAlbum: Int = 3, maniac: Boolean = false): Setup {
        val server = FakeServer { request ->
            val parent = request.url.parameters["ParentId"]
            if (request.path == "/Items" && parent != null) {
                val tracks = (1..perAlbum).joinToString(",") { """{"Id":"$parent-$it","Name":"Song $parent $it","IndexNumber":$it,"AlbumId":"$parent","RunTimeTicks":600000000}""" }
                json("""{"Items":[$tracks]}""")
            } else {
                json("")
            }
        }
        val engine = FakeEngine()
        val settings = PlaybackSettings(InMemoryPreferences()).also { it.maniacMode = maniac }
        val player = PlayerController(server.api(token = "tok", userId = "u1"), engine, settings, backgroundScope, random = Random(1))
        return Setup(server, engine, player)
    }

    private val albums = listOf(album("b", 2010), album("a", 1990), album("c", 2000))

    @Test
    fun playAllQueuesAlbumsOldestFirstInTrackOrder() = runTest {
        val s = setup()
        s.player.play(artist, albums)
        settle()
        assertEquals(listOf("a-1", "a-2", "a-3", "c-1", "c-2", "c-3", "b-1", "b-2", "b-3"), s.engine.items.map { it.id })
        assertEquals(0, s.engine.index)
        assertTrue(s.engine.playing)
        assertTrue(s.player.isNowPlayingOpen)
        assertFalse(s.player.shuffle)
        assertEquals("a-1", s.player.currentTrack?.id)
        assertEquals((0..8).toList(), s.player.queue.indices.toList())
    }

    @Test
    fun shuffleTurnsShuffleOnAndPlaysAnotherOrder() = runTest {
        val s = setup()
        s.player.play(artist, albums, shuffled = true)
        settle()
        val inOrder = listOf("a-1", "a-2", "a-3", "c-1", "c-2", "c-3", "b-1", "b-2", "b-3")
        assertTrue(s.player.shuffle)
        assertEquals(inOrder.sorted(), s.engine.items.map { it.id }.sorted())
        assertNotEquals(inOrder, s.engine.items.map { it.id })
        assertTrue(s.engine.playing)
    }

    @Test
    fun maniacModeNeverShufflesAndPlaysInOrder() = runTest {
        val s = setup(maniac = true)
        s.player.play(artist, albums, shuffled = true)
        settle()
        assertFalse(s.player.shuffle)
        assertEquals(listOf("a-1", "a-2", "a-3", "c-1", "c-2", "c-3", "b-1", "b-2", "b-3"), s.engine.items.map { it.id })
    }

    @Test
    fun theQueueIsCappedAndTheNoticeSaysSo() = runTest {
        val s = setup(perAlbum = 300)
        s.player.play(artist, albums)
        settle()
        assertEquals(500, s.engine.items.size)
        assertEquals(ArtistTracks.cappedMessage(), s.player.queueNotice?.message)
        // The third album was never fetched.
        assertEquals(2, s.server.requests.count { it.path == "/Items" })
    }

    @Test
    fun anArtistWithNoTracksSaysSo() = runTest {
        val s = setup(perAlbum = 0)
        s.player.play(artist, albums)
        settle()
        assertTrue(s.engine.items.isEmpty())
        assertEquals("This artist has no tracks.", s.player.lastError)
    }
}
