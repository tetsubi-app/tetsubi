package gg.dekiru.musicplayer.android.auto

import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinArtist
import gg.dekiru.musicplayer.data.models.JellyfinPerson
import gg.dekiru.musicplayer.data.models.JellyfinPlaylist
import gg.dekiru.musicplayer.data.models.JellyfinTrack
import gg.dekiru.musicplayer.data.models.PlaylistEntry
import gg.dekiru.musicplayer.downloads.DownloadedAlbum
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoIdTest {

    @Test
    fun everyKindReadsBackAsWritten() {
        val ids = listOf(
            AutoId.Root,
            AutoId.Tab(AutoTab.HOME),
            AutoId.Tab(AutoTab.DOWNLOADS),
            AutoId.Artist("a1"),
            AutoId.Album(listOf("d1")),
            AutoId.Album(listOf("d1", "d2", "d3")),
            AutoId.Playlist("p1"),
            AutoId.PlayAlbum(listOf("d1", "d2"), shuffled = false),
            AutoId.PlayAlbum(listOf("d1"), shuffled = true),
            AutoId.PlayPlaylist("p1", shuffled = false),
            AutoId.PlayPlaylist("p1", shuffled = true),
            AutoId.AlbumTrack(listOf("d1", "d2"), "t9"),
            AutoId.PlaylistSong("p1", 0),
            AutoId.PlaylistSong("p1", 12),
            AutoId.Downloaded("d1"),
            AutoId.Note("Notsignedin"),
        )
        for (id in ids) assertEquals(id, AutoId.parse(id.format()), id.format())
    }

    @Test
    fun formatsAsDocumented() {
        assertEquals("root", AutoId.Root.format())
        assertEquals("tab:home", AutoId.Tab(AutoTab.HOME).format())
        assertEquals("album:a,b", AutoId.Album(listOf("a", "b")).format())
        assertEquals("shuffle:album:a", AutoId.PlayAlbum(listOf("a"), shuffled = true).format())
        assertEquals("play:playlist:p", AutoId.PlayPlaylist("p", shuffled = false).format())
        assertEquals("track:a,b/t", AutoId.AlbumTrack(listOf("a", "b"), "t").format())
        assertEquals("song:p/3", AutoId.PlaylistSong("p", 3).format())
    }

    @Test
    fun aTagKeepsTheSameAlbumApartAndIsIgnoredWhenRead() {
        val played = AutoId.Album(listOf("x")).format("played")
        val favourite = AutoId.Album(listOf("x")).format("favourite")
        assertTrue(played != favourite)
        assertEquals(AutoId.Album(listOf("x")), AutoId.parse(played))
        assertEquals(AutoId.Album(listOf("x")), AutoId.parse(favourite))
    }

    @Test
    fun idsThisAppDidNotMakeAreRefused() {
        for (bad in listOf(null, "", "#x", "album", "album:", "album:,", "tab:nope", "track:a", "track:a/", "song:p/x",
            "song:p/-1", "play:album:", "play:song:x", "shuffle:x", "tetsubi:handled", "https://example.com")) {
            assertNull(AutoId.parse(bad), bad.toString())
        }
    }

    @Test
    fun onlyPlayRowsPlay() {
        assertTrue(AutoId.PlayAlbum(listOf("a"), false).plays)
        assertTrue(AutoId.AlbumTrack(listOf("a"), "t").plays)
        assertTrue(AutoId.PlaylistSong("p", 0).plays)
        assertTrue(AutoId.Downloaded("a").plays)
        assertFalse(AutoId.Album(listOf("a")).plays)
        assertFalse(AutoId.Tab(AutoTab.HOME).plays)
    }
}

class AutoTreeTest {

    private fun album(id: String, name: String, artist: String = "Artist") =
        JellyfinAlbum(id = id, name = name, albumArtists = listOf(JellyfinPerson(name = artist)))

    private fun track(id: String, number: Int, disc: Int? = null, seconds: Long = 187) =
        JellyfinTrack(id = id, name = "Song $id", indexNumber = number, discNumber = disc, runTimeTicks = seconds * 10_000_000)

    private fun entry(position: Int, id: String) = PlaylistEntry(
        position = position, entryId = "e$position", track = track(id, position + 1),
        albumName = "Album", albumId = "al", albumArtist = "Band",
    )

    @Test
    fun fourTabsAsCarPlay() {
        val tabs = AutoTree.tabs()
        assertEquals(listOf("Home", "Artists", "Playlists", "Downloads"), tabs.map { it.title })
        assertTrue(tabs.all { it.browsable && !it.playable })
        assertEquals(listOf(AutoStyle.GRID, AutoStyle.LIST, AutoStyle.LIST, AutoStyle.GRID), tabs.map { it.childStyle })
    }

