package gg.dekiru.musicplayer.ui

import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.downloads.DownloadAlbumState
import gg.dekiru.musicplayer.downloads.DownloadStatus
import gg.dekiru.musicplayer.ui.album.AlbumDownloads
import gg.dekiru.musicplayer.ui.album.DownloadButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The album page's download button and progress, from the manager's lists. */
class AlbumDownloadsTest {

    private val d1 = JellyfinAlbum(id = "d1", name = "Set (Disc 1)")
    private val d2 = JellyfinAlbum(id = "d2", name = "Set (Disc 2)")
    private fun state(id: String, status: DownloadStatus = DownloadStatus.DOWNLOADING, progress: Double = 0.5) =
        DownloadAlbumState(id, id, "Band", null, expectedTracks = 10, completedTracks = 4, progress = progress, status = status)

    @Test
    fun aSingleAlbum() {
        val none = AlbumDownloads(listOf(d1), emptyMap(), emptySet())
        assertEquals(DownloadButton.DOWNLOAD, none.button)
        assertEquals("Download", none.buttonLabel)
        assertEquals(listOf(d1), none.toStart)
        assertNull(none.singleState)

        val going = AlbumDownloads(listOf(d1), mapOf("d1" to state("d1")), emptySet())
        assertEquals(DownloadButton.DOWNLOADING, going.button)
        assertEquals("Downloading", going.buttonLabel)
        assertEquals("d1", going.singleState?.albumId)
        assertTrue(going.toStart.isEmpty())

        // Paused and failed still count as downloading; the row below offers Resume or Retry.
        val paused = AlbumDownloads(listOf(d1), mapOf("d1" to state("d1", DownloadStatus.PAUSED)), emptySet())
        assertEquals(DownloadButton.DOWNLOADING, paused.button)

        val done = AlbumDownloads(listOf(d1), emptyMap(), setOf("d1"))
        assertEquals(DownloadButton.DOWNLOADED, done.button)
        assertEquals("Downloaded", done.buttonLabel)
        assertTrue(done.isDownloaded)
    }

    @Test
    fun aSetOfDiscs() {
        val none = AlbumDownloads(listOf(d1, d2), emptyMap(), emptySet())
        assertEquals("Download all discs", none.buttonLabel)
        assertEquals(listOf(d1, d2), none.toStart)

        // One disc downloaded, the other not started: Download all discs starts just that one.
        val half = AlbumDownloads(listOf(d1, d2), emptyMap(), setOf("d1"))
        assertEquals(DownloadButton.DOWNLOAD, half.button)
        assertEquals(listOf(d2), half.toStart)
        assertTrue(half.isDiscDownloaded(d1))
        assertFalse(half.isDiscDownloaded(d2))

        // One downloaded, the other under way: Downloading, progress shown per disc.
        val going = AlbumDownloads(listOf(d1, d2), mapOf("d2" to state("d2", progress = 0.5)), setOf("d1"))
        assertEquals(DownloadButton.DOWNLOADING, going.button)
        assertNull(going.singleState)
        assertEquals(0.75, going.overallProgress)

        val done = AlbumDownloads(listOf(d1, d2), emptyMap(), setOf("d1", "d2"))
        assertEquals(DownloadButton.DOWNLOADED, done.button)

        // Being downloaded again (in the list and active): not downloaded.
        val again = AlbumDownloads(listOf(d1), mapOf("d1" to state("d1")), setOf("d1"))
        assertFalse(again.isDiscDownloaded(d1))
        assertEquals(DownloadButton.DOWNLOADING, again.button)
    }

    @Test
    fun texts() {
        val s = state("d1")
        assertEquals("Downloading", AlbumDownloads.statusText(s))
        assertEquals("Downloading", AlbumDownloads.statusText(s.copy(status = DownloadStatus.QUEUED)))
        assertEquals("Paused", AlbumDownloads.statusText(s.copy(status = DownloadStatus.PAUSED)))
        assertEquals("Download failed", AlbumDownloads.statusText(s.copy(status = DownloadStatus.FAILED)))
        assertEquals("Waiting for Wi‑Fi", AlbumDownloads.statusText(s.copy(status = DownloadStatus.WAITING_FOR_WIFI)))
        assertEquals("4/10 tracks", AlbumDownloads.tracksText(s))
        assertEquals("Pause", AlbumDownloads.actionLabel(s))
        assertEquals("Resume", AlbumDownloads.actionLabel(s.copy(status = DownloadStatus.PAUSED)))
        assertEquals("Retry", AlbumDownloads.actionLabel(s.copy(status = DownloadStatus.FAILED)))
        assertEquals("4/10", AlbumDownloads.discText(s))
        assertEquals("Paused", AlbumDownloads.discText(s.copy(status = DownloadStatus.PAUSED)))
        assertEquals("Failed", AlbumDownloads.discText(s.copy(status = DownloadStatus.FAILED)))
    }
}
