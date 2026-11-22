package gg.dekiru.musicplayer.ui.recent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.api.JellyfinError
import gg.dekiru.musicplayer.data.models.DiscGrouping
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Home ▸ Recently Played › (as iOS): the albums played most recently,
 * newest first, discs of one album merged. The same server data as Home's
 * row, but more of it ([FETCH] albums), enough for a 5 × 5 export.
 */
class RecentlyPlayedModel(
    private val api: JellyfinApi,
    private val scope: CoroutineScope,
) {
    var albums by mutableStateOf<List<JellyfinAlbum>>(emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /** Something has been fetched (even an empty list). */
    var loaded by mutableStateOf(false)
        private set

    private var started = false
    private var job: Job? = null

    fun loadIfNeeded() {
        if (!started) refresh()
    }

    /** Fetches again (pull to refresh). What's shown stays until the new list arrives. */
    fun refresh() {
        started = true
        job?.cancel()
        isLoading = true
        errorMessage = null
        job = scope.launch {
            try {
                albums = DiscGrouping.merge(api.recentlyPlayedAlbums(limit = FETCH))
                loaded = true
            } catch (e: JellyfinError) {
                errorMessage = e.message
            }
            isLoading = false
        }
    }

    companion object {
        /** Albums asked for (Home's row asks for 12); merging discs can make it fewer. */
        const val FETCH = 60
    }
}
