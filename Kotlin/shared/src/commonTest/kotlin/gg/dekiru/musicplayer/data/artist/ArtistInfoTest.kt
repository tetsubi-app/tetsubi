package gg.dekiru.musicplayer.data.artist

import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import gg.dekiru.musicplayer.data.api.TetsubiJson
import gg.dekiru.musicplayer.data.api.tetsubiDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "About <artist>". Ported from ArtistInfoTests.swift, plus the lookup against pretend services. */
class ArtistInfoTest {

    private val sample = """
        {"id":"b7ffd2af-418f-4be2-bdd1-22f8b48613da","name":"Nine Inch Nails","type":"Group",
         "disambiguation":"","area":{"name":"United States"},"begin-area":{"name":"Cleveland"},
         "life-span":{"begin":"1988","end":null,"ended":false},
         "genres":[{"name":"industrial","count":18},{"name":"industrial rock","count":40},
                   {"name":"alternative rock","count":16},{"name":"electronic","count":12},
                   {"name":"industrial metal","count":8},{"name":"electro-industrial","count":6}],
         "relations":[{"type":"official homepage","url":{"resource":"https://www.nin.com/"}},
                      {"type":"wikidata","url":{"resource":"https://www.wikidata.org/wiki/Q11647"}}]}
    """

    @Test
    fun parsesMusicBrainzArtist() {
        val artist = TetsubiJson.decodeFromString(MbArtist.serializer(), sample)
        val info = ArtistInfoClient.infoFrom(artist, nowMillis = 0)
        assertEquals("Group · Cleveland, United States · 1988–present", info.facts)
        assertEquals(listOf("industrial rock", "industrial", "alternative rock", "electronic", "industrial metal"), info.genres)
        assertNull(info.disambiguation)
        assertEquals("https://musicbrainz.org/artist/b7ffd2af-418f-4be2-bdd1-22f8b48613da", info.musicBrainzUrl)
        assertEquals("Q11647", ArtistInfoClient.wikidataId(artist.relations.orEmpty()))
    }

    @Test
    fun years() {
        assertEquals("1988–present", ArtistInfo.years("1988", null, false))
        assertEquals("1989–1996", ArtistInfo.years("1989-03-01", "1996-11", true))
        assertEquals("1990", ArtistInfo.years("1990", null, true))
        assertNull(ArtistInfo.years(null, "2000", true))
    }

    @Test
    fun nameSearchOnlyTrustsAnExactConfidentMatch() {
        val results = listOf(MbSearch.Artist("tribute", "Not Nine Inch Nails", 100), MbSearch.Artist("nin", "nine inch nails", 98))
        assertEquals("nin", ArtistInfoClient.bestMatch("Nine Inch Nails", results))
        assertNull(ArtistInfoClient.bestMatch("Nine Inch Nails", listOf(MbSearch.Artist("x", "Nine Inch Nails", 70))))
        assertNull(ArtistInfoClient.bestMatch("Gruntruck", emptyList()))
        assertEquals("m", ArtistInfoClient.bestMatch("Motorhead", listOf(MbSearch.Artist("m", "Motörhead", 100))))
    }

    @Test
    fun oldStyleWikipediaLink() {
        val rels = listOf(MbArtist.Relation("wikipedia", MbArtist.Relation.Url("https://en.wikipedia.org/wiki/Mot%C3%B6rhead")))
        assertEquals("Motörhead", ArtistInfoClient.wikipediaTitleFromLink(rels))
        assertNull(ArtistInfoClient.wikidataId(rels))
    }

    // The whole lookup

    private class MemoryCache : ArtistInfoCache {
        val files = mutableMapOf<String, String>()
        override fun load(key: String) = files[key]
        override fun save(key: String, text: String) { files[key] = text }
    }

    @Test
    fun looksUpMusicBrainzThenWikidataThenWikipedia() = runTest {
        val userAgents = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                userAgents += request.headers[HttpHeaders.UserAgent].orEmpty()
                val url = request.url.toString()
                when {
                    "musicbrainz.org/ws/2/artist/b7ff" in url -> json(sample)
                    "wikidata.org" in url -> json("""{"entities":{"Q11647":{"sitelinks":{"enwiki":{"title":"Nine Inch Nails"}}}}}""")
                    "wikipedia.org/api/rest_v1/page/summary/Nine_Inch_Nails" in url -> json(
                        """{"type":"standard","extract":"Nine Inch Nails is an American industrial rock band.",
                            "content_urls":{"desktop":{"page":"https://en.wikipedia.org/wiki/Nine_Inch_Nails"}}}""",
                    )
                    else -> json("", HttpStatusCode.NotFound)
                }
            },
        ) { tetsubiDefaults() }
        val cache = MemoryCache()
        var clock = 1_000_000L
        val artists = ArtistInfoClient(cache, { clock++ }, client)

        val info = artists.info("Nine Inch Nails", musicBrainzId = "b7ffd2af-418f-4be2-bdd1-22f8b48613da")
        assertEquals("Nine Inch Nails is an American industrial rock band.", info?.summary)
        assertEquals("https://en.wikipedia.org/wiki/Nine_Inch_Nails", info?.wikipediaUrl)
        assertTrue(userAgents.all { it.startsWith("Tetsubi/") && "https://tetsubi.app" in it })

        // Saved, and served from the cache by a fresh client (no network).
        val offline = ArtistInfoClient(cache, { clock }, HttpClient(MockEngine { error("no network expected") }))
        assertEquals(info?.summary, offline.info("Nine Inch Nails", "b7ffd2af-418f-4be2-bdd1-22f8b48613da")?.summary)
    }

    @Test
    fun offlineLookupIsNotRememberedAsMissing() = runTest {
        var online = false
        val client = HttpClient(
            MockEngine {
                if (!online) throw IllegalStateException("offline")
                json("""{"artists":[]}""")
            },
        ) { tetsubiDefaults() }
        val artists = ArtistInfoClient(MemoryCache(), { 0L }, client)
        assertNull(artists.info("Nobody", null))
        online = true
        assertNull(artists.info("Nobody", null)) // asked again, now really not found
    }
}
