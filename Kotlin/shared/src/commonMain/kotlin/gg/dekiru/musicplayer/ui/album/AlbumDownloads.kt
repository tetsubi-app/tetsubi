package gg.dekiru.musicplayer.ui.album

import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.downloads.DownloadAlbumState
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.downloads.DownloadStatus

/** The album page's Download button. */
enum class DownloadButton { DOWNLOAD, DOWNLOADING, DOWNLOADED }

/**
 * An album page's download state, from the manager's lists (as the
 * download parts of iOS `AlbumDetailViewModel`). Built from the collected
 * `active` and `downloaded`, so the page follows them as they change.
 */
class AlbumDownloads(
    private val discs: List<JellyfinAlbum>,
    private val active: Map<String, DownloadAlbumState>,
    private val downloadedIds: Set<String>,
) {
    val isMultiDisc: Boolean get() = discs.size > 1

    fun state(disc: JellyfinAlbum): DownloadAlbumState? = active[disc.id]

    fun isDiscDownloaded(disc: JellyfinAlbum): Boolean = disc.id in downloadedIds && disc.id !in active

    /** Every disc is on the device. */
    val isDownloaded: Boolean get() = discs.isNotEmpty() && discs.all(::isDiscDownloaded)

    /** Something is under way and nothing is left to start. */
    val isDownloading: Boolean
        get() = discs.any { it.id in active } && discs.all { it.id in active || isDiscDownloaded(it) }

    val button: DownloadButton
        get() = when {
            isDownloaded -> DownloadButton.DOWNLOADED
            isDownloading -> DownloadButton.DOWNLOADING
            else -> DownloadButton.DOWNLOAD
        }

    /** "Downloaded", "Downloading", "Download all discs" or "Download". */
    val buttonLabel: String
        get() = when (button) {
            DownloadButton.DOWNLOADED -> "Downloaded"
            DownloadButton.DOWNLOADING -> "Downloading"
            DownloadButton.DOWNLOAD -> if (isMultiDisc) "Download all discs" else "Download"
        }

    /** A single-disc album's download under way (multi-disc albums show it per disc). */
    val singleState: DownloadAlbumState? get() = if (isMultiDisc) null else discs.firstOrNull()?.let(::state)

    /** Every disc together, 0…1 (downloaded discs count as done): a set's progress ring. */
    val overallProgress: Double
        get() = if (discs.isEmpty()) 0.0 else discs.sumOf { active[it.id]?.progress ?: if (isDiscDownloaded(it)) 1.0 else 0.0 } / discs.size

    /** The discs still to start. */
    val toStart: List<JellyfinAlbum> get() = discs.filter { it.id !in active && !isDiscDownloaded(it) }

    companion object {
        /** Under the bar: "Download failed", "Paused", "Waiting for Wi‑Fi" or "Downloading". */
        fun statusText(state: DownloadAlbumState): String = when (state.status) {
            DownloadStatus.FAILED -> "Download failed"
            DownloadStatus.PAUSED -> "Paused"
            DownloadStatus.WAITING_FOR_WIFI -> "Waiting for Wi‑Fi"
            DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> "Downloading"
        }

        /** "3/12 tracks"; a playlist's are "3/12 songs". */
        fun tracksText(state: DownloadAlbumState): String =
            "${state.completedTracks}/${state.expectedTracks} ${if (state.playlistId != null) "songs" else "tracks"}"

        /** "Retry", "Resume" or "Pause". */
        fun actionLabel(state: DownloadAlbumState): String = when (state.status) {
            DownloadStatus.FAILED -> "Retry"
            DownloadStatus.PAUSED -> "Resume"
            else -> "Pause"
        }

        /** A disc heading's note: "Failed", "Paused" or "3/12". */
        fun discText(state: DownloadAlbumState): String = when (state.status) {
            DownloadStatus.FAILED -> "Failed"
            DownloadStatus.PAUSED -> "Paused"
            else -> "${state.completedTracks}/${state.expectedTracks}"
        }

        /** Pauses a download, resumes it if paused, or retries it if failed. */
        fun toggle(manager: DownloadManager, state: DownloadAlbumState) = when (state.status) {
            DownloadStatus.FAILED -> manager.retry(state.albumId)
            DownloadStatus.PAUSED -> manager.resume(state.albumId)
            else -> manager.pause(state.albumId)
        }
    }
}
