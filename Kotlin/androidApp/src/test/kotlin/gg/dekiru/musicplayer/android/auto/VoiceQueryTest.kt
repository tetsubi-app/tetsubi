package gg.dekiru.musicplayer.android.auto

import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinPerson
import gg.dekiru.musicplayer.data.models.JellyfinPlaylist
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceQueryTest {

    @Test
    fun anAlbumNameIsSearchedForAsSaid() {
        val request = VoiceQuery.parse("OK Computer")
        assertEquals(VoiceRequest("OK Computer", shuffle = false, focus = VoiceFocus.ANY), request)
    }

    @Test
    fun theAppNameAndPlayAreLeftOut() {
        assertEquals("Kid A", VoiceQuery.parse("play Kid A on Tetsubi").terms)
        assertEquals("Kid A", VoiceQuery.parse("Kid A in tetsubi").terms)
        assertEquals("Drive", VoiceQuery.parse("play the playlist Drive").terms)
    }

    @Test
    fun shuffleAnywhereShufflesAndIsNotSearchedFor() {
        val request = VoiceQuery.parse("shuffle In Rainbows on Tetsubi")
        assertTrue(request.shuffle)
        assertEquals("In Rainbows", request.terms)
        assertTrue(VoiceQuery.parse("play Amnesiac on shuffle").shuffle)
        assertEquals("Amnesiac", VoiceQuery.parse("play Amnesiac on shuffle").terms)
        assertFalse(VoiceQuery.parse("Amnesiac").shuffle)
    }

    @Test
    fun playMusicPlaysSomething() {
        for (said in listOf("", "music", "play music on Tetsubi", "something", "Play something in Tetsubi", "shuffle music")) {
            assertEquals("", VoiceQuery.parse(said).terms, said)
        }
        assertTrue(VoiceQuery.parse("shuffle music").shuffle)
        assertEquals("", VoiceQuery.parse(null).terms)
    }

    @Test
    fun assistantsNamedPartsWinOverTheSentence() {
        val album = VoiceQuery.parse("play the album OK Computer by Radiohead", focus = VoiceQuery.FOCUS_ALBUM, album = "OK Computer", artist = "Radiohead")
        assertEquals(VoiceRequest("OK Computer", false, VoiceFocus.ALBUM), album)
        val playlist = VoiceQuery.parse("play my Drive playlist", focus = VoiceQuery.FOCUS_PLAYLIST, playlist = "Drive")
        assertEquals(VoiceRequest("Drive", false, VoiceFocus.PLAYLIST), playlist)
        val artist = VoiceQuery.parse("play Radiohead", focus = VoiceQuery.FOCUS_ARTIST, artist = "Radiohead")
        assertEquals(VoiceFocus.ARTIST, artist.focus)
        assertEquals("Radiohead", artist.terms)
        // A playlist name with no focus still means a playlist.
        assertEquals(VoiceFocus.PLAYLIST, VoiceQuery.parse("Drive", playlist = "Drive").focus)
    }

    private fun album(id: String, name: String, artist: String = "") =
        JellyfinAlbum(id = id, name = name, albumArtists = listOf(JellyfinPerson(name = artist)))

    @Test
    fun theExactNameIsTheBestAlbum() {
        val results = listOf(album("1", "OK Computer OKNOTOK"), album("2", "OK Computer"), album("3", "Other"))
        assertEquals("2", VoiceQuery.bestAlbum("ok computer", results)?.id)
        assertEquals("2", VoiceQuery.bestAlbum("OK, Computer!", results)?.id)
    }

    @Test
    fun otherwiseOneWithEveryWordThenTheFirst() {
        val results = listOf(album("1", "Greatest Hits", "Queen"), album("2", "The Best of Blondie"), album("3", "Hits of Queen"))
        assertEquals("2", VoiceQuery.bestAlbum("best blondie", results)?.id)
        assertEquals("3", VoiceQuery.bestAlbum("queen hits", results)?.id)
        assertEquals("1", VoiceQuery.bestAlbum("greatest hits queen", results)?.id)
        assertEquals("1", VoiceQuery.bestAlbum("nothing like it", results)?.id)
        assertNull(VoiceQuery.bestAlbum("anything", emptyList()))
    }

    @Test
    fun accentsAndCaseDoNotMatter() {
        assertEquals("1", VoiceQuery.bestAlbum("cafe tacvba", listOf(album("0", "Takk"), album("1", "Café Tacvba")))?.id)
    }

    @Test
    fun playlistsMatchByName() {
        val lists = listOf(JellyfinPlaylist("1", "Road Trip Mix"), JellyfinPlaylist("2", "Drive"))
        assertEquals("2", VoiceQuery.exactPlaylist("drive", lists)?.id)
        assertNull(VoiceQuery.exactPlaylist("road trip", lists))
        assertEquals("1", VoiceQuery.bestPlaylist("road trip", lists)?.id)
        assertNull(VoiceQuery.bestPlaylist("", lists))
        assertNull(VoiceQuery.bestPlaylist("jazz", lists))
    }
}
