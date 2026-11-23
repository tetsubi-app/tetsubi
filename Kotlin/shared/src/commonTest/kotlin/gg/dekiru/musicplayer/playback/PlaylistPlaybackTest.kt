package gg.dekiru.musicplayer.playback

import gg.dekiru.musicplayer.data.api.FakeServer
import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import gg.dekiru.musicplayer.data.api.path
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinPlaylist
import gg.dekiru.musicplayer.data.models.JellyfinTrack
import gg.dekiru.musicplayer.data.models.MediaSource
import gg.dekiru.musicplayer.data.models.PlaylistEntry
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Playing a playlist: a queue of songs from different albums, where the same song may come twice. */
class PlaylistPlaybackTest {

    private val playlist = JellyfinPlaylist(id = "pl", name = "Mix")

    /** Each song's own track number is its row's place + 1 here, so a queue's numbers show which rows it holds. */
    private fun entry(position: Int, id: String, album: String, container: String = "flac") = PlaylistEntry(
        position = position,
        entryId = id,
        track = JellyfinTrack(
            id = id, name = "Song $id", indexNumber = position + 1, runTimeTicks = 600_000_000L, album = "Album $album", albumId = album,
            mediaSource = MediaSource(container = container, codecs = container), normalizationGain = -6.0,
        ),
        albumName = "Album $album",
        albumId = album,
        albumArtist = "Band $album",
    )

    /** Five rows from three albums; rows 1 and 4 are the same song. */
    private val entries = listOf(
        entry(0, "a1", "A"), entry(1, "b1", "B"), entry(2, "c1", "C"), entry(3, "a2", "A"), entry(4, "b1", "B"),
    )

    private class Setup(val server: FakeServer, val engine: FakeEngine, val player: PlayerController)

    private fun TestScope.setup(metered: Boolean = false, maniac: Boolean = false): Setup {
        val server = FakeServer { request ->
            when (request.path) {
                "/Items" -> json("""{"Items":[{"Id":"a1","Name":"Song a1","IndexNumber":1,"AlbumId":"A"}]}""")
                else -> json("")
            }
        }
        val engine = FakeEngine()
        val settings = PlaybackSettings(InMemoryPreferences()).also { it.maniacMode = maniac }
        val player = PlayerController(
            server.api(token = "tok", userId = "u1"), engine, settings, backgroundScope,
            isExpensiveNetwork = { metered }, random = Random(1),
        )
        return Setup(server, engine, player)
    }

    private fun Setup.reports() = server.requests.filter { it.path.startsWith("/Sessions/Playing") }
        .map { it.path.removePrefix("/Sessions/Playing").ifEmpty { "start" } }

    @Test
    fun playsFromTheFirstSongWithEachSongsOwnAlbum() = runTest {
        val s = setup()
        s.player.play(playlist, entries)
        settle()
        assertEquals(listOf("a1", "b1", "c1", "a2", "b1"), s.engine.items.map { it.id })
        assertEquals(0, s.engine.index)
        assertTrue(s.engine.playing)
        assertTrue(s.player.isNowPlayingOpen)
        assertEquals(listOf("Album A", "Album B", "Album C", "Album A", "Album B"), s.player.queue.map { it.album })
        assertEquals(listOf("A", "B", "C", "A", "B"), s.player.queue.map { it.albumId })
        assertEquals(listOf("A", "B", "C", "A", "B"), s.player.queue.map { it.artItemId })
        assertEquals(listOf("Band A", "Band B", "Band C", "Band A", "Band B"), s.player.queue.map { it.artist })
        assertEquals(listOf(1, 2, 3, 4, 5), s.player.queue.map { it.trackNumber }, "each song's own track number")
        assertEquals(listOf("Album A", "Album B", "Album C", "Album A", "Album B"), s.engine.items.map { it.album })
        assertTrue(s.engine.items[1].artworkUrl.contains("/Items/B/Images/Primary"), s.engine.items[1].artworkUrl)
        assertEquals(listOf("start"), s.reports())
        assertTrue(s.server.requests.none { it.path == "/Items" }, "the loaded tracks are used; nothing is fetched")
    }

    @Test
    fun aSongWithoutATrackNumberTakesItsPlaceInTheQueue() = runTest {
        val s = setup()
        fun bare(position: Int, id: String) = PlaylistEntry(position = position, entryId = id, track = JellyfinTrack(id = id, name = "Loose", runTimeTicks = 600_000_000L))
        s.player.play(playlist, listOf(entry(0, "a1", "A").let { it.copy(track = it.track.copy(indexNumber = 9)) }, bare(1, "x"), bare(2, "y")))
        settle()
        assertEquals(listOf(9, 2, 3), s.player.queue.map { it.trackNumber })
    }

