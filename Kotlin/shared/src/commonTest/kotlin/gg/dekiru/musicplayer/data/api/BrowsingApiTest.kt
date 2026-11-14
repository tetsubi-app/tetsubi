package gg.dekiru.musicplayer.data.api

import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.settings.FavoritesStore
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The Home and album-page calls: paths, queries and answer handling. */
class BrowsingApiTest {

    private fun signedIn(server: FakeServer) = server.api(token = "tok", userId = "u1")

    @Test
    fun releaseDatesAskForNoExtras() = runTest {
        val server = FakeServer { json("""{"Items":[{"Id":"a","PremiereDate":"2008-11-10T00:00:00Z"}]}""") }
        assertEquals("a", signedIn(server).albumReleaseDates().single().id)
        val q = server.requests.single().url.parameters
        assertEquals("false", q["EnableImages"])
        assertEquals("false", q["EnableUserData"])
        assertEquals(null, q["limit"])
        assertEquals("ProductionYear,PremiereDate", q["Fields"])
    }

    @Test
    fun albumsByIdsInOneRequest() = runTest {
        val server = FakeServer { json("""{"Items":[{"Id":"a"},{"Id":"b"}]}""") }
        assertEquals(listOf("a", "b"), signedIn(server).albumsByIds(listOf("a", "b")).map { it.id })
        assertEquals("a,b", server.requests.single().url.parameters["Ids"])
        assertEquals(emptyList(), signedIn(server).albumsByIds(emptyList()))
        assertEquals(1, server.requests.size, "no request for no IDs")
    }

    @Test
    fun recentlyAddedReadsABareArray() = runTest {
        val server = FakeServer { json("""[{"Id":"a1","Name":"New","Type":"MusicAlbum"}]""") }
        assertEquals(listOf("a1"), signedIn(server).recentlyAddedAlbums().map { it.id })
        assertEquals("/Users/u1/Items/Latest", server.requests.single().path)
    }

    @Test
    fun randomAlbumsQuery() = runTest {
        val server = FakeServer { json("""{"Items":[{"Id":"r1"}],"TotalRecordCount":1}""") }
        signedIn(server).randomAlbums(limit = 12)
        val q = server.requests.single().url.parameters
        assertEquals("/Users/u1/Items", server.requests.single().path)
        assertEquals("Random", q["SortBy"])
        assertEquals("MusicAlbum", q["IncludeItemTypes"])
        assertEquals("12", q["limit"])
    }

    @Test
    fun recentlyPlayedGroupsPlayedTracksIntoAlbums() = runTest {
        val server = FakeServer {
            json(
                """{"Items":[
                  {"Id":"t1","Type":"Audio","Album":"Bleach","AlbumId":"bleach","AlbumArtists":[{"Name":"Nirvana"}],"UserData":{"PlayCount":2}},
                  {"Id":"t2","Type":"Audio","Album":"Bleach","AlbumId":"bleach","AlbumArtists":[{"Name":"Nirvana"}],"UserData":{"PlayCount":1}},
                  {"Id":"t3","Type":"Audio","Album":"Never","AlbumId":"never","UserData":{"PlayCount":0}},
                  {"Id":"t4","Type":"Audio","Album":"Nevermind","AlbumId":"nevermind","AlbumArtists":[{"Name":"Nirvana"}],"UserData":{"PlayCount":5}}
                ]}""",
            )
        }
        val albums = signedIn(server).recentlyPlayedAlbums(limit = 12)
        assertEquals(listOf("bleach", "nevermind"), albums.map { it.id })
        assertEquals("Bleach", albums[0].displayName)
        val q = server.requests.single().url.parameters
        assertEquals("Audio", q["IncludeItemTypes"])
        assertEquals("DatePlayed", q["SortBy"])
        assertEquals("240", q["limit"])
    }

    @Test
    fun recentlyPlayedListsAnAlbumOnceWhenItsTracksDisagree() = runTest {
        // Tracks of one album with different artists or album names: one entry (a repeated ID crashed Home's row).
        val server = FakeServer {
            json(
                """{"Items":[
                  {"Id":"t1","Type":"Audio","Album":"Unity","AlbumId":"unity","AlbumArtists":[{"Name":"HEY-SMITH"}],"UserData":{"PlayCount":1}},
                  {"Id":"t2","Type":"Audio","Album":"Unity","AlbumId":"unity","AlbumArtists":[{"Name":"Less Than Jake"}],"UserData":{"PlayCount":1}},
                  {"Id":"t3","Type":"Audio","Album":"Unity (Split)","AlbumId":"unity","UserData":{"PlayCount":1}}
                ]}""",
            )
        }
        assertEquals(listOf("unity"), signedIn(server).recentlyPlayedAlbums(limit = 12).map { it.id })
    }

    @Test
    fun tracksKeepsOnlyTheAlbumsOwn() = runTest {
        val server = FakeServer {
            json("""{"Items":[{"Id":"t1","AlbumId":"ALB"},{"Id":"t2","AlbumId":"other"},{"Id":"t3"}]}""")
        }
        assertEquals(listOf("t1", "t3"), signedIn(server).tracks("alb").map { it.id })
        assertEquals("alb", server.requests.single().url.parameters["ParentId"])
    }

    @Test
    fun favouriteOnAndOff() = runTest {
        val server = FakeServer { json("") }
        val api = signedIn(server)
        api.setFavorite("a1", true)
        api.setFavorite("a1", false)
        assertEquals(listOf(HttpMethod.Post, HttpMethod.Delete), server.requests.map { it.method })
        assertTrue(server.requests.all { it.path == "/UserFavoriteItems/a1" && it.url.parameters["userId"] == "u1" })
    }

    @Test
    fun isFavoriteReadsUserData() = runTest {
        val server = FakeServer { json("""{"Items":[{"Id":"a1","UserData":{"IsFavorite":true}}]}""") }
        assertTrue(signedIn(server).isFavorite("a1"))
    }

    @Test
    fun musicBrainzIdFromProviderIds() = runTest {
        val server = FakeServer { json("""{"Id":"ar","ProviderIds":{"MusicBrainzArtist":"b7ff"}}""") }
        assertEquals("b7ff", signedIn(server).musicBrainzArtistId("ar"))
    }

    @Test
    fun imageUrlHasNoToken() {
        val url = FakeServer { json("") }.api("https://example.com/jellyfin", token = "secret-token").imageUrl("al-1", 300)
        assertEquals("https://example.com/jellyfin/Items/al-1/Images/Primary?maxWidth=300&quality=90", url)
    }

    @Test
    fun browsingNeedsSignIn() = runTest {
        assertFailsWith<JellyfinError.NotSignedIn> { FakeServer { json("{}") }.api().randomAlbums() }
    }

    // Favourites order

    @Test
    fun favouritesFromThisAppComeFirstNewestFirst() {
        var clock = 100L
        val store = FavoritesStore(InMemoryPreferences()) { clock }
        val albums = listOf("a", "b", "c", "d").map { JellyfinAlbum(id = it) }
        store.record("c", true)
        clock = 200
        store.record("b", true)
        clock = 300
        store.record("d", true)
        store.record("d", false)
        assertEquals(listOf("b", "c", "a", "d"), store.ordered(albums).map { it.id })
    }
}
