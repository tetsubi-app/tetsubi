package gg.dekiru.musicplayer.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadNoticeTest {

    private fun album(id: String, status: DownloadStatus, done: Int = 0, expected: Int = 0, progress: Double = 0.0, error: String? = null) =
        DownloadAlbumState(id, "Album $id", "Artist", null, expected, done, progress, status, error)

    private fun map(vararg albums: DownloadAlbumState) = albums.associateBy { it.albumId }

    @Test
    fun nothingQueuedShowsNothing() {
        assertNull(DownloadNotice.from(emptyMap()))
        assertNull(DownloadNotice.from(map(album("a", DownloadStatus.PAUSED), album("b", DownloadStatus.FAILED))))
        assertFalse(DownloadNotice.hasQueued(map(album("a", DownloadStatus.PAUSED))))
    }

    @Test
    fun downloadingAlbumWithSongsAndProgress() {
        val notice = DownloadNotice.from(
            map(album("q", DownloadStatus.QUEUED), album("d", DownloadStatus.DOWNLOADING, done = 3, expected = 12, progress = 0.27)),
        )!!
        assertEquals("d", notice.albumId)
        assertEquals("Downloading Album d", notice.title)
        assertEquals("3 of 12 songs", notice.text)
        assertEquals(27, notice.percent)
        assertFalse(notice.waitingForWifi)
    }

    @Test
    fun beforeTheTrackListThereIsNoCount() {
        val notice = DownloadNotice.from(map(album("q", DownloadStatus.QUEUED)))!!
        assertEquals("Downloading Album q", notice.title)
        assertNull(notice.text)
        assertNull(notice.percent)
    }

    @Test
    fun waitingForWifi() {
        val active = map(album("w", DownloadStatus.WAITING_FOR_WIFI, done = 1, expected = 5))
        val notice = DownloadNotice.from(active)!!
        assertEquals("Waiting for Wi‑Fi", notice.title)
        assertEquals("Album w", notice.text)
        assertNull(notice.percent)
        assertTrue(notice.waitingForWifi)
        assertTrue(DownloadNotice.hasQueued(active))
    }

    @Test
    fun newFailuresOnly() {
        val before = map(album("a", DownloadStatus.FAILED, error = "old"), album("b", DownloadStatus.DOWNLOADING))
        val after = map(
            album("a", DownloadStatus.FAILED, error = "old"),
            album("b", DownloadStatus.FAILED, error = "Track download failed: timeout"),
        )
        assertEquals(listOf("b"), DownloadNotice.newFailures(before, after).map { it.albumId })
        assertEquals(emptyList(), DownloadNotice.newFailures(after, after))
    }

    @Test
    fun unmeteredOnlyWithoutMobileData() {
        assertTrue(DownloadNotice.needsUnmetered(allowCellular = false))
        assertFalse(DownloadNotice.needsUnmetered(allowCellular = true))
    }
}
