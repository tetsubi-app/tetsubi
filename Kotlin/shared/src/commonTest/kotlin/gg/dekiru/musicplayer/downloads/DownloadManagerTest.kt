package gg.dekiru.musicplayer.downloads

import gg.dekiru.musicplayer.data.api.FakeServer
import gg.dekiru.musicplayer.data.api.FakeServer.Companion.json
import gg.dekiru.musicplayer.data.api.path
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinPerson
import gg.dekiru.musicplayer.data.settings.InMemoryPreferences
import gg.dekiru.musicplayer.playback.AudioFormat
import gg.dekiru.musicplayer.playback.settle
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadManagerTest {

    private fun album(id: String, name: String = "Album $id") =
        JellyfinAlbum(id = id, name = name, albumArtists = listOf(JellyfinPerson(name = "Band")), productionYear = 1999)

    /** Track 2 of each album has its own picture. */
    private fun trackJson(album: String, i: Int, source: String = "flac") = """
        {"Id":"$album-t$i","Name":"Song $i?","IndexNumber":$i,"AlbumId":"$album","RunTimeTicks":${i * 600_000_000L},
         "AlbumArtists":[{"Name":"Band"}],"NormalizationGain":-3.5,
         "MediaSources":[{"Container":"${if (source == "alac") "m4a" else source}","Codecs":"$source","Bitrate":900000}]
         ${if (i == 2) ""","ImageTags":{"Primary":"own"},"AlbumPrimaryImageTag":"alb","ImageBlurHashes":{"Primary":{"own":"X","alb":"Y"}}""" else ""}}
    """

    private class Setup(
        val server: FakeServer,
        val dir: String,
        val prefs: InMemoryPreferences,
        val runner: InProcessDownloadRunner,
        val make: () -> DownloadManager,
    ) {
        var manager: DownloadManager = make()
        var metered = false
        val gates = mutableMapOf<String, CompletableDeferred<Unit>>()
        val failing = mutableSetOf<String>()
        var tracksFail = false
        var clock = 1_000L
        fun audio() = server.requests.filter { it.path.startsWith("/Audio/") }.map { it.path.split('/')[2] }
        fun gate(id: String) = CompletableDeferred<Unit>().also { gates[id] = it }
    }

    private fun TestScope.setup(
        tracks: Int = 3,
        dir: String = tempDir(),
        source: String = "flac",
        playable: () -> Set<AudioFormat> = { emptySet() },
        audio: (String) -> ByteArray = { "audio of $it".encodeToByteArray() },
    ): Setup {
        lateinit var s: Setup
        val server = FakeServer { request ->
            when {
                request.path == "/Items" -> {
                    if (s.tracksFail) return@FakeServer respond("down", HttpStatusCode.ServiceUnavailable)
                    val id = request.url.parameters["ParentId"]!!
                    json("""{"Items":[${(1..tracks).joinToString(",") { trackJson(id, it, source) }}]}""")
                }
                request.path.startsWith("/Audio/") -> {
                    val id = request.path.split('/')[2]
                    s.gates[id]?.await()
                    if (id in s.failing) respond("broken", HttpStatusCode.InternalServerError)
                    else {
                        val bytes = audio(id)
                        respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "${bytes.size}"))
                    }
                }
                request.path.endsWith("/Images/Primary") -> respond("jpg ${request.path}".encodeToByteArray(), HttpStatusCode.OK)
                else -> json("")
            }
        }
        val prefs = InMemoryPreferences()
        val runner = InProcessDownloadRunner(backgroundScope)
        val api = server.api(token = "tok", userId = "u1")
        s = Setup(server, dir, prefs, runner) {
            DownloadManager(api, dir, DownloadSettings(prefs), backgroundScope, { s.metered }, runner, { s.clock }, playable = playable())
        }
        return s
    }

    private fun Setup.manifest(id: String) = DownloadManifest.decode(LocalFiles.readText(manager.paths.manifestPath(id))!!)!!

    @Test
    fun downloadsAnAlbumWithItsCoversAndManifest() = runTest {
        val s = setup()
        s.manager.download(album("al1", "OK Computer"))
        assertEquals(DownloadStatus.QUEUED, s.manager.state("al1")?.status)
        assertTrue(s.runner.isRunning)
        settle()

        val dir = "${s.dir}/al1"
        assertEquals(
            setOf("01 - Song 1.flac", "02 - Song 2.flac", "03 - Song 3.flac", "album.json", "cover.jpg", "covers"),
            LocalFiles.list(dir).toSet(),
        )
        assertEquals("audio of al1-t2", LocalFiles.readText("$dir/02 - Song 2.flac"))
        assertEquals(listOf("02 - Song 2.jpg"), LocalFiles.list("$dir/covers"))
        assertTrue(LocalFiles.readText("$dir/cover.jpg")!!.contains("/Items/al1/Images/Primary"))

        val m = s.manifest("al1")
        assertEquals(ManifestStatus.COMPLETE, m.status)
        assertEquals(1_000L, m.downloadedAt)
        assertEquals("original", m.format)
        assertEquals(3, m.expectedTracks)
        assertEquals(1999, m.year)
        val t2 = m.tracks[1]
        assertEquals(ManifestTrack(2, "al1-t2", "Song 2?", "02 - Song 2.flac", 120.0, -3.5, "FLAC 900 kbps", true, "FLAC"), t2)

        assertNull(s.manager.state("al1"))
        assertTrue(s.manager.isDownloaded("al1"))
        val done = s.manager.downloaded.value.single()
        assertEquals("OK Computer", done.title)
        assertEquals(360.0, done.durationSeconds)
        assertEquals("$dir/02 - Song 2.flac", done.trackPath(t2))
        assertEquals("$dir/covers/02 - Song 2.jpg", done.artPath(t2))
        assertEquals("$dir/cover.jpg", done.artPath(m.tracks[0]))
        assertEquals(LocalFiles.totalSize(dir), done.sizeBytes)
        assertFalse(s.runner.isRunning)
        assertTrue(s.audio().all { "stream.flac" in it || it.startsWith("al1-t") })
    }

    @Test
    fun aChosenFormatIsConvertedAndLabelled() = runTest {
        val s = setup(tracks = 1)
        DownloadSettings(s.prefs).defaultFormat = AudioFormat.AAC
        s.manager.download(album("al1"))
        settle()
        val m = s.manifest("al1")
        assertEquals("aac", m.format)
        assertEquals("01 - Song 1.m4a", m.tracks.single().file)
        assertEquals("AAC", m.tracks.single().qualityLabel)
        assertTrue(s.server.requests.any { it.path == "/Audio/al1-t1/universal" })
    }

    @Test
    fun albumsRunOneAtATimeAndTracksTwoAtATime() = runTest {
        val s = setup(tracks = 4)
        val gates = (1..4).map { s.gate("al1-t$it") }
        s.manager.download(album("al1"))
        s.manager.download(album("al2"))
        settle()
        assertEquals(2, s.audio().size)
        assertEquals(DownloadStatus.DOWNLOADING, s.manager.state("al1")?.status)
        assertEquals(DownloadStatus.QUEUED, s.manager.state("al2")?.status)
        assertFalse(s.server.requests.any { it.url.parameters["ParentId"] == "al2" })
        gates.forEach { it.complete(Unit) }
        settle()
        assertTrue(s.manager.isDownloaded("al1"))
        assertTrue(s.manager.isDownloaded("al2"))
        assertEquals(8, s.audio().size)
    }

    @Test
    fun progressCountsTracks() = runTest {
        val s = setup(tracks = 4)
        val gate = s.gate("al1-t4")
        s.manager.download(album("al1"))
        settle()
        val state = s.manager.state("al1")!!
        assertEquals(4, state.expectedTracks)
        assertEquals(3, state.completedTracks)
        assertTrue(state.progress >= 0.75 && state.progress < 1.0, "progress ${state.progress}")
        gate.complete(Unit)
        settle()
    }

    @Test
    fun pauseKeepsFilesAndResumeFinishes() = runTest {
        val s = setup()
        val gate = s.gate("al1-t1")
        s.manager.download(album("al1"))
        settle()
        s.manager.pause("al1")
        settle()
        val paused = s.manager.state("al1")!!
        assertEquals(DownloadStatus.PAUSED, paused.status)
        assertEquals(2, paused.completedTracks)
        assertEquals(ManifestStatus.PAUSED, s.manifest("al1").status)
        assertFalse(s.manager.isDownloaded("al1"))
        assertTrue(LocalFiles.exists("${s.dir}/al1/02 - Song 2.flac"))
        assertFalse(LocalFiles.exists("${s.dir}/al1/01 - Song 1.flac"))

        gate.complete(Unit)
        s.manager.resume("al1")
        settle()
        assertTrue(s.manager.isDownloaded("al1"))
        // Only the unfinished track was fetched again.
        assertEquals(mapOf("al1-t1" to 2, "al1-t2" to 1, "al1-t3" to 1), s.audio().groupingBy { it }.eachCount())
    }

    @Test
    fun pausingAQueuedAlbumLetsTheNextRun() = runTest {
        val s = setup()
        val gate = s.gate("al1-t1")
        s.manager.download(album("al1"))
        s.manager.download(album("al2"))
        s.manager.pause("al2")
        settle()
        assertEquals(DownloadStatus.PAUSED, s.manager.state("al2")?.status)
        gate.complete(Unit)
        settle()
        assertTrue(s.manager.isDownloaded("al1"))
        assertEquals(DownloadStatus.PAUSED, s.manager.state("al2")?.status)
        assertFalse(s.runner.isRunning)
    }

    @Test
    fun cancelDeletesTheFolder() = runTest {
        val s = setup()
        s.gate("al1-t1")
        s.manager.download(album("al1"))
        settle()
        assertTrue(LocalFiles.exists("${s.dir}/al1/02 - Song 2.flac"))
        s.manager.cancel("al1")
        settle()
        assertNull(s.manager.state("al1"))
        assertFalse(LocalFiles.exists("${s.dir}/al1"))
        assertFalse(s.manager.isDownloaded("al1"))
        assertTrue(s.manager.downloaded.value.isEmpty())
    }

    @Test
    fun aFailedTrackFailsTheAlbumAndRetryKeepsFinishedFiles() = runTest {
        val s = setup()
        s.failing += "al1-t3"
        s.manager.download(album("al1"))
        settle()
        val failed = s.manager.state("al1")!!
        assertEquals(DownloadStatus.FAILED, failed.status)
        assertEquals("Track download failed: HTTP 500", failed.error)
        assertEquals(ManifestStatus.FAILED, s.manifest("al1").status)
        assertEquals("Track download failed: HTTP 500", s.manifest("al1").error)
        assertFalse(s.manager.isDownloaded("al1"))
        assertFalse(s.runner.isRunning)

        s.failing.clear()
        val before = s.audio().size
        s.manager.retry("al1")
        settle()
        assertTrue(s.manager.isDownloaded("al1"))
        assertEquals(listOf("al1-t3"), s.audio().drop(before))
    }

    @Test
    fun aTrackListErrorFailsTheAlbum() = runTest {
        val s = setup()
        s.tracksFail = true
        s.manager.download(album("al1"))
        s.manager.download(album("al2"))
        settle()
        assertEquals(DownloadStatus.FAILED, s.manager.state("al1")?.status)
        assertEquals(DownloadStatus.FAILED, s.manager.state("al2")?.status)
        assertTrue(s.manager.state("al1")!!.error!!.contains("503"))
        // Resume and retry only apply to their own states.
        s.manager.resume("al1")
        assertEquals(DownloadStatus.FAILED, s.manager.state("al1")?.status)
    }

    @Test
    fun waitsForWifiWhenMobileDataIsOff() = runTest {
        val s = setup()
        DownloadSettings(s.prefs).allowCellular = false
        s.metered = true
        s.manager.download(album("al1"))
        settle()
        assertEquals(DownloadStatus.WAITING_FOR_WIFI, s.manager.state("al1")?.status)
        assertTrue(s.audio().isEmpty())

        s.metered = false
        s.manager.networkChanged()
        settle()
        assertTrue(s.manager.isDownloaded("al1"))
    }

    @Test
    fun mobileDataOnDownloadsOnAMeteredNetwork() = runTest {
        val s = setup()
        s.metered = true
        s.manager.download(album("al1"))
        settle()
        assertTrue(s.manager.isDownloaded("al1"))
    }

    @Test
    fun turningMobileDataOnStartsWaitingAlbums() = runTest {
        val s = setup()
        DownloadSettings(s.prefs).allowCellular = false
        s.metered = true
        s.manager.download(album("al1"))
        settle()
        assertEquals(DownloadStatus.WAITING_FOR_WIFI, s.manager.state("al1")?.status)

        s.manager.setAllowCellular(true)
        settle()
        assertTrue(DownloadSettings(s.prefs).allowCellular)
        assertTrue(s.manager.isDownloaded("al1"))
    }

    @Test
    fun turningMobileDataOffStopsUntilWifiAndRestartsTheRunner() = runTest {
        val s = setup()
        val gate = s.gate("al1-t1")
        s.metered = true
        s.manager.download(album("al1"))
        settle()
        s.manager.setAllowCellular(false)
        settle()
        assertFalse(DownloadSettings(s.prefs).allowCellular)
        assertEquals(DownloadStatus.WAITING_FOR_WIFI, s.manager.state("al1")?.status)
        gate.complete(Unit)

        // The runner is asked again only when something is queued, and only on a change.
        val starts = mutableListOf<String>()
        val counting = object : DownloadRunner {
            override fun start(manager: DownloadManager) { starts += "start" }
            override fun stop() {}
            override fun restart(manager: DownloadManager) { starts += "restart" }
        }
        val idle = DownloadManager(s.server.api(token = "tok", userId = "u1"), tempDir(), DownloadSettings(s.prefs), backgroundScope, { true }, counting)
        idle.setAllowCellular(true)
        idle.setAllowCellular(true)
        assertTrue(starts.isEmpty())
        DownloadSettings(s.prefs).allowCellular = false
        idle.download(album("x"))
        idle.setAllowCellular(true)
        assertEquals(listOf("start", "restart"), starts)
    }

    @Test
    fun goingMeteredStopsTheAlbumUntilWifi() = runTest {
        val s = setup()
        DownloadSettings(s.prefs).allowCellular = false
        val gate = s.gate("al1-t1")
        s.manager.download(album("al1"))
        settle()
        s.metered = true
        s.manager.networkChanged()
        settle()
        assertEquals(DownloadStatus.WAITING_FOR_WIFI, s.manager.state("al1")?.status)
        gate.complete(Unit)
        s.metered = false
        s.manager.networkChanged()
        settle()
        assertTrue(s.manager.isDownloaded("al1"))
    }

    @Test
    fun downloadedAlbumsAreNewestFirstWithTotals() = runTest {
        val s = setup(tracks = 2)
        s.manager.download(album("b"))
        settle()
        s.clock = 5_000
        s.manager.download(album("a"))
        settle()
        s.clock = 3_000
        s.manager.download(album("c"))
        settle()
        assertEquals(listOf("a", "c", "b"), s.manager.downloaded.value.map { it.albumId })
        assertEquals(3 * 180.0, s.manager.totalDuration)
        assertEquals(listOf("a", "b", "c").sumOf { LocalFiles.totalSize("${s.dir}/$it") }, s.manager.totalSize)
        assertEquals(listOf("Song 1?", "Song 2?"), s.manager.localAlbums().first().trackTitles)

        s.manager.delete(listOf("a", "b"))
        assertEquals(listOf("c"), s.manager.downloaded.value.map { it.albumId })
        assertFalse(LocalFiles.exists("${s.dir}/a"))
        assertFalse(s.manager.isDownloaded("a"))
        // Downloading again works after a delete.
        s.manager.download(album("a"))
        settle()
        assertTrue(s.manager.isDownloaded("a"))
    }

    @Test
    fun ordersTiesById() {
        fun d(id: String, at: Long) = DownloadedAlbum(id, id, "", null, "original", at, 0, 0.0, emptyList(), "")
        assertEquals(
            listOf("x", "a", "b"),
            DownloadManager.newestFirst(listOf(d("b", 1), d("x", 2), d("a", 1))).map { it.albumId },
        )
    }

    @Test
    fun aMergedMultiDiscAlbumDownloadsEachDisc() = runTest {
        val s = setup(tracks = 1)
        val d1 = album("d1", "Set (Disc 1)")
        val d2 = album("d2", "Set (Disc 2)")
        val merged = d1.copy(name = "Set", discAlbums = listOf(d1, d2))
        s.manager.download(merged)
        assertNotNull(s.manager.state("d2"))
        assertFalse(s.manager.isDownloaded(merged))
        settle()
        assertTrue(s.manager.isDownloaded(merged))
        assertEquals("Set (Disc 2)", s.manifest("d2").title)
        s.manager.delete(listOf("d2"))
        assertFalse(s.manager.isDownloaded(merged))
        assertTrue(s.manager.isDownloaded("d1"))
    }

    @Test
    fun alacConvertedToFlacGetsItsTotalSamplesFilledIn() = runTest {
        // The server's FFmpeg writes the FLAC to a pipe: total samples 0, so Media3 couldn't seek in the file.
        val pad = setOf(AudioFormat.FLAC, AudioFormat.MP3, AudioFormat.AAC, AudioFormat.OPUS, AudioFormat.WAV)
        val converted = FlacFixtures.file(FlacFixtures.frames(false, listOf(4096, 4096, 777)))
        val s = setup(tracks = 2, source = "alac", playable = { pad }, audio = { converted })
        s.manager.download(album("al1"))
        settle()
        assertTrue(s.manager.isDownloaded("al1"))
        assertTrue(s.server.requests.filter { it.path.startsWith("/Audio/") }.all { it.path.endsWith("/universal") })
        for (file in s.manifest("al1").tracks.map { "${s.dir}/al1/${it.file}" }) {
            val bytes = LocalFiles.readBytes(file)!!
            assertEquals(converted.size, bytes.size)
            assertEquals(8_969L, FlacRepair.totalSamples(bytes.copyOfRange(8, 42)))
        }
    }

    @Test
    fun formatsThisDeviceCantPlayAreSavedAsFlac() = runTest {
        // The Pad: no ALAC decoder. "Original" ALAC files played silently.
        val pad = setOf(AudioFormat.FLAC, AudioFormat.MP3, AudioFormat.AAC, AudioFormat.OPUS, AudioFormat.WAV)
        val s = setup(source = "alac", playable = { pad })
        s.manager.download(album("al1"))
        settle()
        val m = s.manifest("al1")
        assertEquals(listOf("01 - Song 1.flac", "02 - Song 2.flac", "03 - Song 3.flac"), m.tracks.map { it.file })
        assertEquals("FLAC", m.tracks[0].format)
        // Converted by the server, not the original file.
        assertTrue(s.server.requests.filter { it.path.startsWith("/Audio/") }.all { it.path.endsWith("/universal") })
        // A device that plays ALAC keeps the original.
        assertEquals(AudioFormat.ALAC, s.manager.let { DownloadManager(s.server.api(token = "t", userId = "u1"), tempDir(), DownloadSettings(s.prefs), backgroundScope) }.storedFormat(AudioFormat.ALAC))
    }

    @Test
    fun oldDownloadsThisDeviceCantPlayAreDownloadedAgainAsFlac() = runTest {
        // Downloaded as ALAC before the rule, by a manager that allowed anything.
        var playable = emptySet<AudioFormat>()
        val s = setup(source = "alac", playable = { playable })
        s.manager.download(album("al1"))
        settle()
        assertEquals("01 - Song 1.m4a", s.manifest("al1").tracks[0].file)
        // An older manifest without the format: read from the quality label.
        val old = s.manifest("al1").let { m -> m.copy(tracks = m.tracks.map { it.copy(format = null) }) }
        LocalFiles.writeText(s.manager.paths.manifestPath("al1"), old.encode())
        s.manager.close()

        playable = setOf(AudioFormat.FLAC, AudioFormat.AAC)
        s.manager = s.make()
        settle()
        val m = s.manifest("al1")
        assertEquals(ManifestStatus.COMPLETE, m.status)
        assertEquals(listOf("01 - Song 1.flac", "02 - Song 2.flac", "03 - Song 3.flac"), m.tracks.map { it.file })
        assertFalse(LocalFiles.exists("${s.dir}/al1/01 - Song 1.m4a"))
        assertTrue(s.manager.isDownloaded("al1"))
    }

    @Test
    fun rebuildsFromManifestsOnStart() = runTest {
        val s = setup()
        s.manager.download(album("done"))
        settle()
        s.failing += "failed-t1"
        s.manager.download(album("failed"))
        settle()
        val gate = s.gate("paused-t1")
        s.manager.download(album("paused"))
        settle()
        s.manager.pause("paused")
        settle()
        // Left-overs: a folder without a manifest, a complete album missing a file.
        LocalFiles.createDirectories("${s.dir}/junk")
        LocalFiles.writeText("${s.dir}/junk/x.flac", "x")
        s.manager.download(album("broken"))
        settle()
        LocalFiles.deleteRecursively("${s.dir}/broken/02 - Song 2.flac")
        // A download cut off by the app closing.
        s.manager.download(album("running"))
        s.manager.close()
        settle()

        s.failing.clear()
        gate.complete(Unit)
        s.manager = s.make()
        val m = s.manager
        assertEquals(listOf("done"), m.downloaded.value.map { it.albumId })
        assertEquals(DownloadStatus.FAILED, m.state("failed")?.status)
        assertEquals("Track download failed: HTTP 500", m.state("failed")?.error)
        assertEquals(DownloadStatus.PAUSED, m.state("paused")?.status)
        assertEquals(2, m.state("paused")?.completedTracks)
        assertEquals("Album paused", m.state("paused")?.title)
        assertEquals(DownloadStatus.FAILED, m.state("broken")?.status)
        assertFalse(LocalFiles.exists("${s.dir}/junk"))
        // The cut-off one carries on by itself.
        settle()
        assertTrue(m.isDownloaded("running"))

        m.retry("broken")
        settle()
        assertTrue(m.isDownloaded("broken"))
    }
}
