package gg.dekiru.musicplayer.downloads

import gg.dekiru.musicplayer.data.api.FakeServer
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import gg.dekiru.musicplayer.downloads.FlacFixtures.file
import gg.dekiru.musicplayer.downloads.FlacFixtures.frame
import gg.dekiru.musicplayer.downloads.FlacFixtures.frames
import gg.dekiru.musicplayer.downloads.FlacFixtures.header
import gg.dekiru.musicplayer.downloads.FlacRepair.Result
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlacRepairTest {

    private fun write(bytes: ByteArray, name: String = "01 - A.flac"): String {
        val path = "${tempDir()}/$name"
        LocalFiles.writeBytes(path, bytes)
        return path
    }

    private fun totalIn(path: String): Long =
        FlacRepair.totalSamples(LocalFiles.readBytes(path)!!.copyOfRange(8, 42))

    /** Repairs and checks that only the total-samples bytes changed. */
    private fun assertRepairs(bytes: ByteArray, expected: Long) {
        val path = write(bytes)
        assertEquals(Result.REPAIRED, FlacRepair.repairFile(path, log = {}))
        val after = LocalFiles.readBytes(path)!!
        assertEquals(bytes.size, after.size)
        assertEquals(expected, totalIn(path))
        for (i in bytes.indices) {
            if (i !in 21..25) assertEquals(bytes[i], after[i], "byte $i")
        }
        // The top nibble of byte 21 is the last bit of the sample size: kept.
        assertEquals(bytes[21].toInt() and 0xF0, after[21].toInt() and 0xF0)
        assertFalse(LocalFiles.exists("$path.tmp"))
        // A second run finds it set.
        assertEquals(Result.ALREADY_SET, FlacRepair.repairFile(path, log = {}))
        assertContentEquals(after, LocalFiles.readBytes(path))
    }

    private fun assertUntouched(bytes: ByteArray, expected: Result) {
        val path = write(bytes)
        assertEquals(expected, FlacRepair.repairFile(path, log = {}))
        assertContentEquals(bytes, LocalFiles.readBytes(path))
        assertFalse(LocalFiles.exists("$path.tmp"))
    }

    @Test
    fun fixedBlocksizeWithAnOddLastBlock() {
        val sizes = List(5) { 4096 } + 1000 // the last one needs the 16-bit size
        assertRepairs(file(frames(variable = false, sizes)), 21_480)
    }

    @Test
    fun anOddLastBlockWithTheEightBitSize() {
        assertRepairs(file(frames(variable = false, listOf(4096, 4096, 100))), 8_292)
    }

    @Test
    fun manyFramesNeedMultiByteFrameNumbers() {
        assertRepairs(file(frames(variable = false, List(300) { 192 } + 77), blockSize = 192), 300L * 192 + 77)
    }

    @Test
    fun variableBlocksizeUsesSampleNumbers() {
        assertRepairs(file(frames(variable = true, listOf(1152, 4608, 576, 100, 4000, 256))), 10_692)
    }

    @Test
    fun otherMetadataBlocksAreSkipped() {
        val padding = 1 to ByteArray(500)
        val comment = 4 to ByteArray(40) { 0xFF.toByte() }
        assertRepairs(file(frames(variable = false, listOf(4096, 4096, 512)), extraBlocks = listOf(comment, padding)), 8_704)
    }

    @Test
    fun syncCodesInsideTheAudioAreNotFrames() {
        // Sample values FFF8 and FFF9 in every frame, and a whole valid-looking
        // header (with the next frame's number) inside a VERBATIM subframe.
        val fake = header(variable = false, number = 2, blockSize = 4096)
        val data = ByteArray(64 * 2) { 0xFF.toByte() }
        fake.copyInto(data, 10)
        val bytes = file(
            listOf(
                frame(false, 0, 4096, value = 0xFFF8),
                frame(false, 1, 64, verbatim = data),
                frame(false, 2, 4096, value = 0xFFF9),
                frame(false, 3, 4096, value = 0xFFF8),
            ),
        )
        assertRepairs(bytes, 4096L * 3 + 64)
    }

    @Test
    fun aFileWithItsTotalSetIsLeftExactlyAsItIs() {
        assertUntouched(file(frames(false, listOf(4096, 4096, 10)), total = 8_202), Result.ALREADY_SET)
        // Even a wrong total isn't ours to change.
        assertUntouched(file(frames(false, listOf(4096, 4096, 10)), total = 5), Result.ALREADY_SET)
    }

    @Test
    fun notFlacIsLeftAlone() {
        assertUntouched(ByteArray(5000) { (it * 7).toByte() }, Result.NOT_FLAC)
        assertUntouched("ID3".encodeToByteArray() + ByteArray(50), Result.NOT_FLAC)
        assertUntouched(ByteArray(3), Result.NOT_FLAC)
        assertUntouched(ByteArray(0), Result.NOT_FLAC)
    }

    @Test
    fun truncatedOrDamagedFlacIsLeftAlone() {
        val good = file(frames(false, listOf(4096, 4096, 4096, 500)))
        // Cut short in the last frame.
        assertUntouched(good.copyOfRange(0, good.size - 3), Result.UNPARSEABLE)
        // A damaged byte in the middle of the audio.
        val damaged = good.copyOf().also { it[it.size - 20] = (it[it.size - 20] + 1).toByte() }
        assertUntouched(damaged, Result.UNPARSEABLE)
        // Garbage where the first frame should be.
        assertUntouched(file(emptyList()) + ByteArray(100) { 0x55 }, Result.UNPARSEABLE)
        // No frames at all.
        assertUntouched(file(emptyList()), Result.UNPARSEABLE)
        // STREAMINFO itself cut short.
        assertUntouched(good.copyOfRange(0, 30), Result.UNPARSEABLE)
        // A frame number out of step (a frame missing).
        val skipped = file(listOf(frame(false, 0, 4096), frame(false, 2, 4096)))
        assertUntouched(skipped, Result.UNPARSEABLE)
    }

    @Test
    fun anId3TagBeforeTheFlacIsKept() {
        val flac = file(frames(false, listOf(4096, 300)))
        val tag = byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0, 0, 0, 0, 20) + ByteArray(20) { 7 }
        val path = write(tag + flac)
        assertEquals(Result.REPAIRED, FlacRepair.repairFile(path, log = {}))
        val after = LocalFiles.readBytes(path)!!
        assertEquals(tag.size + flac.size, after.size)
        assertContentEquals(tag, after.copyOfRange(0, tag.size))
        assertEquals(4396L, FlacRepair.totalSamples(after.copyOfRange(tag.size + 8, tag.size + 42)))
    }

    @Test
    fun theThirtySixBitFieldIsWrittenWhole() {
        val head = file(emptyList())
        head[21] = 0xA0.toByte() // sample-size bit and a stale nibble
        FlacRepair.patch(head, 0x9_8765_4321L)
        assertEquals(0xA9.toByte(), head[21])
        assertContentEquals(byteArrayOf(0x87.toByte(), 0x65, 0x43, 0x21), head.copyOfRange(22, 26))
        assertEquals(0x9_8765_4321L, FlacRepair.totalSamples(head.copyOfRange(8, 42)))
    }

    @Test
    fun theDownloaderRepairsAFlacBeforeItsRenamed() = runTest {
        val bytes = file(frames(false, listOf(4096, 4096, 123)))
        val server = FakeServer { respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "${bytes.size}")) }
        val dir = tempDir()
        TrackDownloader(server.client, { "t" }).download("http://x/Audio/t/universal?Container=flac", "$dir/01 - A.flac")
        assertEquals(8_315L, totalIn("$dir/01 - A.flac"))
        assertFalse(LocalFiles.exists("$dir/01 - A.flac.part"))
        assertFalse(LocalFiles.exists("$dir/01 - A.flac.part.tmp"))
        // Other formats are saved as they come.
        TrackDownloader(server.client, { "t" }).download("http://x/a", "$dir/02 - B.m4a")
        assertContentEquals(bytes, LocalFiles.readBytes("$dir/02 - B.m4a"))
    }

    @Test
    fun theScanRepairsAlbumAndPlaylistFiles() {
        val root = tempDir()
        val broken = file(frames(false, listOf(4096, 50)))
        LocalFiles.createDirectories("$root/album/covers")
        LocalFiles.createDirectories("$root/Playlists/p1")
        LocalFiles.writeBytes("$root/album/01 - A.flac", broken)
        LocalFiles.writeBytes("$root/album/02 - B.flac", file(frames(false, listOf(4096, 50)), total = 4146))
        LocalFiles.writeBytes("$root/album/03 - C.mp3", broken)
        LocalFiles.writeBytes("$root/album/04 - D.flac.part", broken)
        LocalFiles.writeBytes("$root/Playlists/p1/t1.flac", broken)
        assertEquals(2, DownloadManager.repairFlacFiles(root))
        assertEquals(4146L, totalIn("$root/album/01 - A.flac"))
        assertEquals(4146L, totalIn("$root/Playlists/p1/t1.flac"))
        assertContentEquals(broken, LocalFiles.readBytes("$root/album/03 - C.mp3"))
        assertContentEquals(broken, LocalFiles.readBytes("$root/album/04 - D.flac.part"))
        assertEquals(0, DownloadManager.repairFlacFiles(root))
        assertEquals(0, DownloadManager.repairFlacFiles("$root/missing"))
    }

    @Test
    fun theManagerScansOnceInTheBackground() = runTest {
        val root = tempDir()
        val id = "album1"
        LocalFiles.createDirectories("$root/$id")
        LocalFiles.writeText(
            "$root/$id/${DownloadPaths.MANIFEST}",
            DownloadManifest(
                albumId = id, title = "A", artist = "B", downloadedAt = 1, status = ManifestStatus.COMPLETE,
                tracks = listOf(ManifestTrack(1, "t1", "A", "01 - A.flac")),
            ).encode(),
        )
        val broken = file(frames(false, listOf(4096, 50)))
        LocalFiles.writeBytes("$root/$id/01 - A.flac", broken)
        val prefs = InMemoryPreferences()
        val io = StandardTestDispatcher(testScheduler)
        DownloadManager(FakeServer { respond("") }.api(), root, DownloadSettings(prefs), backgroundScope, ioDispatcher = io)
        assertFalse(DownloadSettings(prefs).flacTotalsRepaired)
        repeat(5) { runCurrent() } // background work: advanceUntilIdle leaves it
        assertEquals(4146L, totalIn("$root/$id/01 - A.flac"))
        assertTrue(DownloadSettings(prefs).flacTotalsRepaired)

        // Once done, never again.
        LocalFiles.writeBytes("$root/$id/02 - B.flac", broken)
        DownloadManager(FakeServer { respond("") }.api(), root, DownloadSettings(prefs), backgroundScope, ioDispatcher = io)
        repeat(5) { runCurrent() } // background work: advanceUntilIdle leaves it
        assertContentEquals(broken, LocalFiles.readBytes("$root/$id/02 - B.flac"))
    }
}
