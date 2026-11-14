package gg.dekiru.musicplayer.data.models

import gg.dekiru.musicplayer.data.api.TetsubiJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reading Jellyfin's JSON into the models. */
class ModelDecodingTest {

    private inline fun <reified T> decode(json: String): T = TetsubiJson.decodeFromString(json)

    @Test
    fun audioItemResolvesToParentAlbum() {
        val album = decode<JellyfinAlbum>("""{"Id":"trk","Name":"Break down!!","Type":"Audio","Album":"#1stDEMO","AlbumId":"alb"}""")
        assertEquals("alb", album.id)
        assertEquals("#1stDEMO", album.displayName)
    }

    @Test
    fun musicAlbumKeepsItsOwnId() {
        val album = decode<JellyfinAlbum>("""{"Id":"alb","Name":"#1stDEMO","Type":"MusicAlbum","AlbumId":"other"}""")
        assertEquals("alb", album.id)
    }

    @Test
    fun albumFields() {
        val album = decode<JellyfinAlbum>(
            """
            {"Id":"a1","Name":"MTV Unplugged","ProductionYear":1994,"PremiereDate":"1994-10-31T00:00:00.0000000Z",
             "AlbumArtists":[{"Id":"ar1","Name":"Nirvana"}],"ImageTags":{"Primary":"tag"},
             "RunTimeTicks":32000000000,"Type":"MusicAlbum",
             "UserData":{"PlayCount":3,"IsFavorite":true,"Played":false,"DatePlayed":"2026-10-31T20:00:00Z"},
             "SomethingNew":{"Nested":[1,2]}}
            """,
        )
        assertEquals("Nirvana", album.artistName)
        assertEquals("ar1", album.artistId)
        assertEquals(3200, album.durationSeconds)
        assertEquals(1994, album.productionYear)
        assertEquals("tag", album.imageTags?.get("Primary"))
        assertEquals(true, album.userData?.isFavorite)
        assertTrue(album.hasBeenPlayed)
    }

    @Test
    fun playCountCountsAsPlayedWithoutDates() {
        assertTrue(decode<JellyfinAlbum>("""{"Id":"a","UserData":{"PlayCount":2}}""").hasBeenPlayed)
        assertEquals(false, decode<JellyfinAlbum>("""{"Id":"a","UserData":{"PlayCount":0}}""").hasBeenPlayed)
    }

    @Test
    fun albumsAreEqualById() {
        assertEquals(JellyfinAlbum(id = "a", name = "One"), JellyfinAlbum(id = "a", name = "Other"))
    }

    @Test
    fun trackKeepsFirstMediaSourceAndFindsCodecInStreams() {
        val track = decode<JellyfinTrack>(
            """
            {"Id":"t1","Name":"Lithium","IndexNumber":5,"ParentIndexNumber":2,"RunTimeTicks":2570000000,
             "MediaSources":[{"Id":"m1","Container":"flac","Size":1234,
               "MediaStreams":[{"Type":"Video","Codec":"mjpeg"},{"Type":"Audio","Codec":"FLAC"}]},
               {"Id":"m2","Container":"mp3"}]}
            """,
        )
        assertEquals("flac", track.mediaSource?.container)
        assertEquals("flac", track.mediaSource?.codecs)
        assertEquals(257, track.durationSeconds)
        assertEquals(205, track.fileNumber)
    }

    @Test
    fun topLevelCodecsWins() {
        val track = decode<JellyfinTrack>("""{"Id":"t","MediaSources":[{"Codecs":"AAC","MediaStreams":[{"Type":"Audio","Codec":"mp3"}]}]}""")
        assertEquals("aac", track.mediaSource?.codecs)
    }

    @Test
    fun itemsResponseWithoutItems() {
        val response = decode<ItemsResponse<JellyfinAlbum>>("""{"TotalRecordCount":0}""")
        assertEquals(emptyList(), response.items)
    }

    @Test
    fun quickConnectStartDecodes() {
        val start = decode<QuickConnectStart>("""{"Secret":"abc","Code":"123456","Authenticated":false}""")
        assertEquals("123456", start.code)
        assertEquals("abc", start.secret)
    }

    @Test
    fun authResponseWithoutUserHasNoId() {
        assertNull(decode<AuthenticationResponse>("""{"AccessToken":"t"}""").resolvedUserId)
    }
}
