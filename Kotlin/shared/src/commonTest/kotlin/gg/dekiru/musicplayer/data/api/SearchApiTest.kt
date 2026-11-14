package gg.dekiru.musicplayer.data.api

import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Search calls (as iOS `searchForAlbums` / `searchArtists`): paths, queries, answers. */
class SearchApiTest {

    private fun signedIn(server: FakeServer) = server.api(token = "tok", userId = "u1")

    @Test
    fun albumSearchQuery() = runTest {
        val server = FakeServer { json("""{"Items":[{"Id":"a1","Name":"Gish","AlbumArtists":[{"Name":"Smashing Pumpkins"}]}]}""") }
        val albums = signedIn(server).searchAlbums("gish")
        assertEquals(listOf("a1"), albums.map { it.id })
        assertEquals("Smashing Pumpkins", albums.single().artistName)
        val request = server.requests.single()
        assertEquals("/Users/u1/Items", request.path)
        val q = request.url.parameters
        assertEquals("gish", q["SearchTerm"])
        assertEquals("MusicAlbum", q["IncludeItemTypes"])
        assertEquals("true", q["Recursive"])
        assertEquals("50", q["limit"])
        assertEquals("Name", q["SortBy"])
        assertEquals("Ascending", q["SortOrder"])
        assertEquals("AlbumArtists,Album,ProductionYear,PremiereDate", q["Fields"])
        assertEquals("1", q["ImageTypeLimit"])
    }

    @Test
    fun artistSearchQuery() = runTest {
        val server = FakeServer { json("""{"Items":[{"Id":"ar1","Name":"Gruntruck"}],"TotalRecordCount":1}""") }
        val artists = signedIn(server).searchArtists("grunt", limit = 3)
        assertEquals(listOf("ar1" to "Gruntruck"), artists.map { it.id to it.name })
        val request = server.requests.single()
        assertEquals("/Artists/AlbumArtists", request.path)
        val q = request.url.parameters
        assertEquals("u1", q["UserId"])
        assertEquals("grunt", q["searchTerm"])
        assertEquals("3", q["Limit"])
        assertEquals("SortName", q["SortBy"])
    }

    @Test
    fun playlistSearchQuery() = runTest {
        val server = FakeServer { json("""{"Items":[],"TotalRecordCount":0,"StartIndex":0}""") }
        assertEquals(emptyList(), signedIn(server).searchPlaylists("road trip"))
        val request = server.requests.single()
        assertEquals("/Users/u1/Items", request.path)
        val q = request.url.parameters
        assertEquals("road trip", q["SearchTerm"])
        assertEquals("Playlist", q["IncludeItemTypes"])
        assertEquals("Audio", q["MediaTypes"])
        assertEquals("true", q["Recursive"])
        assertEquals("ChildCount,CumulativeRunTimeTicks,DateCreated", q["Fields"])
    }

    @Test
    fun playlistSearchAnswer() = runTest {
        val server = FakeServer {
            json(
                """{"Items":[
                  {"Name":"Road Trip","Id":"p1","ChildCount":24,"CumulativeRunTimeTicks":43200000000,
                   "DateCreated":"2026-11-11T23:28:15.0000000Z","ImageTags":{"Primary":"abc"},"Type":"Playlist","MediaType":"Audio"},
                  {"Name":"Trip Hop","Id":"p2","ImageTags":{},"Type":"Playlist"}
                ],"TotalRecordCount":2,"StartIndex":0}""",
            )
        }
        val found = signedIn(server).searchPlaylists("trip")
        assertEquals(listOf("p1", "p2"), found.map { it.id })
        assertEquals("Road Trip", found[0].displayName)
        assertEquals(24, found[0].songCount)
        assertEquals(4320, found[0].durationSeconds)
        assertEquals("2026-11-11T23:28:15.0000000Z", found[0].dateCreated)
        assertTrue(found[0].hasArt)
        // New or empty: no picture, no songs.
        assertFalse(found[1].hasArt)
        assertEquals(0, found[1].songCount)
        assertEquals(0, found[1].durationSeconds)
    }

    @Test
    fun blankTermsAskNothing() = runTest {
        val server = FakeServer { json("""{"Items":[{"Id":"x"}]}""") }
        assertEquals(emptyList(), signedIn(server).searchAlbums("  "))
        assertEquals(emptyList(), signedIn(server).searchArtists(""))
        assertEquals(emptyList(), signedIn(server).searchPlaylists(" "))
        assertTrue(server.requests.isEmpty())
    }
}
