package gg.dekiru.musicplayer.ui.downloads

import gg.dekiru.musicplayer.downloads.DownloadAlbumState
import gg.dekiru.musicplayer.downloads.DownloadStatus
import gg.dekiru.musicplayer.downloads.DownloadedAlbum
import gg.dekiru.musicplayer.downloads.DownloadedPlaylist
import gg.dekiru.musicplayer.ui.playlist.PlaylistsModel
import gg.dekiru.musicplayer.playback.formatAlbumDuration
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/** The Downloads tab's texts, worded and formatted as iOS `DownloadsView`. */
object DownloadsText {

    const val EMPTY_TITLE = "No downloads yet"
    const val EMPTY_MESSAGE = "Download an album from its detail page to play it offline."

    /** "12 tracks · 47m 12s · 512 MB" (no playing time when it's 0). */
    fun detailLine(count: Int, durationSeconds: Double, sizeBytes: Long): String {
        val parts = mutableListOf("$count ${if (count == 1) "track" else "tracks"}")
        if (durationSeconds > 0) parts += formatAlbumDuration(durationSeconds)
        parts += formatBytes(sizeBytes)
        return parts.joinToString(" · ")
    }

    fun detailLine(album: DownloadedAlbum): String = detailLine(album.tracks.size, album.durationSeconds, album.sizeBytes)

    /** "FLAC · 3/12 tracks"; "Original" when the server's file is kept. A playlist's are "songs". */
    fun formatLine(state: DownloadAlbumState): String =
        "${state.format?.displayName ?: "Original"} · ${state.completedTracks}/${state.expectedTracks} " +
            if (state.playlistId != null) "songs" else "tracks"

    /** A downloaded playlist's line: "12 songs · 47 min · 512 MB" (the size of its own files). */
    fun detailLine(playlist: DownloadedPlaylist): String =
        "${PlaylistsModel.summary(playlist.songCount, playlist.durationSeconds.toLong())} · ${formatBytes(playlist.sizeBytes)}"

    /** Under "Downloading", in the artist's place. */
    const val PLAYLIST = "Playlist"

    const val REMOVE_PLAYLIST_MESSAGE = "It'll be deleted from this device. You can download it again from the playlist page."

    /** The remove confirmation's text, by what's selected. */
    fun removeMessage(albums: Int, playlists: Int): String = when {
        playlists == 0 -> "They'll be deleted from this device. You can download them again from the album page."
        albums == 0 && playlists == 1 -> REMOVE_PLAYLIST_MESSAGE
        albums == 0 -> "They'll be deleted from this device. You can download them again from the playlist page."
        else -> "They'll be deleted from this device. You can download them again from their pages."
    }

    /** "42%", "Paused · 42%", "Failed · 42%", "Waiting for Wi‑Fi · 42%". */
    fun progressLine(state: DownloadAlbumState): String {
        val percent = "${(state.progress.coerceIn(0.0, 1.0) * 100).toInt()}%"
        return when (state.status) {
            DownloadStatus.FAILED -> "Failed · $percent"
            DownloadStatus.PAUSED -> "Paused · $percent"
            DownloadStatus.WAITING_FOR_WIFI -> "Waiting for Wi‑Fi · $percent"
            DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> percent
        }
    }

    /** "Storage used": the complete downloads' size (a playlist's own files; songs it plays from an album count with the album). */
    fun storageUsed(albums: List<DownloadedAlbum>, playlists: List<DownloadedPlaylist> = emptyList()): String =
        formatBytes(albums.sumOf { it.sizeBytes } + playlists.sumOf { it.sizeBytes })

    /**
     * "Total playing time" of the complete downloads; null (the row hidden)
     * when there are none. A playlist adds the songs kept in its own folder, each once.
     */
    fun totalPlayingTime(albums: List<DownloadedAlbum>, playlists: List<DownloadedPlaylist> = emptyList()): String? {
        if (albums.isEmpty() && playlists.isEmpty()) return null
        val own = playlists.sumOf { p -> p.manifest.uniqueTracks.filter { it.file != null }.sumOf { it.durationSeconds } }
        return formatAlbumDuration(albums.sumOf { it.durationSeconds } + own)
    }

    /** The edit-mode button: "Remove selected", "Remove selected (2)". */
    fun removeLabel(count: Int): String = if (count == 0) "Remove selected" else "Remove selected ($count)"

    /**
     * Bytes as Apple's `ByteCountFormatter` (file style) writes them, so the
     * numbers match the iPhone: decimal units, whole KB, one decimal for MB,
     * two from GB up, trailing zeros dropped ("Zero KB", "512 bytes",
     * "12 KB", "512.3 MB", "1.25 GB", "2 GB").
     */
    fun formatBytes(bytes: Long): String {
        if (bytes == 0L) return "Zero KB"
        if (bytes == 1L) return "1 byte"
        if (abs(bytes) < 1000) return "$bytes bytes"
        val units = listOf("KB" to 0, "MB" to 1, "GB" to 2, "TB" to 2, "PB" to 2)
        var value = bytes / 1000.0
        var i = 0
        while (true) {
            val (unit, digits) = units[i]
            val scale = 10.0.pow(digits)
            val scaled = (value * scale).roundToLong()
            // 999.96 MB rounds to 1000 MB: say 1 GB instead.
            if (abs(scaled) >= 1000 * scale && i < units.lastIndex) {
                value /= 1000
                i++
                continue
            }
            return "${decimal(scaled, digits)} $unit"
        }
    }

    /** 51234 with 2 digits → "512.34"; trailing zeros dropped. */
    private fun decimal(scaled: Long, digits: Int): String {
        if (digits == 0) return scaled.toString()
        val factor = 10.0.pow(digits).toLong()
        val whole = scaled / factor
        val fraction = (abs(scaled) % factor).toString().padStart(digits, '0').trimEnd('0')
        return if (fraction.isEmpty()) whole.toString() else "$whole.$fraction"
    }
}
