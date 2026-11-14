package gg.dekiru.musicplayer.ui.playlist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.api.JellyfinError
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinPlaylist
import gg.dekiru.musicplayer.data.models.PlaylistEntry
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.downloads.DownloadedPlaylist
import gg.dekiru.musicplayer.playback.PlayerController
import gg.dekiru.musicplayer.ui.home.HomeModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One playlist's page: its tracks in playlist order. The song count and
 * playing time are counted from the loaded tracks (the server's own lag
 * after an edit).
 *
 * Edit mode (when the server says the user may edit): each change shows
 * at once and the whole new order goes to the server, one send at a time;
 * a newer order replaces one still waiting. A send that fails reloads the
 * page from the server and says so. `list` (the Library's and Home's
 * playlists) is told of each change.
 *
 * A downloaded playlist is kept in step: each load and each change saved
 * goes to its download, and deleting the playlist removes it. Offline, a
 * downloaded playlist's page is filled from its saved list ([loadOffline]).
 */
class PlaylistModel(
    playlist: JellyfinPlaylist,
    private val api: JellyfinApi,
    private val scope: CoroutineScope,
    private val list: PlaylistsModel? = null,
    private val downloads: () -> DownloadManager? = { null },
) {
    /** As listed, with the new name after a rename. */
    var playlist by mutableStateOf(playlist)
        private set
    var entries by mutableStateOf<List<PlaylistEntry>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    /** "Go to Album" couldn't fetch the album. */
    var albumError by mutableStateOf<String?>(null)
        private set

    /** Why the last Play, Shuffle or tapped song was refused (not downloaded, no connection): shown for six seconds, as iOS, or until one plays. */
    var playNotice by mutableStateOf<String?>(null)
        private set

    /**
     * Plays the playlist from the page: Play, Shuffle (`shuffled`) or a
     * tapped song (`startingAt`, its position). A refusal becomes
     * [playNotice] and nothing else changes.
     */
    fun play(player: PlayerController, startingAt: Int? = null, shuffled: Boolean = false, openNowPlaying: Boolean = true) {
        val notice = player.play(playlist, entries, startingAt = startingAt, openNowPlaying = openNowPlaying, shuffled = shuffled)
        playNotice = notice
        noticeJob?.cancel()
        noticeJob = notice?.let {
            scope.launch {
                delay(PLAY_NOTICE_MILLIS)
                playNotice = null
            }
        }
    }

    private var noticeJob: Job? = null

    /** Back online: everything can be played again. */
    fun clearPlayNotice() {
        noticeJob?.cancel()
        playNotice = null
    }

    /** The server lets the user change this playlist: the page offers Edit. */
    var canEdit by mutableStateOf(false)
        private set
    var isEditing by mutableStateOf(false)
        private set

    /** A change couldn't be saved, or the playlist couldn't be deleted. */
    var saveError by mutableStateOf<String?>(null)
        private set
    var isDeleting by mutableStateOf(false)
        private set

    private var loadJob: Job? = null

    /** Each time the page shows; the spinner only the first time. Not while changes are being sent. */
    fun load() {
        if (loadJob?.isActive == true || sendJob?.isActive == true) return
        if (!loaded) isLoading = true
        errorMessage = null
        loadJob = scope.launch {
            try {
                val fetched = api.playlistTracks(playlist.id)
                // A change made while this was on its way wins: it's what the server is being sent.
                if (sendJob?.isActive != true) {
                    entries = fetched
                    loaded = true
                    list?.rememberArt(playlist.id, fetched)
                    downloads()?.playlists?.sync(playlist.id, fetched, playlist)
                }
            } catch (e: JellyfinError) {
                errorMessage = "Couldn't load this playlist. ${e.message}"
            }
            isLoading = false
        }
    }

    /**
     * No connection: a downloaded playlist's page shows its saved list
     * (nothing changes for one that isn't downloaded, or a page that already
     * has the server's list). Editing ends.
     */
    fun loadOffline() {
        isEditing = false
        canEdit = false
        if (loaded) return
        val saved = downloads()?.playlists?.saved(playlist.id)?.takeIf { it.isAvailableOffline } ?: return
        entries = saved.manifest.toEntries()
        playlist = saved.manifest.toPlaylist()
        loaded = true
        errorMessage = null
    }

    /** This playlist's download, if it has one (for the saved pictures). */
    val saved: DownloadedPlaylist? get() = downloads()?.playlists?.saved(playlist.id)

    /** Asks the server whether the user may edit; no answer means no. */
    fun checkCanEdit() {
        scope.launch {
            canEdit = api.canEditPlaylist(playlist.id)
            if (!canEdit) isEditing = false
        }
    }

    // Editing

    fun startEditing() {
        if (canEdit && loaded) isEditing = true
    }

    fun doneEditing() {
        isEditing = false
    }

    fun dismissSaveError() {
        saveError = null
    }

    /** Moves the row at `from` to `to` (places in the list). */
    fun move(from: Int, to: Int) {
        if (!canEdit || from == to || from !in entries.indices || to !in entries.indices) return
        val changed = entries.toMutableList()
        changed.add(to, changed.removeAt(from))
        setOrder(changed)
    }

    /** Takes the row at `position` out (only that one, when the song is there twice). */
    fun remove(position: Int) {
        if (!canEdit || position !in entries.indices) return
        setOrder(entries.filterIndexed { i, _ -> i != position })
    }

    /** Renames the playlist; the name is trimmed and an empty one is refused (false). */
    fun rename(name: String): Boolean {
        val trimmed = PlaylistName.cleaned(name)
        if (!canEdit || trimmed == null) return false
        if (trimmed == playlist.name) return true
        playlist = playlist.copy(name = trimmed)
        pendingName = trimmed
        send()
        return true
    }

    /** Deletes the playlist from the server, after any change still being sent; `onDeleted` leaves the page. */
    fun delete(onDeleted: () -> Unit) {
        if (!canEdit || isDeleting) return
        isDeleting = true
        saveError = null
        pendingOrder = null
        pendingName = null
        scope.launch {
            sendJob?.join()
            try {
                api.deletePlaylist(playlist.id)
                isEditing = false
                downloads()?.playlists?.delete(playlist.id)
                list?.changed(playlist.id, emptyList())
                onDeleted()
            } catch (e: JellyfinError) {
                saveError = PlaylistWording.deleteFailed(e.message)
            }
            isDeleting = false
        }
    }

    /** The order and the name waiting to be sent: only the newest of each. */
    private var pendingOrder: List<String>? = null
    private var pendingName: String? = null

    /** The name the server has. */
    private var savedName = playlist.name
    private var sendJob: Job? = null

    private fun setOrder(changed: List<PlaylistEntry>) {
        entries = changed.mapIndexed { i, entry -> entry.copy(position = i) }
        pendingOrder = entries.map { it.track.id }
        send()
    }

    /** Sends what's waiting, one request at a time, until nothing is. */
    private fun send() {
        if (sendJob?.isActive == true) return
        saveError = null
        sendJob = scope.launch {
            while (true) {
                val name = pendingName
                val order = pendingOrder
                if (name == null && order == null) break
                pendingName = null
                pendingOrder = null
                try {
                    if (name != null) {
                        api.renamePlaylist(playlist.id, name)
                        savedName = name
                    }
                    if (order != null) api.setPlaylistItems(playlist.id, order)
                } catch (e: JellyfinError) {
                    // What the page shows is no longer what the server has: show the server's.
                    saveError = PlaylistWording.saveFailed(e.message)
                    try {
                        val fetched = api.playlistTracks(playlist.id)
                        entries = fetched
                    } catch (reload: JellyfinError) {
                        errorMessage = "Couldn't load this playlist. ${reload.message}"
                    }
                    playlist = playlist.copy(name = savedName)
                    pendingName = null
                    pendingOrder = null
                }
            }
            // The download follows what the server now has (not a list it refused and couldn't be re-read).
            if (errorMessage == null) downloads()?.playlists?.sync(playlist.id, entries, playlist)
            list?.changed(playlist.id, entries)
        }
    }

    val songCount: Int get() = entries.size
    val durationSeconds: Long get() = entries.sumOf { it.track.durationSeconds }

    /** The server's numbers until the tracks are in, then the loaded tracks'. */
    val summary: String
        get() = if (loaded) PlaylistsModel.summary(songCount, durationSeconds) else PlaylistsModel.summary(playlist)

    /** The playlist's own picture when the server has one, else the first track's album art (else the placeholder). */
    val artItemId: String get() = artItemId(playlist, entries)

    /** "Go to Album": fetches the row's album and hands it to `open`. */
    fun openAlbum(entry: PlaylistEntry, open: (JellyfinAlbum) -> Unit) {
        val albumId = entry.albumId ?: return
        albumError = null
        scope.launch {
            try {
                open(api.album(albumId))
            } catch (e: JellyfinError) {
                // No server: a downloaded album still opens.
                val local = downloads()?.downloadedAlbum(albumId)?.let { HomeModel.downloadedRow(listOf(it)).firstOrNull() }
                if (local != null) open(local) else albumError = "Couldn't open the album. ${e.message}"
            }
        }
    }

    companion object {
        /** How long a refused play's notice stays, as iOS's error notices. */
        const val PLAY_NOTICE_MILLIS = 6_000L

        fun artItemId(playlist: JellyfinPlaylist, entries: List<PlaylistEntry>): String =
            if (playlist.hasArt) playlist.id else entries.firstOrNull()?.artItemId ?: playlist.id
    }
}
