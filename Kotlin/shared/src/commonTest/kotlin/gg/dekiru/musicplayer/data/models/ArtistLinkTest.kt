package gg.dekiru.musicplayer.data.models

import gg.dekiru.musicplayer.playback.PlaybackTrack
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The artist line on the album page and in Now Playing: which artist it opens, and when it can't (as iOS `ArtistLinkTests`). */
class ArtistLinkTest {
    @Test
    fun anArtistWithAnIdOpensThatArtist() {
        assertEquals(ArtistLink.Artist(JellyfinArtist("a1", "Low")), ArtistLink.resolve("Low", "a1"))
    }

    @Test
    fun aNameWithoutAnIdIsLookedUp() {
        assertEquals(ArtistLink.Lookup("Low"), ArtistLink.resolve(" Low ", null))
        assertEquals(ArtistLink.Lookup("Low"), ArtistLink.resolve("Low", ""))
    }

    @Test
    fun noNameOrVariousArtistsIsNotTappable() {
        assertNull(ArtistLink.resolve("", "a1"))
        assertNull(ArtistLink.resolve("  ", null))
        assertNull(ArtistLink.resolve("Various Artists", "va"))
        assertNull(ArtistLink.resolve("various artists", null))
    }

    @Test
    fun theAlbumsFirstAlbumArtistIsUsed() {
        val album = JellyfinAlbum("al", "Split", albumArtists = listOf(JellyfinPerson("a1", "Low"), JellyfinPerson("a2", "Spoon")))
        assertEquals(ArtistLink.Artist(JellyfinArtist("a1", "Low")), ArtistLink.resolve(album.artistName, album.artistId))
    }

    @Test
    fun matchIsExactIgnoringCaseAndSpaces() {
        val found = listOf(JellyfinArtist("1", "Lower"), JellyfinArtist("2", "LOW "))
        assertEquals("2", ArtistLink.match("low", found)?.id)
        assertNull(ArtistLink.match("Lo", found))
        assertNull(ArtistLink.match("Low", emptyList()))
    }

    @Test
    fun findLooksUpANameOnlyOnline() = runTest {
        val searched = mutableListOf<String>()
        val search: suspend (String) -> List<JellyfinArtist> = { term ->
            searched += term
            listOf(JellyfinArtist("x", "Low"))
        }
        assertEquals("a1", ArtistLink.find("Low", "a1", online = true, search)?.id)
        assertEquals(emptyList(), searched)
        assertEquals("x", ArtistLink.find("Low", null, online = true, search)?.id)
        assertEquals(listOf("Low"), searched)
        assertNull(ArtistLink.find("Low", null, online = false, search))
        // Offline with an ID it still opens (the page shows the downloaded albums).
        assertEquals("a1", ArtistLink.find("Low", "a1", online = false, search)?.id)
        assertNull(ArtistLink.find("Spoon", null, online = true, search))
        assertNull(ArtistLink.find("Various Artists", null, online = true, search))
        assertEquals(listOf("Low", "Spoon"), searched)
    }

    /** Now Playing's track carries its album artist's ID: the track's own first album artist, else the album's. */
    @Test
    fun playbackTrackCarriesTheAlbumArtistId() {
        val album = JellyfinAlbum("al", "A", albumArtists = listOf(JellyfinPerson("a1", "Low")))
        val own = PlaybackTrack.from(JellyfinTrack(id = "t", albumArtists = listOf(JellyfinPerson("a2", "Spoon"))), album, 0)
        assertEquals("Spoon", own.artist)
        assertEquals("a2", own.albumArtistId)

        val bare = PlaybackTrack.from(JellyfinTrack(id = "t"), album, 0)
        assertEquals("Low", bare.artist)
        assertEquals("a1", bare.albumArtistId)

        // A name-only track artist borrows the album's ID when the names match.
        assertEquals("a1", PlaybackTrack.from(JellyfinTrack(id = "t", albumArtists = listOf(JellyfinPerson(name = "Low"))), album, 0).albumArtistId)
        assertNull(PlaybackTrack.from(JellyfinTrack(id = "t", albumArtists = listOf(JellyfinPerson(name = "Spoon"))), album, 0).albumArtistId)

        // Only the name known (a download from the Downloads tab): looked up.
        val named = JellyfinAlbum("al", "A", albumArtists = listOf(JellyfinPerson(name = "Low")))
        val noId = PlaybackTrack.from(JellyfinTrack(id = "t"), named, 0)
        assertNull(noId.albumArtistId)
        assertEquals(ArtistLink.Lookup("Low"), ArtistLink.resolve(noId.artist, noId.albumArtistId))
    }

    @Test
    fun aPlaylistSongTakesItsTracksAlbumArtistId() {
        val track = JellyfinTrack(id = "t", albumArtists = listOf(JellyfinPerson("a1", "Low")))
        assertEquals("a1", PlaybackTrack.from(PlaylistEntry(entryId = "e", track = track, albumArtist = "Low"), 0).albumArtistId)
        assertNull(PlaybackTrack.from(PlaylistEntry(entryId = "e", track = track, albumArtist = "Someone"), 0).albumArtistId)
    }
}
