package gg.dekiru.musicplayer.data.api

import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import gg.dekiru.musicplayer.data.models.JellyfinPlaylist
import gg.dekiru.musicplayer.data.models.PlaylistEntry
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Playlists: what's asked of the server and how its answers are read (answers as recorded from Jellyfin). */
class PlaylistApiTest {

    private fun signedIn(server: FakeServer) = server.api(token = "tok", userId = "u1")

    private val listed = """
        {"Name":"Tetsubi test renamed","ServerId":"625162f4ed3245f698a53f68e9a8da83","Id":"03fa46cb02bc9de9396da4258c2a84ff",
         "DateCreated":"2026-11-11T23:28:15.0000000Z","CanDelete":true,"ChannelId":null,
         "CumulativeRunTimeTicks":10143941948,"RunTimeTicks":10143941948,"IsFolder":true,"Type":"Playlist",
         "UserData":{"PlaybackPositionTicks":0,"PlayCount":0,"IsFavorite":false,"Played":false,
                     "Key":"03fa46cb-02bc-9de9-396d-a4258c2a84ff","ItemId":"03fa46cb02bc9de9396da4258c2a84ff"},
         "ChildCount":4,"ImageTags":{"Primary":"0da9f1ae7eaac8ac1c7c526f652bd931"},"BackdropImageTags":[],
         "ImageBlurHashes":{"Primary":{"0da9f1ae7eaac8ac1c7c526f652bd931":"eJ72gq"}},
         "LocationType":"FileSystem","MediaType":"Audio"}
    """

    private val fresh = """
        {"Name":"New one","Id":"p2","DateCreated":"2026-11-12T08:00:00.0000000Z","RunTimeTicks":0,"IsFolder":true,
         "Type":"Playlist","UserData":{"PlayCount":0,"IsFavorite":false,"Played":false},"ChildCount":0,
         "ImageTags":{},"BackdropImageTags":[],"MediaType":"Audio"}
    """

    private val perfectlySimple = """
        {"Name":"Perfectly Simple","Id":"a85d5e83dfed72d4a3e6f266d8dfd3f2","PlaylistItemId":"a85d5e83dfed72d4a3e6f266d8dfd3f2",
         "AlbumId":"19a8bfe4dbe882ef56f712c13e7c6de1","Album":"...out flew reason","AlbumArtist":"dark star","IndexNumber":1,
         "RunTimeTicks":3071437639,"NormalizationGain":-10.1,"AlbumPrimaryImageTag":"744e3f0e15d44cdd51ae0203992cef66",
         "Type":"Audio","MediaType":"Audio"}
    """

    private val romanRoad = """
        {"Name":"Roman Road","Id":"4ac3003946a71d6e72fc9489b38171c7","PlaylistItemId":"4ac3003946a71d6e72fc9489b38171c7",
         "AlbumId":"19a8bfe4dbe882ef56f712c13e7c6de1","Album":"...out flew reason","AlbumArtist":"dark star","IndexNumber":2,
         "RunTimeTicks":2241462130,"NormalizationGain":-11}
    """

    // The list

    @Test
    fun playlistsRequest() = runTest {
        val server = FakeServer { json("""{"Items":[$listed],"TotalRecordCount":1,"StartIndex":0}""") }
        val playlists = signedIn(server).playlists()
        val request = server.requests.single()
        assertEquals("/Users/u1/Items", request.path)
        val q = request.url.parameters
        assertEquals("Playlist", q["IncludeItemTypes"])
        assertEquals("Audio", q["MediaTypes"])
        assertEquals("true", q["Recursive"])
        assertEquals("ChildCount,CumulativeRunTimeTicks,DateCreated", q["Fields"])

        val playlist = playlists.single()
        assertEquals("03fa46cb02bc9de9396da4258c2a84ff", playlist.id)
        assertEquals("Tetsubi test renamed", playlist.displayName)
        assertEquals(4, playlist.songCount)
        assertEquals(1014, playlist.durationSeconds)
        assertEquals("2026-11-11T23:28:15.0000000Z", playlist.dateCreated)
        assertTrue(playlist.hasArt)
    }

    @Test
    fun aNewPlaylistHasNoArtAndNoSongs() = runTest {
        val server = FakeServer { json("""{"Items":[$fresh],"TotalRecordCount":1,"StartIndex":0}""") }
        val playlist = signedIn(server).playlists().single()
        assertFalse(playlist.hasArt, "ImageTags is {}")
        assertEquals(0, playlist.songCount)
        assertEquals(0, playlist.durationSeconds)
    }

    @Test
    fun noPlaylists() = runTest {
        val server = FakeServer { json("""{"Items":[],"TotalRecordCount":0,"StartIndex":0}""") }
        assertEquals(emptyList(), signedIn(server).playlists())
    }

    @Test
    fun aBarePlaylistItemIsRead() {
        val playlist = TetsubiJson.decodeFromString<JellyfinPlaylist>(
            """{"Id":"p","Name":"Mix","UserData":{"LastPlayedDate":"2026-11-10T20:00:00.0000000Z","PlayCount":2}}""",
        )
        assertEquals("Mix", playlist.displayName)
        assertFalse(playlist.hasArt, "no ImageTags at all")
        assertEquals("Unknown", TetsubiJson.decodeFromString<JellyfinPlaylist>("""{"Id":"p"}""").displayName)
    }