    @Test
    fun aTrackWithItsOwnPictureKeepsIt() = runTest {
        val s = setup()
        val own = entries[0].copy(track = entries[0].track.copy(hasOwnArt = true))
        s.player.play(playlist, listOf(own, entries[1]))
        settle()
        assertEquals(listOf("a1", "B"), s.player.queue.map { it.artItemId })
        assertEquals("A", s.player.queue[0].albumId)
    }

    @Test
    fun startsAtAPosition() = runTest {
        val s = setup()
        s.player.play(playlist, entries, startingAt = 4, openNowPlaying = false)
        settle()
        assertEquals(4, s.engine.index)
        assertEquals(4, s.player.currentIndex)
        assertEquals("b1", s.player.currentTrack?.id)
        assertEquals(4, s.player.currentPlace, "the second of the two rows, not the first")
        assertFalse(s.player.isNowPlayingOpen)
        assertEquals(5, s.engine.items.size, "the whole playlist is queued")
    }

    @Test
    fun aPositionOutsideThePlaylistStartsAtTheTop() = runTest {
        val s = setup()
        s.player.play(playlist, entries, startingAt = 9)
        settle()
        assertEquals(0, s.player.currentIndex)
    }

    @Test
    fun theSameSongTwicePlaysTwice() = runTest {
        val s = setup()
        s.player.play(playlist, entries, startingAt = 3)
        settle()
        assertEquals(2, s.player.queue.count { it.id == "b1" })
        assertEquals(2, s.engine.items.count { it.id == "b1" })
        s.engine.finishTrack()
        settle()
        assertEquals("b1", s.player.currentTrack?.id)
        assertEquals(4, s.player.currentPlace)
        assertEquals(5, s.player.currentTrack?.trackNumber)
        s.player.skipTo(1)
        settle()
        assertEquals("b1", s.player.currentTrack?.id)
        assertEquals(1, s.player.currentPlace)
        assertEquals(listOf("start", "/Stopped", "start", "/Stopped", "start"), s.reports(), "each row's play is reported")
    }

    @Test
    fun shuffleKeepsEveryRowAndStartsWhereAsked() = runTest {
        val s = setup()
        s.player.changeShuffle(true)
        s.player.play(playlist, entries, startingAt = 2)
        settle()
        assertEquals("c1", s.player.queue.first().id)
        assertEquals(0, s.player.currentIndex)
        assertEquals(2, s.player.currentPlace)
        assertEquals(listOf("a1", "a2", "b1", "b1", "c1"), s.player.queue.map { it.id }.sorted(), "both rows of the song are in")
        assertEquals(listOf(1, 2, 3, 4, 5), s.player.queue.map { it.trackNumber }.sorted())
        assertEquals(s.player.queue.map { it.id }, s.engine.items.map { it.id })
        // Each entry still has its own album.
        s.player.queue.forEach { assertEquals(if (it.id.startsWith("a")) "A" else if (it.id.startsWith("b")) "B" else "C", it.albumId) }
    }

    @Test
    fun shuffleFromPlayMixesTheOrder() = runTest {
        val s = setup()
        s.player.changeShuffle(true)
        val many = (0 until 30).map { entry(it, "t$it", "A") }
        s.player.play(playlist, many)
        settle()
        assertEquals(many.map { it.track.id }.sorted(), s.player.queue.map { it.id }.sorted())
        assertTrue(s.player.queue.map { it.id } != many.map { it.track.id })
    }

    @Test
    fun repeatAppliesAsForAnAlbum() = runTest {
        val s = setup()
        s.player.changeRepeatMode(RepeatMode.ALL)
        s.player.play(playlist, entries)
        settle()
        assertEquals(RepeatMode.ALL, s.engine.repeat)
    }

    @Test
    fun maniacModeStartsAtTheFirstSongInOrder() = runTest {
        val s = setup(maniac = true)
        s.player.play(playlist, entries, startingAt = 3)
        settle()
        assertEquals(0, s.player.currentIndex)
        assertEquals(listOf("a1", "b1", "c1", "a2", "b1"), s.player.queue.map { it.id })
        assertFalse(s.player.shuffle)
        s.player.skipToNext()
        s.player.skipTo(3)
        assertEquals(0, s.player.currentIndex)
    }

