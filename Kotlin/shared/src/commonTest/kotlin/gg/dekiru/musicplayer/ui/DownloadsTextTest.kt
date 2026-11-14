package gg.dekiru.musicplayer.ui

import gg.dekiru.musicplayer.downloads.DownloadAlbumState
import gg.dekiru.musicplayer.downloads.DownloadStatus
import gg.dekiru.musicplayer.downloads.DownloadedAlbum
import gg.dekiru.musicplayer.playback.AudioFormat
import gg.dekiru.musicplayer.ui.downloads.DownloadsText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Downloads tab's texts, as iOS writes them. */
class DownloadsTextTest {

    @Test
    fun bytesAsByteCountFormatter() {
        assertEquals("Zero KB", DownloadsText.formatBytes(0))
        assertEquals("1 byte", DownloadsText.formatBytes(1))
        assertEquals("999 bytes", DownloadsText.formatBytes(999))
        assertEquals("1 KB", DownloadsText.formatBytes(1_000))
        assertEquals("12 KB", DownloadsText.formatBytes(12_345))
        assertEquals("1 MB", DownloadsText.formatBytes(999_999))
        assertEquals("512.3 MB", DownloadsText.formatBytes(512_345_678))
        assertEquals("2 MB", DownloadsText.formatBytes(2_000_000))
        assertEquals("1.25 GB", DownloadsText.formatBytes(1_250_000_000))
        assertEquals("1.5 GB", DownloadsText.formatBytes(1_500_000_000))
        assertEquals("2 GB", DownloadsText.formatBytes(1_999_999_999))
        assertEquals("3.1 TB", DownloadsText.formatBytes(3_100_000_000_000))
    }

    @Test
    fun detailLineIsTracksTimeAndSize() {
        assertEquals("12 tracks · 47m 12s · 512.3 MB", DownloadsText.detailLine(12, 47 * 60 + 12.0, 512_345_678))
        assertEquals("1 track · 4m 05s · 9 MB", DownloadsText.detailLine(1, 245.0, 9_000_000))
        assertEquals("1 track · 1h 02m 03s · 1.2 GB", DownloadsText.detailLine(1, 3723.0, 1_200_000_000))
        // No playing time known: left out.
        assertEquals("3 tracks · 12 KB", DownloadsText.detailLine(3, 0.0, 12_000))
    }

    @Test
    fun storageTotals() {
        fun d(id: String, size: Long, seconds: Double) = DownloadedAlbum(id, id, "", null, "original", 0, size, seconds, emptyList(), "")
        val albums = listOf(d("a", 400_000_000, 3000.0), d("b", 850_000_000, 1000.5))
        assertEquals("1.25 GB", DownloadsText.storageUsed(albums))
        assertEquals("1h 06m 41s", DownloadsText.totalPlayingTime(albums))
        assertEquals("Zero KB", DownloadsText.storageUsed(emptyList()))
        assertNull(DownloadsText.totalPlayingTime(emptyList()))
    }

    @Test
    fun downloadingLines() {
        val s = DownloadAlbumState("a", "Album", "Band", AudioFormat.FLAC, expectedTracks = 12, completedTracks = 3, progress = 0.426)
        assertEquals("FLAC · 3/12 tracks", DownloadsText.formatLine(s))
        assertEquals("Original · 3/12 tracks", DownloadsText.formatLine(s.copy(format = null)))
        assertEquals("42%", DownloadsText.progressLine(s.copy(status = DownloadStatus.DOWNLOADING)))
        assertEquals("42%", DownloadsText.progressLine(s))
        assertEquals("Paused · 42%", DownloadsText.progressLine(s.copy(status = DownloadStatus.PAUSED)))
        assertEquals("Failed · 42%", DownloadsText.progressLine(s.copy(status = DownloadStatus.FAILED)))
        assertEquals("Waiting for Wi‑Fi · 42%", DownloadsText.progressLine(s.copy(status = DownloadStatus.WAITING_FOR_WIFI)))
        assertEquals("100%", DownloadsText.progressLine(s.copy(progress = 1.0)))
    }

    @Test
    fun removeButton() {
        assertEquals("Remove selected", DownloadsText.removeLabel(0))
        assertEquals("Remove selected (2)", DownloadsText.removeLabel(2))
    }
}
