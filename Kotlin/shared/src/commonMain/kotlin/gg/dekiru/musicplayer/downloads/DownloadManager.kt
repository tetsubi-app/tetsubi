package gg.dekiru.musicplayer.downloads

import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinTrack
import gg.dekiru.musicplayer.data.models.allDiscs
import gg.dekiru.musicplayer.logLine
import gg.dekiru.musicplayer.nowMillis
import gg.dekiru.musicplayer.playback.AudioFormat
import gg.dekiru.musicplayer.playback.PlaybackTrack
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor

enum class DownloadStatus { QUEUED, DOWNLOADING, PAUSED, FAILED, WAITING_FOR_WIFI }

/** An album being downloaded, paused or failed (as `DownloadAlbumState` on iOS). */
data class DownloadAlbumState(
    val albumId: String,
    val title: String,
    val artist: String,
    /** The chosen format, null for the original. */
    val format: AudioFormat?,
    /** 0 until the track list is in. */
    val expectedTracks: Int = 0,
    val completedTracks: Int = 0,
    /** 0…1, by tracks and the bytes of the tracks under way. */
    val progress: Double = 0.0,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val error: String? = null,
    /** Set when this is a playlist, not an album ([albumId] is then its queue key, see [PlaylistDownloads.key]). */
    val playlistId: String? = null,
)

/** A complete download. */
data class DownloadedAlbum(
    val albumId: String,
    val title: String,
    val artist: String,
    val year: Int?,
    /** "flac", … or "original". */
    val format: String,
    /** Epoch ms. */
    val downloadedAt: Long,
    val sizeBytes: Long,
    val durationSeconds: Double,
    /** In play order. */
    val tracks: List<ManifestTrack>,
    /** The album folder. */
    val directory: String,
) {
    fun trackPath(track: ManifestTrack): String = "$directory/${track.file}"

    /** `cover.jpg`, may not exist (saving it can fail). */
    val coverPath: String get() = "$directory/${DownloadPaths.COVER}"

    /** A track's own picture when it has one (`covers/…`), else the album cover. */
    fun artPath(track: ManifestTrack): String =
        if (track.hasOwnArt) "$directory/${DownloadPaths.COVERS}/${DownloadPaths.stem(track.file)}.jpg" else coverPath

    fun toLocalAlbum() = LocalAlbum(albumId, title, artist, tracks.map { it.title })
}

/**
 * Downloads albums for offline play (as iOS `DownloadManager`), one per
 * signed-in server. Albums run one at a time, tracks two at a time, through
 * a [DownloadRunner]. State lives in each album's `album.json`, so paused
 * and failed albums survive a relaunch and unfinished ones carry on.
 *
 * Call it from `scope`'s thread (the main thread in the app): its own
 * bookkeeping stays there, files and network go to [ioDispatcher].
 */