    @Test
    fun maniacModeUnshufflesTheRestOfAPlaylistWithASongTwice() = runTest {
        val s = setup()
        s.player.changeShuffle(true)
        s.player.play(playlist, entries, startingAt = 2)
        settle()
        s.engine.finishTrack()
        settle()
        val playing = s.player.currentPlace
        s.player.changeManiacMode(true)
        // The whole queue goes back in playlist order (with the listening queue; it used to keep what had played first).
        assertEquals(listOf(1, 2, 3, 4, 5), s.player.queue.map { it.trackNumber }, "in playlist order, no row lost or doubled")
        assertEquals(playing, s.player.currentPlace, "the playing row carries on")
        assertEquals(s.player.queue.map { it.id }, s.engine.items.map { it.id })
        assertEquals(s.player.queue.map { it.album }, s.engine.items.map { it.album })
        assertEquals(s.player.currentTrack?.trackNumber, s.player.currentPlace?.plus(1))
    }

    @Test
    fun theEndOfThePlaylistRewindsToItsFirstSong() = runTest {
        val s = setup()
        s.player.play(playlist, entries, startingAt = 4)
        settle()
        s.engine.listener?.onEnded()
        settle()
        assertFalse(s.player.isNowPlayingOpen)
        assertFalse(s.player.isPlaying)
        assertFalse(s.engine.playing)
        assertEquals("a1", s.player.currentTrack?.id)
        assertEquals(0, s.engine.index)
        assertEquals(0, s.player.currentPlace)
        assertTrue(s.player.isPlaying(playlist), "the bar keeps the playlist")
        assertEquals(listOf("start", "/Stopped"), s.reports())
    }

    @Test
    fun queueSourceIsThePlaylistAndNoAlbumIsMarkedPlaying() = runTest {
        val s = setup()
        assertNull(s.player.queueSourceId)
        s.player.play(playlist, entries)
        settle()
        assertEquals("pl", s.player.queueSourceId)
        assertTrue(s.player.isPlaying(playlist))
        assertFalse(s.player.isPlaying(JellyfinPlaylist(id = "other")))
        assertFalse(s.player.isPlaying(JellyfinAlbum(id = "A", name = "Album A")), "one of its songs is playing, but not the album")
        assertFalse(s.player.isPlaying(JellyfinAlbum(id = "B", name = "Album B")))
    }

    @Test
    fun anAlbumQueueIsItsAlbumNotAPlaylist() = runTest {
        val s = setup()
        val album = JellyfinAlbum(id = "A", name = "Album A")
        s.player.play(album)
        settle()
        assertEquals("A", s.player.queueSourceId)
        assertTrue(s.player.isPlaying(album))
        assertFalse(s.player.isPlaying(JellyfinPlaylist(id = "pl")))
        // A playlist then takes the queue over.
        s.player.play(playlist, entries)
        settle()
        assertFalse(s.player.isPlaying(album))
        assertEquals("pl", s.player.queueSourceId)
        s.player.stop()
        assertNull(s.player.queueSourceId)
        assertNull(s.player.currentPlace)
    }

    @Test
    fun anEmptyPlaylistSaysSo() = runTest {
        val s = setup()
        s.player.play(playlist, emptyList())
        settle()
        assertEquals("This playlist has no songs.", s.player.lastError)
        assertTrue(s.engine.items.isEmpty())
        assertFalse(s.player.isNowPlayingOpen)
    }

    @Test
    fun alacIsConvertedToFlacAsForAnAlbum() = runTest {
        val s = setup()
        s.player.play(playlist, listOf(entry(0, "x1", "A", container = "alac"), entries[1]))
        settle()
        assertTrue(s.engine.items[0].url.contains("/Audio/x1/universal"), s.engine.items[0].url)
        assertTrue(s.engine.items[0].url.contains("flac"), s.engine.items[0].url)
        assertFalse(s.engine.items[1].url.contains("/universal"), s.engine.items[1].url)
    }

    @Test
    fun mobileDataCapAppliesAsForAnAlbum() = runTest {
        val s = setup(metered = true)
        s.player.play(playlist, entries)
        settle()
        assertEquals("AAC 256 kbps", s.player.currentTrack?.qualityLabel)
        val url = s.engine.items[1].url
        assertTrue("/Audio/b1/universal" in url && "AudioCodec=aac" in url && "MaxStreamingBitrate=256000" in url, url)
    }
}
