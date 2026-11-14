package gg.dekiru.musicplayer.data.api

import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Library's calls: every album, album artists, one artist's albums. */
class LibraryApiTest {

    private fun signedIn(server: FakeServer) = server.api(token = "tok", userId = "u1")

    @Test
    fun allAlbumsNewestAdditionFirst() = runTest {
        val server = FakeServer { json("""{"Items":[{"Id":"a","Name":"One","AlbumArtists":[{"Name":"Band"}],"ProductionYear":1999}]}""") }
        val albums = signedIn(server).allAlbums()
        assertEquals("Band", albums.single().artistName)
        assertEquals(1999, albums.single().productionYear)
        val request = server.requests.single()
        assertEquals("/Users/u1/Items", request.path)
        val q = request.url.parameters
        assertEquals("MusicAlbum", q["IncludeItemTypes"])
        assertEquals("true", q["Recursive"])
        assertEquals("10000", q["limit"])
        assertEquals("DateAdded", q["SortBy"])
        assertEquals("Descending", q["SortOrder"])
        assertEquals("AlbumArtists,Album,ProductionYear,PremiereDate", q["Fields"])
    }

    @Test
    fun albumArtistsByName() = runTest {
        val server = FakeServer { json("""{"Items":[{"Id":"ar1","Name":"Björk"},{"Id":"ar2","Name":"Can"}],"TotalRecordCount":2}""") }
        val artists = signedIn(server).albumArtists()
        assertEquals(listOf("Björk", "Can"), artists.map { it.name })
        assertEquals(listOf("ar1", "ar2"), artists.map { it.id })
        val request = server.requests.single()
        assertEquals("/Artists/AlbumArtists", request.path)
        val q = request.url.parameters
        assertEquals("u1", q["UserId"])
        assertEquals("SortName", q["SortBy"])
        assertEquals("Ascending", q["SortOrder"])
    }

    @Test
    fun anArtistsAlbumsNewestReleaseFirst() = runTest {
        val server = FakeServer { json("""{"Items":[{"Id":"x"}]}""") }
        assertEquals("x", signedIn(server).albumsByArtist("ar1").single().id)
        val request = server.requests.single()
        assertEquals("/Items", request.path)
        val q = request.url.parameters
        assertEquals("ar1", q["AlbumArtistIds"])
        assertEquals("MusicAlbum", q["IncludeItemTypes"])
        assertEquals("ProductionYear,SortName", q["SortBy"])
        assertEquals("Descending", q["SortOrder"])
    }

    @Test
    fun noItemsIsAnEmptyList() = runTest {
        val server = FakeServer { json("""{"TotalRecordCount":0}""") }
        assertEquals(emptyList(), signedIn(server).albumArtists())
    }
}
