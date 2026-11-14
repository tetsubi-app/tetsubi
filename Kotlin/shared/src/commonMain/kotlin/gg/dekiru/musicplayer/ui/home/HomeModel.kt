package gg.dekiru.musicplayer.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.api.JellyfinError
import gg.dekiru.musicplayer.data.models.DiscGrouping
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinPerson
import gg.dekiru.musicplayer.data.models.OnThisDay
import gg.dekiru.musicplayer.data.models.OnThisDayEntry
import gg.dekiru.musicplayer.data.settings.FavoritesStore
import gg.dekiru.musicplayer.downloads.DownloadedAlbum
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

/**
 * Home's rows (as `HomeViewModel` on iOS): On This Day, Favourites,
 * Recently Played, Recently Added, Random. All load at once; discs of one
 * album are merged in every row. A row that fails stays empty; the first
 * failure is shown above the rows. Offline, Home shows only the downloaded
 * albums, and reloads when the connection comes back.
 */
class HomeModel(
    private val api: JellyfinApi,
    private val favorites: FavoritesStore,
    private val scope: CoroutineScope,
    private val today: () -> LocalDate,
) {
    var onThisDay by mutableStateOf<List<OnThisDayEntry>>(emptyList())
        private set
    var favoriteAlbums by mutableStateOf<List<JellyfinAlbum>>(emptyList())
        private set
    var recentlyPlayed by mutableStateOf<List<JellyfinAlbum>>(emptyList())
        private set
    var recentlyAdded by mutableStateOf<List<JellyfinAlbum>>(emptyList())
        private set
    var randomAlbums by mutableStateOf<List<JellyfinAlbum>>(emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /** No network: the server rows give way to the downloaded albums. */
    var isOffline by mutableStateOf(false)
        private set

    private var loadJob: Job? = null
    private var loadedOnce = false

    /** First appearance only; going back to Home keeps what's there. */
    fun loadIfNeeded() {
        if (!loadedOnce) refresh()
    }

    /**
     * The network now (Home calls this with the current state, then each
     * change). Loads on the first online appearance; reloads on reconnect
     * (as iOS). Nothing is asked of the server while offline.
     */
    fun networkChanged(online: Boolean) {
        val wasOffline = isOffline
        isOffline = !online
        if (!online) return
        if (wasOffline) refresh() else loadIfNeeded()
    }

    /**
     * The server can't match "this month and day in any year", so: every
     * album's release date only (a small answer), matched here; then the
     * details of just the matches, at most [OnThisDay.LIMIT] shown.
     */
    private suspend fun loadOnThisDay(): List<OnThisDayEntry> {
        val day = today()
        // Room for several discs of one album before the cap.
        val ids = OnThisDay.matches(api.albumReleaseDates(), day).take(OnThisDay.LIMIT * 4).map { it.album.id }
        if (ids.isEmpty()) return emptyList()
        return OnThisDay.entries(api.albumsByIds(ids), day).take(OnThisDay.LIMIT)
    }

    fun refresh() {
        loadedOnce = true
        loadJob?.cancel()
        isLoading = true
        errorMessage = null
        loadJob = scope.launch {
            var firstError: String? = null
            suspend fun <T> row(fetch: suspend () -> T, show: (T) -> Unit) {
                try {
                    show(fetch())
                } catch (e: JellyfinError) {
                    if (firstError == null) firstError = e.message
                }
            }
            listOf(
                async { row({ loadOnThisDay() }) { onThisDay = it } },
                async { row({ DiscGrouping.merge(favorites.ordered(api.favoriteAlbums())) }) { favoriteAlbums = it } },
                async { row({ DiscGrouping.merge(api.recentlyPlayedAlbums()) }) { recentlyPlayed = it } },
                async { row({ DiscGrouping.merge(api.recentlyAddedAlbums()) }) { recentlyAdded = it } },
                async { row({ DiscGrouping.merge(api.randomAlbums()) }) { randomAlbums = it } },
            ).awaitAll()
            errorMessage = firstError
            isLoading = false
        }
    }

    companion object {
        /** The "Downloaded" row: newest download first, discs of one album merged. */
        fun downloadedRow(albums: List<DownloadedAlbum>): List<JellyfinAlbum> = DiscGrouping.merge(
            albums.map {
                JellyfinAlbum(
                    id = it.albumId, name = it.title, productionYear = it.year,
                    albumArtists = listOf(JellyfinPerson(name = it.artist)),
                )
            },
        )
    }
}
