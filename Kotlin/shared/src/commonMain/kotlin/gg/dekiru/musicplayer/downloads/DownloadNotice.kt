package gg.dekiru.musicplayer.downloads

/** What the download notification shows (Android), worked out from [DownloadManager.active]. */
data class DownloadNotice(
    /** The album the Pause and Cancel actions act on. */
    val albumId: String,
    val title: String,
    val text: String?,
    /** 0..100, or null for no progress bar. */
    val percent: Int?,
    val waitingForWifi: Boolean,
) {
    companion object {
        /** The album downloading (or next in line), or null when nothing is queued. */
        fun from(active: Map<String, DownloadAlbumState>): DownloadNotice? {
            val queued = active.values.filter { it.status.isQueued }
            val album = queued.firstOrNull { it.status == DownloadStatus.DOWNLOADING }
                ?: queued.firstOrNull { it.status == DownloadStatus.QUEUED }
                ?: queued.firstOrNull()
                ?: return null
            if (album.status == DownloadStatus.WAITING_FOR_WIFI) {
                return DownloadNotice(album.albumId, "Waiting for Wi‑Fi", album.title, null, waitingForWifi = true)
            }
            val songs = if (album.expectedTracks > 0) "${album.completedTracks} of ${album.expectedTracks} songs" else null
            val percent = if (album.expectedTracks > 0) (album.progress * 100).toInt().coerceIn(0, 100) else null
            return DownloadNotice(album.albumId, "Downloading ${album.title}", songs, percent, waitingForWifi = false)
        }

        /** Albums the manager still has to run (it keeps the work alive for these). */
        fun hasQueued(active: Map<String, DownloadAlbumState>): Boolean = active.values.any { it.status.isQueued }

        /** Albums that failed between two snapshots of [DownloadManager.active], for a "failed" notification. */
        fun newFailures(before: Map<String, DownloadAlbumState>, after: Map<String, DownloadAlbumState>): List<DownloadAlbumState> =
            after.values.filter { it.status == DownloadStatus.FAILED && before[it.albumId]?.status != DownloadStatus.FAILED }

        /** Work waits for Wi‑Fi (an unmetered network) when mobile data is off. */
        fun needsUnmetered(allowCellular: Boolean): Boolean = !allowCellular
    }
}

private val DownloadStatus.isQueued: Boolean
    get() = this == DownloadStatus.QUEUED || this == DownloadStatus.DOWNLOADING || this == DownloadStatus.WAITING_FOR_WIFI
