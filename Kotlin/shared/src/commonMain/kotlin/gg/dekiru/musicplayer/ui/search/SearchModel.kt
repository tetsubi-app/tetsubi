package gg.dekiru.musicplayer.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.api.JellyfinError
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinPlaylist
import gg.dekiru.musicplayer.downloads.LocalAlbum
import gg.dekiru.musicplayer.downloads.LocalSearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Search (as iOS `SearchViewModel`): once typing stops for [DEBOUNCE_MS],
 * playlists by name and albums by title or artist ([AlbumSearch]) from
 * the server. Offline, or when the album search fails, the downloaded albums
 * and playlists that match instead.
 * Successful server searches go into [SearchHistory], shown while the
 * box is empty.
 */
class SearchModel(
    private val api: JellyfinApi,
    private val history: SearchHistory,
    private val scope: CoroutineScope,
    private val isOnline: () -> Boolean,
    /** The downloaded albums, for searching offline. */
    private val downloadedAlbums: () -> List<LocalAlbum> = { emptyList() },
    /** The playlists listed offline, for searching offline. */
    private val downloadedPlaylists: () -> List<JellyfinPlaylist> = { emptyList() },
) {
    /** What's in the box. */
    var query by mutableStateOf("")
        private set
    var results by mutableStateOf<List<JellyfinAlbum>>(emptyList())
        private set

    /** Playlists whose name matches, shown above the albums. */
    var playlistResults by mutableStateOf<List<JellyfinPlaylist>>(emptyList())
        private set

    var isLoading by mutableStateOf(false)
        private set

    /** True after a search of a non-empty term finished. */
    var hasSearched by mutableStateOf(false)
        private set

    /** Downloaded albums matching the query, when the server can't be reached. */
    var localResults by mutableStateOf<List<LocalAlbum>>(emptyList())
        private set

    /** Downloaded playlists matching the query by name, when the server can't be reached. */
    var localPlaylistResults by mutableStateOf<List<JellyfinPlaylist>>(emptyList())
        private set

    /** True when the results shown come from downloads only. */
    var isOfflineResults by mutableStateOf(false)
        private set

    /** Recent searches, shown while the box is empty. */
    var recent by mutableStateOf(history.terms)
        private set

    private var job: Job? = null
    private val term: String get() = query.trim()

    /** The box changed: searches again after the pause. */
    fun onQueryChange(text: String) {
        val before = term
        query = text
        // Only spaces added or removed: the same search.
        if (term == before && (job != null || hasSearched)) return
        job?.cancel()
        job = null
        val term = term
        if (term.isEmpty()) {
            results = emptyList()
            playlistResults = emptyList()
            localResults = emptyList()
            localPlaylistResults = emptyList()
            isOfflineResults = false
            hasSearched = false
            isLoading = false
            recent = history.terms
            return
        }
        job = scope.launch {
            delay(DEBOUNCE_MS)
            perform(term)
        }
    }

    /** Re-runs a recent search. */
    fun useHistory(term: String) = onQueryChange(term)

    fun clearHistory() {
        history.clear()
        recent = emptyList()
    }

    /** Back online with only downloads shown: asks the server now. */
    fun networkChanged(online: Boolean) {
        if (!online || !isOfflineResults || term.isEmpty()) return
        job?.cancel()
        val term = term
        job = scope.launch { perform(term) }
    }

    private suspend fun perform(term: String) {
        if (!isOnline()) return searchDownloads(term)
        isLoading = true
        val (albums, playlists) = try {
            coroutineScope {
                // Only the album search failing is an error: playlists are then left out.
                val playlists = async {
                    try {
                        api.searchPlaylists(term)
                    } catch (e: JellyfinError) {
                        emptyList()
                    }
                }
                AlbumSearch.run(api, term) to playlists.await()
            }
        } catch (e: JellyfinError) {
            // Can't reach the server: the downloads instead.
            return searchDownloads(term)
        }
        results = albums
        playlistResults = playlists
        localResults = emptyList()
        localPlaylistResults = emptyList()
        isOfflineResults = false
        hasSearched = true
        isLoading = false
        history.record(term)
    }

    private fun searchDownloads(term: String) {
        results = emptyList()
        playlistResults = emptyList()
        localResults = LocalSearch.matches(term, downloadedAlbums())
        localPlaylistResults = LocalSearch.matchingPlaylists(term, downloadedPlaylists())
        isOfflineResults = true
        hasSearched = true
        isLoading = false
    }

    companion object {
        /** As iOS: 0.4 s after the last keystroke. */
        const val DEBOUNCE_MS = 400L

        const val FIELD_HINT = "Albums, artists or playlists"
        const val EMPTY_HINT = "Type an album, artist or playlist name."

        fun playlistsHeader(playlists: Int): String = "Playlists ($playlists)"

        /** The albums' section title: "Results (3)" on their own, "Albums (3)" under a Playlists section. */
        fun albumsHeader(albums: Int, playlists: Int): String = if (playlists == 0) "Results ($albums)" else "Albums ($albums)"

        fun noResultsMessage(term: String): String = "No albums or playlists found for “$term”."

        /** What the offline results say when nothing matches. */
        fun noDownloadsMessage(term: String): String = "No downloaded albums or playlists match “$term”."
    }
}