    @Test
    fun runningTimeFallsBackToRunTimeTicks() {
        val playlist = TetsubiJson.decodeFromString<JellyfinPlaylist>("""{"Id":"p","RunTimeTicks":600000000}""")
        assertEquals(60, playlist.durationSeconds)
    }

    // A playlist's tracks

    @Test
    fun playlistTracksRequest() = runTest {
        val server = FakeServer { json("""{"Items":[$perfectlySimple,$romanRoad],"TotalRecordCount":2,"StartIndex":0}""") }
        val entries = signedIn(server).playlistTracks("pl1")
        val request = server.requests.single()
        assertEquals("/Playlists/pl1/Items", request.path)
        assertEquals("u1", request.url.parameters["userId"])
        assertEquals("MediaSources,NormalizationGain", request.url.parameters["Fields"])

        assertEquals(listOf(0, 1), entries.map { it.position })
        val first = entries[0]
        assertEquals("a85d5e83dfed72d4a3e6f266d8dfd3f2", first.entryId)
        assertEquals("a85d5e83dfed72d4a3e6f266d8dfd3f2", first.track.id)
        assertEquals("Perfectly Simple", first.track.displayName)
        assertEquals("...out flew reason", first.albumName)
        assertEquals("19a8bfe4dbe882ef56f712c13e7c6de1", first.albumId)
        assertEquals("dark star", first.albumArtist)
        assertEquals(307, first.track.durationSeconds)
        assertEquals(-10.1, first.track.normalizationGain)
        assertEquals("19a8bfe4dbe882ef56f712c13e7c6de1", first.artItemId, "the album's picture")
        assertEquals("Roman Road", entries[1].track.displayName)
    }

    @Test
    fun aSongListedTwiceIsTwoEntries() = runTest {
        val server = FakeServer { json("""{"Items":[$perfectlySimple,$romanRoad,$perfectlySimple],"TotalRecordCount":3}""") }
        val entries = signedIn(server).playlistTracks("pl1")
        assertEquals(3, entries.size)
        assertEquals(entries[0].track.id, entries[2].track.id)
        assertEquals(entries[0].entryId, entries[2].entryId, "the server gives both the same PlaylistItemId")
        assertEquals(listOf(0, 1, 2), entries.map { it.position }, "told apart by position")
        assertTrue(entries[0] != entries[2])
    }

    @Test
    fun anEmptyPlaylistHasNoEntries() = runTest {
        val server = FakeServer { json("""{"Items":[],"TotalRecordCount":0,"StartIndex":0}""") }
        assertEquals(emptyList(), signedIn(server).playlistTracks("pl1"))
    }

    @Test
    fun anUnknownPlaylistIs404() = runTest {
        val server = FakeServer { json("", HttpStatusCode.NotFound) }
        val error = assertFailsWith<JellyfinError.Http> { signedIn(server).playlistTracks("gone") }
        assertEquals(404, error.status)
    }

    // Making and changing playlists

    @Test
    fun createPlaylistRequestAndAnswer() = runTest {
        val server = FakeServer { json("""{"Id":"7f3c2e0a9b1d4c5e8f6a7b8c9d0e1f2a"}""") }
        val id = signedIn(server).createPlaylist("Road trip", listOf("t1", "t2", "t1"))
        assertEquals("7f3c2e0a9b1d4c5e8f6a7b8c9d0e1f2a", id)
        val request = server.requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/Playlists", request.path)
        assertTrue(request.url.parameters.isEmpty(), "everything is in the body")
        assertEquals(
            TetsubiJson.parseToJsonElement("""{"Name":"Road trip","Ids":["t1","t2","t1"],"UserId":"u1","MediaType":"Audio","IsPublic":false}"""),
            TetsubiJson.parseToJsonElement(request.bodyText),
        )
        assertTrue(request.authorization.contains("Token=\"tok\""), request.authorization)
    }

    @Test
    fun createPlaylistWithNoSongsSendsAnEmptyList() = runTest {
        val server = FakeServer { json("""{"Id":"p9"}""") }
        assertEquals("p9", signedIn(server).createPlaylist("Empty", emptyList()))
        assertEquals(
            TetsubiJson.parseToJsonElement("""{"Name":"Empty","Ids":[],"UserId":"u1","MediaType":"Audio","IsPublic":false}"""),
            TetsubiJson.parseToJsonElement(server.requests.single().bodyText),
        )
    }

    @Test
    fun createPlaylistFailureIsAnError() = runTest {
        val server = FakeServer { json("", HttpStatusCode.Forbidden) }
        assertEquals(403, assertFailsWith<JellyfinError.Http> { signedIn(server).createPlaylist("x", listOf("t1")) }.status)
    }