    @Test
    fun homeRowsSitUnderTheirHeadingsWithEmptyRowsLeftOut() {
        val rows = AutoTree.home(
            recentlyPlayed = listOf(album("a", "A"), album("b", "B")),
            favourites = emptyList(),
            recentlyAdded = listOf(album("a", "A")),
            failed = false,
        )
        assertEquals(listOf("Recently Played", "Recently Played", "Recently Added"), rows.map { it.group })
        // The same album in two rows gets two IDs, both opening it.
        assertTrue(rows[0].id != rows[2].id)
        assertEquals(AutoId.parse(rows[0].id), AutoId.parse(rows[2].id))
        assertTrue(rows.all { it.browsable && it.artItemId != null && it.childStyle == AutoStyle.LIST })
    }

    @Test
    fun homeRowsStopAtTwelve() {
        val many = (1..20).map { album("$it", "Album $it") }
        assertEquals(12, AutoTree.home(many, emptyList(), emptyList(), failed = false).size)
    }

    @Test
    fun homeOfflinePointsToDownloads() {
        val rows = AutoTree.home(emptyList(), emptyList(), emptyList(), failed = true)
        assertEquals(1, rows.size)
        assertEquals("Can't reach your Jellyfin server", rows[0].title)
        assertEquals("Downloaded albums are in Downloads.", rows[0].subtitle)
        assertEquals(AutoId.Tab(AutoTab.DOWNLOADS), AutoId.parse(rows[0].id))
        assertTrue(rows[0].browsable)
        assertEquals(listOf("Nothing here yet"), AutoTree.home(emptyList(), emptyList(), emptyList(), failed = false).map { it.title })
        assertEquals(rows[0], AutoTree.offline())
    }

    @Test
    fun signedOutSaysSo() {
        val row = AutoTree.signedOut().single()
        assertEquals("Not signed in", row.title)
        assertEquals("Open Tetsubi on your phone to sign in.", row.subtitle)
        assertFalse(row.browsable || row.playable)
    }

    @Test
    fun multiDiscAlbumsAreOneRowWithEveryDisc() {
        val rows = AutoTree.artistAlbums(listOf(album("d2", "Set (Disc 2)"), album("d1", "Set (Disc 1)"), album("x", "Other")))
        assertEquals(listOf("Set", "Other"), rows.map { it.title })
        assertEquals(AutoId.Album(listOf("d1", "d2")), AutoId.parse(rows[0].id))
        assertEquals(listOf("Can't reach your Jellyfin server"), AutoTree.artistAlbums(null).map { it.title })
        assertEquals(listOf("No albums"), AutoTree.artistAlbums(emptyList()).map { it.title })
    }

    @Test
    fun artistsAreAToZ() {
        val rows = AutoTree.artists(listOf(JellyfinArtist("2", "zed"), JellyfinArtist("1", "Abba"), JellyfinArtist("3", "Mogwai")))
        assertEquals(listOf("Abba", "Mogwai", "zed"), rows.map { it.title })
        assertTrue(rows.all { it.childStyle == AutoStyle.GRID })
    }

    @Test
    fun albumPageHasPlayShuffleThenTracks() {
        val a = album("a", "A")
        val rows = AutoTree.albumPage(a, listOf(a to listOf(track("t1", 1), track("t2", 2))), maniac = false)
        assertEquals(listOf("Play", "Shuffle", "Song t1", "Song t2"), rows.map { it.title })
        assertEquals(AutoId.PlayAlbum(listOf("a"), shuffled = false), AutoId.parse(rows[0].id))
        assertEquals(AutoId.PlayAlbum(listOf("a"), shuffled = true), AutoId.parse(rows[1].id))
        assertEquals(AutoId.AlbumTrack(listOf("a"), "t2"), AutoId.parse(rows[3].id))
        assertEquals("3:07", rows[2].subtitle)
        assertTrue(rows.all { it.playable && !it.browsable })
        assertTrue(rows.all { it.group == null })
    }

    @Test
    fun maniacModeHasNoShuffle() {
        val a = album("a", "A")
        val rows = AutoTree.albumPage(a, listOf(a to listOf(track("t1", 1))), maniac = true)
        assertEquals(listOf("Play", "Song t1"), rows.map { it.title })
        val songs = AutoTree.playlistPage("p", listOf(entry(0, "s")), maniac = true)
        assertEquals(listOf("Play", "Song s"), songs.map { it.title })
    }

