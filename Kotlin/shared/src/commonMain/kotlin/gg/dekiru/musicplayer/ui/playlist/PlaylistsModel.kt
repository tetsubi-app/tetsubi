package gg.dekiru.musicplayer.ui.playlist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.api.JellyfinError
import gg.dekiru.musicplayer.data.models.JellyfinPlaylist
import gg.dekiru.musicplayer.data.models.PlaylistEntry
import gg.dekiru.musicplayer.data.settings.Preferences
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.downloads.DownloadedPlaylist
import gg.dekiru.musicplayer.ui.library.AlphaIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** As iOS: by name or by the date made. */
enum class PlaylistSort(val title: String) { NAME("Name"), DATE_ADDED("Date Added") }

/**
 * The user's playlists, for the Library's Playlists tab and Home's row.
 * Fetched each time the tab or Home shows (quietly once there's a list);
 * sorted here. The sort is remembered. Offline, nothing is asked of the
 * server and the downloaded playlists are listed, from their saved lists.
 * A downloaded playlist the server lists differently (name, songs, length)
 * has its download brought in step. A playlist with no picture of its own is
 * shown with its first song's album art, looked up once and remembered.
 */
class PlaylistsModel(
    private val api: JellyfinApi,
    private val preferences: Preferences,
    private val scope: CoroutineScope,
    private val downloads: () -> DownloadManager? = { null },
) {
    private var all by mutableStateOf<List<JellyfinPlaylist>>(emptyList())

    /** Loaded at least once (an empty list is then "no playlists", not "not yet"). */
    var loaded by mutableStateOf(false)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var isOffline by mutableStateOf(false)
        private set

    private var _sort by mutableStateOf(
        preferences.getString(SORT_KEY)?.let { saved -> PlaylistSort.entries.firstOrNull { it.title == saved } } ?: PlaylistSort.NAME,
    )

    /** The chosen sort (an unknown saved one: Name). */
    var sortOption: PlaylistSort
        get() = _sort
        set(value) {
            _sort = value
            preferences.putString(SORT_KEY, value.title)
        }

    val sortOptions: List<PlaylistSort> get() = PlaylistSort.entries

    /** Playlists with a download on this device; the screen hands them over as they change ([downloadsChanged]). */
    private var downloaded by mutableStateOf<List<DownloadedPlaylist>>(emptyList())

    fun downloadsChanged(playlists: List<DownloadedPlaylist>) {
        downloaded = playlists
    }

    /** What's shown, in the chosen order; offline, the downloaded playlists only. */
    val playlists: List<JellyfinPlaylist>
        get() = if (isOffline) {
            sort(downloaded.filter { it.isAvailableOffline }.map { it.manifest.toPlaylist() }, sortOption)
        } else {
            sort(all, sortOption)
        }

    /** A downloaded playlist's saved picture (its own, else its first song's), if it's on disk. */
    fun artPath(playlist: JellyfinPlaylist): String? = downloaded.firstOrNull { it.playlistId == playlist.id }?.coverPath

    private var loadJob: Job? = null

    fun networkChanged(online: Boolean) {
        isOffline = !online
        if (online) load() else errorMessage = null
    }

    /** Fetches the list. The spinner only when there's nothing to show yet; a failure keeps the old list. */
    fun load() {
        if (isOffline) return
        if (all.isEmpty()) isLoading = true
        errorMessage = null
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                all = api.playlists()
                loaded = true
                syncDownloads(all)
            } catch (e: JellyfinError) {
                errorMessage = "Couldn't load your playlists. ${e.message}"
            }
            isLoading = false
        }
    }

    /** Downloaded playlists being read for [syncDownloads], so one isn't read twice at once. */
    private val syncing = mutableSetOf<String>()

    /** Brings downloads in step with the playlists the server lists differently from what's saved. */
    private fun syncDownloads(listed: List<JellyfinPlaylist>) {
        val manager = downloads() ?: return
        for (playlist in listed) {
            val saved = manager.playlists.saved(playlist.id) ?: continue
            if (!differs(playlist, saved)) continue
            syncDownload(playlist.id, playlist)
        }
    }

    /** Reads a downloaded playlist's songs from the server and hands them to its download. */
    private fun syncDownload(playlistId: String, playlist: JellyfinPlaylist? = null) {
        val manager = downloads() ?: return
        if (manager.playlists.saved(playlistId) == null || !syncing.add(playlistId)) return
        scope.launch {
            try {
                manager.playlists.sync(playlistId, api.playlistTracks(playlistId), playlist)
            } catch (e: JellyfinError) {
                // Next time the list or the playlist's page loads.
            }
            syncing -= playlistId
        }
    }

    // Art for playlists without a picture of their own

    /** Playlist ID → the item whose picture stands for it (its first song's). */
    private val firstTrackArt = mutableStateMapOf<String, String>()

    /** Playlists whose first song has been asked for, so a list doesn't ask again while it scrolls. */
    private val artAsked = mutableSetOf<String>()

    /** The playlist's own picture when the server has one, else its first song's once known (else the placeholder). */
    fun artItemId(playlist: JellyfinPlaylist): String =
        if (playlist.hasArt) playlist.id else firstTrackArt[playlist.id] ?: playlist.id

    /**
     * Finds the first song's art for a playlist shown without a picture:
     * one tracks call per playlist, remembered. Not offline, and not for a
     * playlist with no songs.
     */
    fun lookUpArt(playlist: JellyfinPlaylist) {
        if (playlist.hasArt || playlist.songCount <= 0 || isOffline || playlist.id in artAsked) return
        artAsked += playlist.id
        scope.launch {
            try {
                api.playlistTracks(playlist.id).firstOrNull()?.let { firstTrackArt[playlist.id] = it.artItemId }
            } catch (e: JellyfinError) {
                // The placeholder stays until the playlist changes or the app restarts.
            }
        }
    }

    /** A playlist's page has its songs: its first one's art is the playlist's (none when it's empty). */
    fun rememberArt(playlistId: String, entries: List<PlaylistEntry>) {
        artAsked += playlistId
        val first = entries.firstOrNull()
        if (first != null) firstTrackArt[playlistId] = first.artItemId else firstTrackArt.remove(playlistId)
    }

    /**
     * A playlist was made, added to, renamed, edited or deleted: the list
     * is fetched again, and the playlist's art is looked up again when
     * it's next shown (unless `entries`, its songs now, are given).
     */
    fun changed(playlistId: String? = null, entries: List<PlaylistEntry>? = null) {
        if (playlistId != null) {
            if (entries != null) {
                rememberArt(playlistId, entries)
            } else {
                artAsked -= playlistId
                // Songs were added from elsewhere in the app: a downloaded playlist gets them.
                syncDownload(playlistId)
            }
        }
        load()
    }

    companion object {
        const val SORT_KEY = "playlists.sort"

        /** In the offline Playlists tab and Home row when no playlist is downloaded. */
        const val NONE_DOWNLOADED = "No downloaded playlists yet."

        /** The server's listing no longer matches the saved list: another name, song count or length. */
        fun differs(listed: JellyfinPlaylist, saved: DownloadedPlaylist): Boolean =
            listed.displayName != saved.name || listed.songCount != saved.songCount ||
                kotlin.math.abs(listed.durationSeconds - saved.durationSeconds) > 2

        /** Name follows the A–Z order; Date Added is newest first, undated last, then by name. */
        fun sort(playlists: List<JellyfinPlaylist>, by: PlaylistSort): List<JellyfinPlaylist> {
            val byName = playlists.sortedWith(AlphaIndex.comparator { it.displayName })
            return when (by) {
                PlaylistSort.NAME -> byName
                PlaylistSort.DATE_ADDED -> byName.sortedByDescending { it.dateCreated.orEmpty() }
            }
        }

        /** "1 song", "12 songs". */
        fun songCount(count: Int): String = if (count == 1) "1 song" else "$count songs"

        /** "12 min", "1 h 12 min", "2 h"; whole minutes, rounded down as on iOS; under a minute, "1 min". */
        fun playingTime(seconds: Long): String {
            val minutes = maxOf(1, seconds / 60)
            val h = minutes / 60
            val m = minutes % 60
            return when {
                h == 0L -> "$m min"
                m == 0L -> "$h h"
                else -> "$h h $m min"
            }
        }

        /** "4 songs · 16 min"; just the count ("0 songs") when there's no playing time. */
        fun summary(count: Int, seconds: Long): String =
            if (seconds > 0) "${songCount(count)} · ${playingTime(seconds)}" else songCount(count)

        fun summary(playlist: JellyfinPlaylist): String = summary(playlist.songCount, playlist.durationSeconds)
    }
}
