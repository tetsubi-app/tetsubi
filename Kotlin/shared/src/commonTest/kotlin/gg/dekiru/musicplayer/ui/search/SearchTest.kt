package gg.dekiru.musicplayer.ui.search

import gg.dekiru.musicplayer.data.api.FakeServer
import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import gg.dekiru.musicplayer.data.api.path
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinPerson
import gg.dekiru.musicplayer.data.models.JellyfinPlaylist
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import gg.dekiru.musicplayer.downloads.LocalAlbum
import gg.dekiru.musicplayer.downloads.LocalSearch
import gg.dekiru.musicplayer.playback.settle
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Search: history, title + artist search, the debounce, and offline switching (as the iOS tests). */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchTest {

    // History (as iOS SearchSupportTests)

    @Test
    fun historyKeepsRefinedTermOnly() {
        val history = SearchHistory(InMemoryPreferences())
        history.record("me")
        history.record("mell")
        history.record("mellon collie")
        assertEquals(listOf("mellon collie"), history.terms)
        history.record("Gish")
        history.record("MELLON COLLIE")
        assertEquals(listOf("MELLON COLLIE", "Gish"), history.terms)
    }

    @Test
    fun historyIgnoresShortTermsAndCaps() {
        val history = SearchHistory(InMemoryPreferences())
        history.record("a")
        history.record("   b  ")
        assertEquals(emptyList(), history.terms)
        for (i in 0 until 15) history.record("album $i x$i")
        assertEquals(SearchHistory.LIMIT, history.terms.size)
        assertEquals("album 14 x14", history.terms.first(), "newest first")
        assertEquals("album 5 x5", history.terms.last())
        history.clear()
        assertEquals(emptyList(), history.terms)
    }

    @Test
    fun historyIsStoredUnderTheIosKey() {
        val prefs = InMemoryPreferences()
        SearchHistory(prefs).record("  Siamese Dream ")
        assertEquals("search.history", SearchHistory.KEY)
        assertEquals("""["Siamese Dream"]""", prefs.getString("search.history"))
        assertEquals(listOf("Siamese Dream"), SearchHistory(prefs).terms, "read back by a new instance")
        prefs.putString("search.history", "not json")
        assertEquals(emptyList(), SearchHistory(prefs).terms)
    }

    // Combining (as iOS AlbumSearchTests)

    private fun album(id: String, name: String, artist: String = "Gruntruck") =
        JellyfinAlbum(id = id, name = name, albumArtists = listOf(JellyfinPerson(id = "ar", name = artist)), type = "MusicAlbum")

    @Test
    fun artistMatchesAppearWhenNoTitleMatches() {
        val results = AlbumSearch.combine(
            emptyList(),
            listOf(album("a", "Inside Yours"), album("d1", "Inside Yours/Push (Disc 1)"), album("d2", "Inside Yours/Push (Disc 2)")),
        )
        assertEquals(listOf("Inside Yours", "Inside Yours/Push"), results.map { it.displayName })
    }

    @Test
    fun titleMatchesFirstWithoutDuplicates() {
        val results = AlbumSearch.combine(listOf(album("a", "Inside Yours")), listOf(album("b", "Push"), album("a", "Inside Yours")))
        assertEquals(listOf("a", "b"), results.map { it.id })
    }

    // The model

    /** Titles match "Push"; the artist search finds Gruntruck, whose albums include it again. */
    private fun server(failing: Boolean = false, playlistsFailing: Boolean = false) = FakeServer { request ->
        val q = request.url.parameters
        when {
            failing -> json("", HttpStatusCode.InternalServerError)
            request.path == "/Users/u1/Items" && q["IncludeItemTypes"] == "Playlist" ->
                if (playlistsFailing) {
                    json("", HttpStatusCode.InternalServerError)
                } else {
                    json("""{"Items":[{"Id":"p1","Name":"Push it","ChildCount":3,"CumulativeRunTimeTicks":6000000000}]}""")
                }
            request.path == "/Users/u1/Items" && q["SearchTerm"] != null -> json("""{"Items":[{"Id":"t1","Name":"Push"}]}""")
            request.path == "/Artists/AlbumArtists" -> json("""{"Items":[{"Id":"ar1","Name":"Gruntruck"}]}""")
            request.path == "/Items" && q["AlbumArtistIds"] == "ar1" ->
                json("""{"Items":[{"Id":"b1","Name":"Inside Yours"},{"Id":"t1","Name":"Push"}]}""")
            else -> json("""{"Items":[]}""")
        }
    }

    private val downloaded = listOf(
        LocalAlbum("l1", "Mellon Collie", "Smashing Pumpkins"),
        LocalAlbum("l2", "Push", "Gruntruck"),
    )

    /** The playlists listed offline. */
    private val downloadedPlaylists = listOf(
        JellyfinPlaylist(id = "dp1", name = "Pumpkins Café mix", songCount = 2),
        JellyfinPlaylist(id = "dp2", name = "Road trip"),
    )

    private class Setup(val server: FakeServer, val model: SearchModel, val history: SearchHistory, var online: Boolean)

    private fun TestScope.setup(failing: Boolean = false, online: Boolean = true, playlistsFailing: Boolean = false): Setup {
        val server = server(failing, playlistsFailing)
        val history = SearchHistory(InMemoryPreferences())
        lateinit var s: Setup
        val model = SearchModel(server.api(token = "t", userId = "u1"), history, backgroundScope, { s.online }, { downloaded }, { downloadedPlaylists })
        s = Setup(server, model, history, online)
        return s
    }

    @Test
    fun searchesTitlesAndArtistsAndRemembersTheTerm() = runTest {
        val s = setup()
        s.model.onQueryChange("push")
        settle()
        assertEquals(listOf("t1", "b1"), s.model.results.map { it.id })
        // The artists whose name matched are results of their own.
        assertEquals(listOf("ar1"), s.model.artistResults.map { it.id })
        assertTrue(s.model.hasSearched)
        assertFalse(s.model.isLoading)
        assertFalse(s.model.isOfflineResults)
        assertEquals(listOf("push"), s.history.terms)
    }

    // Playlists

    @Test
    fun findsPlaylistsByNameAlongsideAlbums() = runTest {
        val s = setup()
        s.model.onQueryChange("push")
        settle()
        assertEquals(listOf("t1", "b1"), s.model.results.map { it.id })
        val playlist = s.model.playlistResults.single()
        assertEquals("p1", playlist.id)
        assertEquals("Push it", playlist.displayName)
        assertEquals(3, playlist.songCount)
        assertEquals(600, playlist.durationSeconds)
        assertEquals(emptyList(), s.model.localPlaylistResults)
        val asked = s.server.requests.single { it.url.parameters["IncludeItemTypes"] == "Playlist" }
        assertEquals("push", asked.url.parameters["SearchTerm"])

        // Emptying the box clears them.
        s.model.onQueryChange("")
        assertEquals(emptyList(), s.model.playlistResults)
    }

    @Test
    fun aFailedPlaylistSearchStillShowsAlbums() = runTest {
        val s = setup(playlistsFailing = true)
        s.model.onQueryChange("push")
        settle()
        assertEquals(listOf("t1", "b1"), s.model.results.map { it.id })
        assertEquals(emptyList(), s.model.playlistResults)
        assertFalse(s.model.isOfflineResults)
        assertEquals(listOf("push"), s.history.terms)
    }

    @Test
    fun offlineMatchesDownloadedPlaylistsByName() = runTest {
        val s = setup(online = false)
        s.model.onQueryChange("pumpkins")
        settle()
        assertTrue(s.model.isOfflineResults)
        assertEquals(listOf("l1"), s.model.localResults.map { it.id })
        assertEquals(listOf("dp1"), s.model.localPlaylistResults.map { it.id })
        assertEquals(emptyList(), s.model.playlistResults)
        assertTrue(s.server.requests.isEmpty())

        // Back online: the server's answer replaces them.
        s.online = true
        s.model.networkChanged(online = true)
        settle()
        assertEquals(emptyList(), s.model.localPlaylistResults)
        assertEquals(listOf("p1"), s.model.playlistResults.map { it.id })
    }

    @Test
    fun aFailedSearchFallsBackToDownloadedPlaylistsToo() = runTest {
        val s = setup(failing = true)
        s.model.onQueryChange("road")
        settle()
        assertTrue(s.model.isOfflineResults)
        assertEquals(emptyList(), s.model.localResults)
        assertEquals(listOf("dp2"), s.model.localPlaylistResults.map { it.id })
    }

    @Test
    fun theWordsOnTheScreen() {
        assertEquals("Albums, artists or playlists", SearchModel.FIELD_HINT)
        assertEquals("Type an album, artist or playlist name.", SearchModel.EMPTY_HINT)
        assertEquals("Playlists (2)", SearchModel.playlistsHeader(2))
        assertEquals("Results (3)", SearchModel.albumsHeader(albums = 3, otherSections = 0))
        assertEquals("Albums (3)", SearchModel.albumsHeader(albums = 3, otherSections = 2))
        assertEquals("No albums or playlists found for “gish”.", SearchModel.noResultsMessage("gish"))
        assertEquals("No downloaded albums or playlists match “gish”.", SearchModel.noDownloadsMessage("gish"))
    }

    @Test
    fun downloadedPlaylistsMatchOnEveryWordOfTheName() {
        fun ids(query: String) = LocalSearch.matchingPlaylists(query, downloadedPlaylists).map { it.id }
        assertEquals(listOf("dp1"), ids("cafe PUMPKINS"), "any order, case and accents ignored")
        assertEquals(listOf("dp1"), ids("café"))
        assertEquals(listOf("dp2"), ids("  trip "))
        assertEquals(emptyList(), ids("road pumpkins"), "every word must be in the one name")
        assertEquals(emptyList(), ids("   "))
        assertEquals(listOf("dp1", "dp2"), ids("p"), "in the order given")
    }

    @Test
    fun waitsForTypingToStop() = runTest {
        val s = setup()
        s.model.onQueryChange("p")
        advanceTimeBy(SearchModel.DEBOUNCE_MS - 1)
        runCurrent()
        s.model.onQueryChange("pu")
        advanceTimeBy(SearchModel.DEBOUNCE_MS - 1)
        runCurrent()
        assertTrue(s.server.requests.isEmpty(), "nothing asked while typing")
        advanceTimeBy(2)
        settle()
        val terms = s.server.requests.mapNotNull { it.url.parameters["SearchTerm"] ?: it.url.parameters["searchTerm"] }
        assertEquals(setOf("pu"), terms.toSet(), "only the last term is searched")
        assertEquals(listOf("pu"), s.history.terms)

        // Adding a space is the same search.
        val asked = s.server.requests.size
        s.model.onQueryChange("pu ")
        settle()
        assertEquals(asked, s.server.requests.size)
    }

    @Test
    fun emptyingTheBoxClearsResultsAndShowsHistory() = runTest {
        val s = setup()
        s.model.onQueryChange("push")
        settle()
        s.model.onQueryChange("pushed")
        s.model.onQueryChange("   ")
        settle()
        assertEquals(emptyList(), s.model.results)
        assertFalse(s.model.hasSearched)
        assertEquals(listOf("push"), s.model.recent)
        s.model.clearHistory()
        assertEquals(emptyList(), s.model.recent)
        assertEquals(emptyList(), s.history.terms)
        // A recent search runs again.
        s.model.useHistory("push")
        settle()
        assertEquals("push", s.model.query)
        assertEquals(listOf("t1", "b1"), s.model.results.map { it.id })
    }

    @Test
    fun offlineSearchesDownloadsThenTheServerOnReconnect() = runTest {
        val s = setup(online = false)
        s.model.onQueryChange("pumpkins")
        settle()
        assertTrue(s.model.isOfflineResults)
        assertEquals(listOf("l1"), s.model.localResults.map { it.id })
        assertTrue(s.server.requests.isEmpty(), "nothing asked while offline")
        assertEquals(emptyList(), s.history.terms, "offline searches aren't remembered")

        s.online = true
        s.model.networkChanged(online = true)
        settle()
        assertFalse(s.model.isOfflineResults)
        assertEquals(emptyList(), s.model.localResults)
        assertTrue(s.server.requests.isNotEmpty())
        assertEquals(listOf("pumpkins"), s.history.terms)
    }

    @Test
    fun aFailedSearchFallsBackToDownloads() = runTest {
        val s = setup(failing = true)
        s.model.onQueryChange("push")
        settle()
        assertTrue(s.model.isOfflineResults)
        assertEquals(listOf("l2"), s.model.localResults.map { it.id })
        assertFalse(s.model.isLoading)
        assertEquals(emptyList(), s.history.terms)
    }

    @Test
    fun theChipsChooseWhichKindsOfResultShow() {
        assertEquals(listOf(true, true, true), listOf(SearchFilter.ALL.showsAlbums, SearchFilter.ALL.showsArtists, SearchFilter.ALL.showsPlaylists))
        assertEquals(listOf(true, false, false), listOf(SearchFilter.ALBUMS.showsAlbums, SearchFilter.ALBUMS.showsArtists, SearchFilter.ALBUMS.showsPlaylists))
        assertEquals(listOf(false, true, false), listOf(SearchFilter.ARTISTS.showsAlbums, SearchFilter.ARTISTS.showsArtists, SearchFilter.ARTISTS.showsPlaylists))
        assertEquals(listOf(false, false, true), listOf(SearchFilter.PLAYLISTS.showsAlbums, SearchFilter.PLAYLISTS.showsArtists, SearchFilter.PLAYLISTS.showsPlaylists))
        assertEquals(listOf("All", "Albums", "Artists", "Playlists"), SearchFilter.entries.map { it.title })
        assertEquals("No artists found for “x”.", SearchModel.noResultsMessage("x", SearchFilter.ARTISTS))
        assertEquals(SearchModel.noResultsMessage("x"), SearchModel.noResultsMessage("x", SearchFilter.ALL))
        assertEquals("Artists (2)", SearchModel.artistsHeader(2))
        assertEquals("Albums (3)", SearchModel.albumsHeader(3, otherSections = 1))
    }
}