    @Test
    fun discAlbumsAreGroupedByTheirDiscNumber() {
        val d1 = album("d1", "Set (Disc 1)")
        val d2 = album("d2", "Set (Disc 2)")
        val set = d1.copy(name = "Set", discAlbums = listOf(d1, d2))
        val rows = AutoTree.albumPage(set, listOf(d1 to listOf(track("a", 1)), d2 to listOf(track("b", 1), track("c", 2))), maniac = false)
        assertEquals(listOf(null, null, "Disc 1", "Disc 2", "Disc 2"), rows.map { it.group })
        assertEquals(AutoId.AlbumTrack(listOf("d1", "d2"), "c"), AutoId.parse(rows.last().id))
    }

    @Test
    fun oneAlbumTaggedDiscOneAndTwoIsGroupedByTag() {
        val groups = AutoTree.trackGroups(listOf(album("a", "Fragile") to listOf(track("1", 1, disc = 1), track("2", 1, disc = 2), track("3", 2, disc = 2))))
        assertEquals(listOf("Disc 1" to 1, "Disc 2" to 2), groups.map { it.first to it.second.size })
    }

    @Test
    fun aDiscThatFailedIsLeftOutAndNumberedByPlaceWithoutANumber() {
        val x = album("x", "First")
        val y = album("y", "Second")
        val groups = AutoTree.trackGroups(listOf(x to null, y to listOf(track("1", 1))))
        assertEquals(listOf<String?>("Disc 2"), groups.map { it.first })
    }

    @Test
    fun anAlbumThatDidNotLoadSaysSo() {
        val a = album("a", "A")
        assertEquals(listOf("Can't load this album"), AutoTree.albumPage(a, listOf(a to null), maniac = false).map { it.title })
    }

    @Test
    fun playlistPageGoesByPlace() {
        val rows = AutoTree.playlistPage("p", listOf(entry(0, "s"), entry(1, "s")), maniac = false)
        assertEquals(listOf("Play", "Shuffle", "Song s", "Song s"), rows.map { it.title })
        // The same song twice: two rows, each playing from its own place.
        assertEquals(AutoId.PlaylistSong("p", 0), AutoId.parse(rows[2].id))
        assertEquals(AutoId.PlaylistSong("p", 1), AutoId.parse(rows[3].id))
        assertEquals("Band · Album", rows[2].subtitle)
        assertEquals(listOf("Can't load this playlist"), AutoTree.playlistPage("p", null, false).map { it.title })
        assertEquals(listOf("This playlist is empty."), AutoTree.playlistPage("p", emptyList(), false).map { it.title })
    }

    @Test
    fun playlistsByNameWithOfflineWording() {
        val rows = AutoTree.playlists(listOf(JellyfinPlaylist("2", "Zzz", songCount = 4, runTimeTicks = 16 * 60 * 10_000_000L), JellyfinPlaylist("1", "Drive")), fromServer = true)
        assertEquals(listOf("Drive", "Zzz"), rows.map { it.title })
        assertEquals("4 songs · 16 min", rows[1].subtitle)
        assertEquals(listOf("No playlists yet"), AutoTree.playlists(emptyList(), fromServer = true).map { it.title })
        val offline = AutoTree.playlists(emptyList(), fromServer = false).single()
        assertEquals("No downloaded playlists", offline.title)
        assertEquals("Download playlists on your phone to play them offline.", offline.subtitle)
    }

    @Test
    fun downloadsPlayStraightAway() {
        fun downloaded(id: String, title: String) = DownloadedAlbum(id, title, "Band", null, "FLAC", 0, 0, 0.0, emptyList(), "/x")
        val rows = AutoTree.downloads(listOf(downloaded("b", "Bravo"), downloaded("a", "alpha")))
        assertEquals(listOf("alpha", "Bravo"), rows.map { it.title })
        assertTrue(rows.all { it.playable && !it.browsable })
        assertEquals(AutoId.Downloaded("a"), AutoId.parse(rows[0].id))
        val empty = AutoTree.downloads(emptyList()).single()
        assertEquals("No downloaded albums", empty.title)
        assertEquals("Download albums on your phone to play them offline.", empty.subtitle)
    }

    @Test
    fun pagesAreCutAsAsked() {
        val rows = (0 until 10).toList()
        assertEquals(rows, AutoTree.page(rows, 0, Int.MAX_VALUE))
        assertEquals(listOf(4, 5, 6, 7), AutoTree.page(rows, 1, 4))
        assertEquals(listOf(8, 9), AutoTree.page(rows, 2, 4))
        assertEquals(emptyList(), AutoTree.page(rows, 3, 4))
    }
}