    @Test
    fun addToPlaylistRequestAndAnswer() = runTest {
        val server = FakeServer { json("", HttpStatusCode.NoContent) }
        signedIn(server).addToPlaylist("pl1", listOf("a", "b", "c"))
        val request = server.requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/Playlists/pl1/Items", request.path)
        assertEquals("a,b,c", request.url.parameters["ids"])
        assertEquals("u1", request.url.parameters["userId"])
        assertEquals("", request.bodyText)
    }

    @Test
    fun addToPlaylistFailureIsAnError() = runTest {
        val server = FakeServer { json("", HttpStatusCode.NotFound) }
        assertEquals(404, assertFailsWith<JellyfinError.Http> { signedIn(server).addToPlaylist("gone", listOf("a")) }.status)
    }

    @Test
    fun setPlaylistItemsRequestAndAnswer() = runTest {
        val server = FakeServer { json("", HttpStatusCode.NoContent) }
        signedIn(server).setPlaylistItems("pl1", listOf("b", "a", "b"))
        val request = server.requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/Playlists/pl1", request.path)
        assertTrue(request.url.parameters.isEmpty())
        assertEquals(TetsubiJson.parseToJsonElement("""{"Ids":["b","a","b"]}"""), TetsubiJson.parseToJsonElement(request.bodyText), "in order, the song twice kept")
        // None: the playlist is emptied.
        signedIn(server).setPlaylistItems("pl1", emptyList())
        assertEquals(TetsubiJson.parseToJsonElement("""{"Ids":[]}"""), TetsubiJson.parseToJsonElement(server.requests.last().bodyText))
    }

    @Test
    fun setPlaylistItemsFailureIsAnError() = runTest {
        val server = FakeServer { json("", HttpStatusCode.Forbidden) }
        assertEquals(403, assertFailsWith<JellyfinError.Http> { signedIn(server).setPlaylistItems("pl1", listOf("a")) }.status)
    }

    @Test
    fun renamePlaylistRequestAndAnswer() = runTest {
        val server = FakeServer { json("", HttpStatusCode.NoContent) }
        signedIn(server).renamePlaylist("pl1", "Sunday \"quiet\"")
        val request = server.requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/Playlists/pl1", request.path)
        assertEquals(TetsubiJson.parseToJsonElement("""{"Name":"Sunday \"quiet\""}"""), TetsubiJson.parseToJsonElement(request.bodyText), "only the name: the songs stay")
    }

    @Test
    fun renamePlaylistFailureIsAnError() = runTest {
        val server = FakeServer { json("", HttpStatusCode.InternalServerError) }
        assertEquals(500, assertFailsWith<JellyfinError.Http> { signedIn(server).renamePlaylist("pl1", "x") }.status)
    }

    @Test
    fun canEditPlaylistRequestAndAnswer() = runTest {
        var answer = """{"UserId":"u1","CanEdit":true}"""
        var status = HttpStatusCode.OK
        val server = FakeServer { json(answer, status) }
        assertTrue(signedIn(server).canEditPlaylist("pl1"))
        val request = server.requests.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("/Playlists/pl1/Users/u1", request.path)
        answer = """{"UserId":"u1","CanEdit":false}"""
        assertFalse(signedIn(server).canEditPlaylist("pl1"))
        // Someone else's playlist: the server doesn't list this user.
        answer = ""
        status = HttpStatusCode.NotFound
        assertFalse(signedIn(server).canEditPlaylist("pl1"))
        status = HttpStatusCode.InternalServerError
        assertFalse(signedIn(server).canEditPlaylist("pl1"), "no answer means no")
    }

    @Test
    fun deletePlaylistRequestAndAnswer() = runTest {
        val server = FakeServer { json("", HttpStatusCode.NoContent) }
        signedIn(server).deletePlaylist("pl1")
        val request = server.requests.single()
        assertEquals(HttpMethod.Delete, request.method)
        assertEquals("/Items/pl1", request.path)
        assertEquals("", request.bodyText)
    }

    @Test
    fun deletePlaylistFailureIsAnError() = runTest {
        val server = FakeServer { json("", HttpStatusCode.Forbidden) }
        assertEquals(403, assertFailsWith<JellyfinError.Http> { signedIn(server).deletePlaylist("pl1") }.status)
    }

    @Test
    fun entryWithoutPlaylistItemIdOrAlbumArtistFallsBack() {
        val entry = TetsubiJson.decodeFromString<PlaylistEntry>(
            """{"Id":"t1","Name":"Loose","AlbumArtists":[{"Name":"Band","Id":"b"}]}""",
        )
        assertEquals("t1", entry.entryId)
        assertEquals("Band", entry.albumArtist)
        assertNull(entry.albumId)
        assertEquals("t1", entry.artItemId, "no album: the track's own picture")
    }

    @Test
    fun entryGoesToJsonAndBack() {
        val entry = TetsubiJson.decodeFromString<PlaylistEntry>(perfectlySimple).copy(position = 3)
        val again = TetsubiJson.decodeFromString<PlaylistEntry>(TetsubiJson.encodeToString(PlaylistEntry.serializer(), entry))
        assertEquals(entry.copy(position = 0), again, "the position is the place in the answer, not saved")
    }
}