class DownloadManager(
    internal val api: JellyfinApi,
    downloadsDir: String,
    val settings: DownloadSettings,
    internal val scope: CoroutineScope,
    private val isExpensiveNetwork: () -> Boolean = { false },
    runner: DownloadRunner? = null,
    internal val now: () -> Long = ::nowMillis,
    internal val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * Formats this device's player can play (empty: no player, anything goes).
     * A track whose chosen format isn't one is downloaded as FLAC instead:
     * the Pad has no ALAC decoder, and ALAC files played silently.
     */
    private val playable: Set<AudioFormat> = emptySet(),
    /** False with no connection at all: a queued playlist then waits instead of failing. */
    private val isOnline: () -> Boolean = { true },
) {
    val paths = DownloadPaths(downloadsDir)

    /** The format to save a track in: the chosen one if this device plays it, else FLAC (lossless too). */
    internal fun storedFormat(chosen: AudioFormat): AudioFormat =
        if (playable.isEmpty() || chosen in playable || AudioFormat.FLAC !in playable) chosen else AudioFormat.FLAC

    /** Whether a downloaded file can be played here (unknown formats count as playable). */
    private fun canPlay(track: ManifestTrack): Boolean = track.fileFormat?.let { storedFormat(it) == it } ?: true
    private val runner: DownloadRunner = runner ?: InProcessDownloadRunner(scope)
    internal val downloader = TrackDownloader(api.http, { api.authorizationHeader() }, ioDispatcher)
    private val dispatcher = scope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher ?: Dispatchers.Default

    private val _active = MutableStateFlow<Map<String, DownloadAlbumState>>(emptyMap())

    /** Albums downloading, queued, waiting, paused or failed, by album ID. */
    val active: StateFlow<Map<String, DownloadAlbumState>> = _active.asStateFlow()

    private val _downloaded = MutableStateFlow<List<DownloadedAlbum>>(emptyList())

    /** Complete albums, newest first. */
    val downloaded: StateFlow<List<DownloadedAlbum>> = _downloaded.asStateFlow()

    /** Album IDs waiting to run, in order; the first is the one running. */
    private val queue = mutableListOf<String>()
    private var currentId: String? = null
    private var currentJob: Job? = null
    private val runLock = Mutex()

    /** Bumped when the queue or the network changes, to wake a waiting queue. */
    private val wakeups = MutableStateFlow(0L)
    private var closed = false

    /** Downloaded playlists; they share this queue. */
    val playlists = PlaylistDownloads(this)

    init {
        restore()
        if (queue.isNotEmpty()) this.runner.start(this)
        repairOldFlacFiles()
    }

    /**
     * Once per install: fills in the total samples of FLAC files downloaded
     * before [TrackDownloader] did it, so Media3 can seek in them. Runs in the
     * background; each file is replaced whole (see [FlacRepair]), and the flag
     * is set only when every file has been looked at, so a killed app starts
     * the scan again next time.
     */
    private fun repairOldFlacFiles() {
        if (settings.flacTotalsRepaired) return
        // All of it on the I/O dispatcher, the flag too (both real Preferences
        // are thread-safe), so nothing waits for the main thread.
        scope.launch(ioDispatcher) {
            val repaired = repairFlacFiles(paths.root)
            if (repaired > 0) logLine("Tetsubi", "flac: repaired $repaired downloaded files")
            runCatching { settings.flacTotalsRepaired = true }
        }
    }

    // Public API

    /**
     * Downloads an album (each disc on its own for a merged multi-disc
     * album, as iOS). Nothing happens if it's already downloading or downloaded.
     */
    fun download(album: JellyfinAlbum, format: AudioFormat? = settings.defaultFormat) {
        if (album.discAlbums.isNotEmpty()) {
            album.discAlbums.forEach { download(it, format) }
            return
        }
        val id = album.id
        if (id in _active.value || isDownloaded(id)) return
        LocalFiles.createDirectories(paths.albumDir(id))
        writeManifest(
            DownloadManifest(
                albumId = id, title = album.displayName, artist = album.artistName, year = album.productionYear,
                format = DownloadManifest.formatName(format),
            ),
        )
        setState(DownloadAlbumState(id, album.displayName, album.artistName, format))
        enqueue(id)
    }

    /** Stops an album; what's on disk (including half a track) is kept for [resume]. */
    fun pause(albumId: String) {
        val state = _active.value[albumId] ?: return
        if (state.status == DownloadStatus.PAUSED || state.status == DownloadStatus.FAILED) return
        queue.remove(albumId)
        if (currentId == albumId) currentJob?.cancel()
        modify(albumId) { it.copy(status = DownloadStatus.PAUSED) }
        if (PlaylistDownloads.isKey(albumId)) {
            playlists.setStatus(albumId, ManifestStatus.PAUSED)
        } else {
            editManifest(albumId) { it.copy(status = ManifestStatus.PAUSED) }
        }
        wake()
    }

    fun resume(albumId: String) {
        if (_active.value[albumId]?.status != DownloadStatus.PAUSED) return
        if (PlaylistDownloads.isKey(albumId)) {
            playlists.setStatus(albumId, ManifestStatus.DOWNLOADING)
        } else {
            editManifest(albumId) { it.copy(status = ManifestStatus.DOWNLOADING) }
        }
        modify(albumId) { it.copy(status = DownloadStatus.QUEUED) }
        enqueue(albumId)
    }

    /** Tries a failed album again, in the current default format; tracks already on disk are kept. */
    fun retry(albumId: String) {
        val state = _active.value[albumId] ?: return
        if (state.status != DownloadStatus.FAILED) return
        if (PlaylistDownloads.isKey(albumId)) return playlists.retry(albumId)
        val format = settings.defaultFormat
        LocalFiles.createDirectories(paths.albumDir(albumId))
        val manifest = readManifest(albumId) ?: DownloadManifest(albumId, state.title, state.artist)
        writeManifest(manifest.copy(format = DownloadManifest.formatName(format), status = ManifestStatus.DOWNLOADING, error = null))
        modify(albumId) { it.copy(format = format, status = DownloadStatus.QUEUED, error = null) }
        enqueue(albumId)
    }

    /** Stops an album and deletes its files. */
    fun cancel(albumId: String) {
        if (PlaylistDownloads.isKey(albumId)) return playlists.delete(PlaylistDownloads.id(albumId))
        queue.remove(albumId)
        val job = if (currentId == albumId) currentJob else null
        job?.cancel()
        _active.update { it - albumId }
        val dir = paths.albumDir(albumId)
        LocalFiles.deleteRecursively(dir)
        // A track may still be closing its file.
        job?.let { scope.launch { it.join(); LocalFiles.deleteRecursively(dir) } }
        wake()
    }

    /** Deletes downloads (complete or not) and their files. */
    fun delete(albumIds: Collection<String>) {
        for (id in albumIds) {
            if (id in _active.value) cancel(id) else LocalFiles.deleteRecursively(paths.albumDir(id))
        }
        _downloaded.update { list -> list.filter { it.albumId !in albumIds } }
        playlists.albumsChanged()
    }

    /** Complete and not in progress. */
    fun isDownloaded(albumId: String): Boolean =
        albumId !in _active.value && _downloaded.value.any { it.albumId == albumId }

    /** For a merged multi-disc album: every disc is downloaded. */
    fun isDownloaded(album: JellyfinAlbum): Boolean = album.allDiscs.all { isDownloaded(it.id) }

    fun state(albumId: String): DownloadAlbumState? = _active.value[albumId]

    fun downloadedAlbum(albumId: String): DownloadedAlbum? =
        _downloaded.value.firstOrNull { it.albumId == albumId }?.takeIf { albumId !in _active.value }

    /** Space used by complete downloads, in bytes. */
    val totalSize: Long get() = _downloaded.value.sumOf { it.sizeBytes }

    /** Playing time of complete downloads, in seconds. */
    val totalDuration: Double get() = _downloaded.value.sumOf { it.durationSeconds }

    /** Downloaded albums for [LocalSearch]. */
    fun localAlbums(): List<LocalAlbum> = _downloaded.value.map { it.toLocalAlbum() }

    /**
     * Call when the network or "Download over mobile data" changes. On a
     * metered network with mobile data off, the running album stops (its
     * files are kept) and everything waits for Wi‑Fi.
     */
    fun networkChanged() {
        if (!networkAllowed()) {
            currentJob?.cancel()
            markWaiting()
        } else {
            for (id in queue) modify(id) { if (it.status == DownloadStatus.WAITING_FOR_WIFI) it.copy(status = DownloadStatus.QUEUED) else it }
        }
        wake()
    }

    /**
     * "Download over mobile data" switched. Waiting albums carry on at once
     * when it's turned on; the runner is started again so it picks up the
     * new network rule (Android: Wi‑Fi only or any network).
     */
    fun setAllowCellular(on: Boolean) {
        if (settings.allowCellular == on) return
        settings.allowCellular = on
        networkChanged()
        if (queue.isNotEmpty() && !closed) runner.restart(this)
    }

    /** Stops work (sign-out). Unfinished albums stay "downloading" on disk and carry on next time. */
    fun close() {
        closed = true
        queue.clear()
        currentJob?.cancel()
        wake()
    }

    /**
     * Downloads queued albums one at a time, until none are left. Called by
     * the [DownloadRunner]; a second call waits for the first.
     */
    suspend fun runQueue() {
        runLock.withLock {
            withContext(dispatcher) {
                try {
                    while (!closed) {
                        val id = queue.firstOrNull() ?: break
                        if (!networkAllowed()) {
                            markWaiting()
                            wakeups.drop(1).first()
                            continue
                        }
                        if (PlaylistDownloads.isKey(id) && !isOnline()) {
                            // No connection: the playlist waits in the queue.
                            modify(id) { if (it.status == DownloadStatus.DOWNLOADING) it.copy(status = DownloadStatus.QUEUED) else it }
                            wakeups.drop(1).first()
                            continue
                        }
                        for (other in queue) {
                            modify(other) { it.copy(status = if (other == id) DownloadStatus.DOWNLOADING else DownloadStatus.QUEUED) }
                        }
                        currentId = id
                        val job = launch { if (PlaylistDownloads.isKey(id)) playlists.run(id) else downloadAlbum(id) }
                        currentJob = job
                        job.join()
                        currentId = null
                        currentJob = null
                    }
                } finally {
                    // Stopped from outside (the runner): what was running waits in the queue.
                    for (id in queue) modify(id) { if (it.status == DownloadStatus.DOWNLOADING) it.copy(status = DownloadStatus.QUEUED) else it }
                    currentId = null
                    currentJob = null
                }
            }
        }
        if (queue.isEmpty()) runner.stop()
    }

    // Work

    private class Planned(val track: JellyfinTrack, val format: AudioFormat, val entry: ManifestTrack)

    /** The network went metered with mobile data off. */
    internal class WaitForWifi : Exception()

    private suspend fun downloadAlbum(id: String) {
        val start = readManifest(id) ?: _active.value[id]?.let { DownloadManifest(id, it.title, it.artist) }
        if (start == null) {
            queue.remove(id)
            return
        }
        val preferred = start.preferredFormat
        val tracks = try {
            api.tracks(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(id, e.message ?: "Failed to fetch track list")
            return
        }
        if (tracks.isEmpty()) return fail(id, "The album has no tracks")

        val dir = paths.albumDir(id)
        val planned = withContext(ioDispatcher) {
            LocalFiles.createDirectories(dir)
            plan(tracks, preferred, LocalFiles.list(dir))
        }
        val manifest = start.copy(
            status = ManifestStatus.DOWNLOADING, error = null, expectedTracks = tracks.size,
            tracks = planned.map { it.entry },
        )
        writeManifest(manifest)
        val missing = planned.filter { !LocalFiles.exists("$dir/${it.entry.file}") }
        val done = planned.size - missing.size
        modify(id) {
            it.copy(
                expectedTracks = tracks.size, completedTracks = done, error = null,
                progress = if (tracks.isEmpty()) 0.0 else done.toDouble() / tracks.size,
            )
        }

        saveCovers(id, planned)

        val fractions = MutableStateFlow<Map<String, Double>>(emptyMap())
        val slots = Semaphore(2)
        try {
            coroutineScope {
                for (p in missing) {
                    launch {
                        slots.withPermit {
                            if (!networkAllowed()) throw WaitForWifi()
                            downloader.download(DownloadUrls.url(api, p.track, p.format), "$dir/${p.entry.file}") { bytes, total ->
                                val f = if (total != null && total > 0) (bytes.toDouble() / total).coerceIn(0.0, 0.99) else 0.0
                                fractions.update { it + (p.track.id to f) }
                                publish(id, done, tracks.size, fractions.value)
                            }
                            fractions.update { it + (p.track.id to 1.0) }
                            publish(id, done, tracks.size, fractions.value)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: WaitForWifi) {
            markWaiting()
            return
        } catch (e: Exception) {
            return fail(id, "Track download failed: ${e.message ?: e::class.simpleName}")
        }
        complete(id)
    }

    /** Numbers, formats and file names; a file kept from an earlier try (maybe in another format) is reused. */
    private fun plan(tracks: List<JellyfinTrack>, preferred: AudioFormat?, existing: List<String>): List<Planned> {
        val onDisk = existing.filter {
            !it.endsWith(DownloadPaths.PART) && !it.endsWith(".tmp") &&
                it != DownloadPaths.MANIFEST && it != DownloadPaths.COVER && it != DownloadPaths.COVERS
        }
        return tracks.zip(JellyfinTrack.fileNumbers(tracks)).map { (track, number) ->
            val format = storedFormat(AudioFormat.downloadFormat(track, preferred))
            val name = DownloadPaths.trackFileName(number, track.displayName, format)
            val file = onDisk.firstOrNull { DownloadPaths.stem(it) == DownloadPaths.stem(name) } ?: name
            val label = if (DownloadUrls.isOriginal(track, format)) {
                PlaybackTrack.qualityLabel(format, track.mediaSource?.bitrate)
            } else {
                format.displayName
            }
            Planned(
                track, format,
                ManifestTrack(
                    number = number, trackId = track.id, title = track.displayName, file = file,
                    durationSeconds = (track.runTimeTicks ?: 0) / 10_000_000.0,
                    normalizationGain = track.normalizationGain, qualityLabel = label, hasOwnArt = track.hasOwnArt,
                    format = format.displayName,
                ),
            )
        }
    }

    /** `cover.jpg` and the tracks' own pictures (600 px), if not saved yet. Failures are skipped. */
    private suspend fun saveCovers(id: String, planned: List<Planned>) {
        val wanted = buildList {
            add(id to paths.coverPath(id))
            planned.filter { it.track.hasOwnArt }.forEach { add(it.track.id to paths.trackCoverPath(id, it.entry.file)) }
        }
        for ((itemId, path) in wanted) {
            if (LocalFiles.exists(path)) continue
            val bytes = fetchImage(itemId) ?: continue
            withContext(ioDispatcher) {
                runCatching {
                    path.substringBeforeLast('/').let(LocalFiles::createDirectories)
                    LocalFiles.writeBytes(path, bytes)
                }
            }
        }
    }

    internal suspend fun fetchImage(itemId: String): ByteArray? = try {
        val response = api.http.get(api.imageUrl(itemId, COVER_SIZE)) {
            header(HttpHeaders.Authorization, api.authorizationHeader())
        }
        response.readRawBytes().takeIf { response.status.isSuccess() && it.isNotEmpty() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    internal fun publish(id: String, done: Int, expected: Int, fractions: Map<String, Double>) {
        if (expected <= 0) return
        modify(id) {
            it.copy(
                completedTracks = done + fractions.values.count { f -> f >= 1.0 },
                progress = ((done + fractions.values.sum()) / expected).coerceIn(0.0, 1.0),
            )
        }
    }

    private fun complete(id: String) {
        queue.remove(id)
        val manifest = editManifest(id) { it.copy(status = ManifestStatus.COMPLETE, downloadedAt = now(), error = null) }
        _active.update { it - id }
        if (manifest != null) addDownloaded(manifest)
    }

    private fun fail(id: String, message: String) {
        queue.remove(id)
        modify(id) { it.copy(status = DownloadStatus.FAILED, error = message) }
        editManifest(id) { it.copy(status = ManifestStatus.FAILED, error = message) }
    }

    // Bookkeeping

    /** A playlist's run ended (done, or its download is gone): out of the queue and the active list. */
    internal fun finished(key: String) {
        queue.remove(key)
        _active.update { it - key }
    }

    /** Takes a playlist out of the queue and the active list, stopping it if it's running; returns its job. */
    internal fun dequeue(key: String): Job? {
        queue.remove(key)
        val job = if (currentId == key) currentJob else null
        job?.cancel()
        _active.update { it - key }
        wake()
        return job
    }

    /** Starts the running playlist over (its list changed); the queue runs it again. */
    internal fun restart(key: String) {
        if (currentId == key) currentJob?.cancel()
        wake()
    }

    internal fun failed(key: String, message: String) {
        queue.remove(key)
        modify(key) { it.copy(status = DownloadStatus.FAILED, error = message) }
    }

    /** During start-up, before the runner starts. */
    internal fun queueRestored(key: String) {
        if (key !in queue) queue += key
    }

    internal fun enqueue(id: String) {
        if (closed) return
        if (id !in queue) queue += id
        if (!networkAllowed()) markWaiting()
        wake()
        runner.start(this)
    }

    internal fun networkAllowed(): Boolean = settings.allowCellular || !isExpensiveNetwork()

    internal fun markWaiting() {
        for (id in queue) modify(id) { it.copy(status = DownloadStatus.WAITING_FOR_WIFI) }
    }

    private fun wake() = wakeups.update { it + 1 }

    internal fun setState(state: DownloadAlbumState) = _active.update { it + (state.albumId to state) }

    internal fun modify(id: String, change: (DownloadAlbumState) -> DownloadAlbumState) =
        _active.update { map -> map[id]?.let { map + (id to change(it)) } ?: map }

    private fun readManifest(id: String): DownloadManifest? =
        LocalFiles.readText(paths.manifestPath(id))?.let(DownloadManifest::decode)

    private fun writeManifest(manifest: DownloadManifest) {
        runCatching { LocalFiles.writeText(paths.manifestPath(manifest.albumId), manifest.encode()) }
    }

    /** Changes a saved manifest; returns the new one (null if there's none). */
    private fun editManifest(id: String, change: (DownloadManifest) -> DownloadManifest): DownloadManifest? =
        readManifest(id)?.let(change)?.also(::writeManifest)

    private fun addDownloaded(manifest: DownloadManifest) {
        val album = toDownloaded(manifest)
        _downloaded.update { list -> newestFirst(list.filter { it.albumId != album.albumId } + album) }
    }

    private fun toDownloaded(manifest: DownloadManifest): DownloadedAlbum {
        val dir = paths.albumDir(manifest.albumId)
        return DownloadedAlbum(
            albumId = manifest.albumId, title = manifest.title, artist = manifest.artist, year = manifest.year,
            format = manifest.format, downloadedAt = manifest.downloadedAt ?: 0,
            sizeBytes = LocalFiles.totalSize(dir), durationSeconds = manifest.tracks.sumOf { it.durationSeconds },
            tracks = manifest.tracks, directory = dir,
        )
    }

    /** Rebuilds the lists from the manifests on disk. Folders without one are left-overs and go. */
    private fun restore() {
        val downloaded = mutableListOf<DownloadedAlbum>()
        val active = mutableMapOf<String, DownloadAlbumState>()
        for (name in LocalFiles.list(paths.root)) {
            val dir = "${paths.root}/$name"
            if (!LocalFiles.isDirectory(dir) || name == PlaylistPaths.FOLDER) continue
            var manifest = LocalFiles.readText("$dir/${DownloadPaths.MANIFEST}")?.let(DownloadManifest::decode)
            if (manifest == null) {
                LocalFiles.deleteRecursively(dir)
                continue
            }
            // Files this device can't play (ALAC on the Pad): delete them and
            // download those tracks again, as FLAC.
            val unplayable = manifest.tracks.filterNot(::canPlay)
            if (unplayable.isNotEmpty()) {
                unplayable.forEach { LocalFiles.deleteRecursively("$dir/${it.file}") }
                manifest = manifest.copy(status = ManifestStatus.DOWNLOADING, error = null)
                writeManifest(manifest)
            }
            val id = manifest.albumId
            if (DownloadManifest.isDownloaded(manifest) { LocalFiles.exists("$dir/$it") }) {
                downloaded += toDownloaded(manifest)
                continue
            }
            val done = manifest.tracks.count { LocalFiles.exists("$dir/${it.file}") }
            var state = DownloadAlbumState(
                albumId = id, title = manifest.title, artist = manifest.artist, format = manifest.preferredFormat,
                expectedTracks = manifest.expectedTracks, completedTracks = done,
                progress = if (manifest.expectedTracks > 0) done.toDouble() / manifest.expectedTracks else 0.0,
            )
            state = when (manifest.status) {
                ManifestStatus.PAUSED -> state.copy(status = DownloadStatus.PAUSED)
                ManifestStatus.FAILED -> state.copy(status = DownloadStatus.FAILED, error = manifest.error)
                ManifestStatus.DOWNLOADING -> state.also { queue += id }
                ManifestStatus.COMPLETE -> {
                    // Files went missing: offer Retry.
                    val message = "Some downloaded files are missing"
                    writeManifest(manifest.copy(status = ManifestStatus.FAILED, error = message))
                    state.copy(status = DownloadStatus.FAILED, error = message)
                }
            }
            active[id] = state
        }
        _downloaded.value = newestFirst(downloaded)
        _active.value = active
        playlists.restore()
        if (queue.isNotEmpty() && !networkAllowed()) markWaiting()
    }

    companion object {
        /**
         * Runs [FlacRepair] on every finished `.flac` file under `dir` (album
         * and playlist folders). Returns how many were repaired.
         */
        internal fun repairFlacFiles(dir: String): Int {
            var repaired = 0
            // A folder deleted meanwhile (an album removed) is skipped.
            val names = runCatching { LocalFiles.list(dir) }.getOrDefault(emptyList())
            for (name in names) {
                val path = "$dir/$name"
                if (LocalFiles.isDirectory(path)) {
                    repaired += repairFlacFiles(path)
                } else if (name.endsWith(".flac", ignoreCase = true) && FlacRepair.repairFile(path) == FlacRepair.Result.REPAIRED) {
                    repaired++
                }
            }
            return repaired
        }

        /** Size of saved pictures (px), as iOS. */
        const val COVER_SIZE = 600

        /** Newest download first; ties by ID, so the order is stable (as iOS `newestFirst`). */
        fun newestFirst(albums: List<DownloadedAlbum>): List<DownloadedAlbum> =
            albums.sortedWith(compareByDescending<DownloadedAlbum> { it.downloadedAt }.thenBy { it.albumId })
    }
}
