package gg.dekiru.musicplayer.downloads

import gg.dekiru.musicplayer.data.models.JellyfinPlaylist
import gg.dekiru.musicplayer.data.models.JellyfinTrack
import gg.dekiru.musicplayer.data.models.PlaylistEntry
import gg.dekiru.musicplayer.playback.AudioFormat
import gg.dekiru.musicplayer.playback.PlaybackTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One row of a downloaded playlist, as saved in `playlist.json`. Rows are
 * in playlist order; a song that's in the playlist twice has two rows with
 * the same [trackId] and the same [file] (it's stored once).
 */
@Serializable
data class PlaylistManifestTrack(
    val trackId: String,
    val title: String,
    /** The album artist, as the playlist page shows it. */
    val artist: String = "",
    val album: String = "",
    val albumId: String? = null,
    val durationSeconds: Double = 0.0,
    /** The track's number on its album; 0 when it has none. */
    val trackNumber: Int = 0,
    /**
     * File name in the playlist's folder (`<trackId>.<ext>`); null when the
     * song isn't kept there because a downloaded album has it.
     */
    val file: String? = null,
    /** The item whose picture the row shows (the track's own or its album's), saved as `covers/<art>.jpg`. */
    val art: String? = null,
    val normalizationGain: Double? = null,
    /** e.g. "FLAC 905 kbps"; null when the album's file is used. */
    val qualityLabel: String? = null,
    /** The file's format, e.g. "FLAC". */
    val format: String? = null,
)

/** `playlist.json` in each playlist's folder: enough for the playlist's page to work offline. */
@Serializable
data class PlaylistManifest(
    val playlistId: String,
    val name: String,
    /** The chosen format's name ("flac", …) or "original". */
    val format: String = DownloadManifest.ORIGINAL,
    /** Epoch ms, set the first time the download is complete. */
    val downloadedAt: Long? = null,
    val status: ManifestStatus = ManifestStatus.DOWNLOADING,
    val error: String? = null,
    /** The server has a picture for the playlist itself (saved as `cover.jpg`). */
    val hasArt: Boolean = false,
    /** As the server lists it, for the "Date Added" order offline. */
    val dateCreated: String? = null,
    /** In playlist order, duplicates included. */
    val tracks: List<PlaylistManifestTrack> = emptyList(),
) {
    val preferredFormat: AudioFormat? get() = DownloadManifest.formatFromName(format)

    /** Each song once, in the order it first appears. */
    val uniqueTracks: List<PlaylistManifestTrack> get() = tracks.distinctBy { it.trackId }

    fun encode(): String = json.encodeToString(serializer(), this)

    /** The rows as the playlist page lists them. */
    fun toEntries(): List<PlaylistEntry> = tracks.mapIndexed { position, t ->
        PlaylistEntry(
            position = position,
            entryId = t.trackId,
            track = JellyfinTrack(
                id = t.trackId, name = t.title, indexNumber = t.trackNumber.takeIf { it > 0 },
                runTimeTicks = (t.durationSeconds * 10_000_000).toLong(), album = t.album, albumId = t.albumId,
                normalizationGain = t.normalizationGain,
                hasOwnArt = t.art != null && t.art == t.trackId && t.albumId != null,
            ),
            albumName = t.album.takeIf { it.isNotEmpty() },
            albumId = t.albumId,
            albumArtist = t.artist.takeIf { it.isNotEmpty() },
        )
    }

    /** The playlist as the lists show it, counted from the saved rows. */
    fun toPlaylist() = JellyfinPlaylist(
        id = playlistId, name = name, songCount = tracks.size,
        runTimeTicks = (tracks.sumOf { it.durationSeconds } * 10_000_000).toLong(),
        dateCreated = dateCreated, hasArt = hasArt,
    )

    companion object {
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun decode(text: String): PlaylistManifest? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}

/**
 * Where playlist downloads live, inside the downloads folder:
 *
 * ```
 * <root>/playlists/<playlistId>/<trackId>.flac     a song (".part" while downloading), stored once
 * <root>/playlists/<playlistId>/playlist.json      the manifest
 * <root>/playlists/<playlistId>/cover.jpg          the playlist's own picture, when the server has one
 * <root>/playlists/<playlistId>/covers/<itemId>.jpg   a row's picture (its album's, or the track's own)
 * ```
 */
class PlaylistPaths(downloadsRoot: String) {
    val root: String = "${downloadsRoot.trimEnd('/')}/$FOLDER"

    fun dir(playlistId: String): String = "$root/${DownloadPaths.sanitize(playlistId)}"
    fun manifestPath(playlistId: String): String = "${dir(playlistId)}/$MANIFEST"
    fun coverPath(playlistId: String): String = "${dir(playlistId)}/${DownloadPaths.COVER}"
    fun artPath(playlistId: String, itemId: String): String = "${dir(playlistId)}/${DownloadPaths.COVERS}/${DownloadPaths.sanitize(itemId)}.jpg"
    fun trackPath(playlistId: String, fileName: String): String = "${dir(playlistId)}/$fileName"

    companion object {
        const val FOLDER = "playlists"
        const val MANIFEST = "playlist.json"

        fun trackFileName(trackId: String, format: AudioFormat): String = "${DownloadPaths.sanitize(trackId)}.${format.fileExtension}"
    }
}

/** A playlist with a download on this device (complete or not). */
data class DownloadedPlaylist(
    val manifest: PlaylistManifest,
    /** The playlist's folder. */
    val directory: String,
    /** Space its own files take (songs kept in a downloaded album's folder aren't counted). */
    val sizeBytes: Long,
    /** Every song is on the device and nothing is under way. */
    val isComplete: Boolean,
) {
    val playlistId: String get() = manifest.playlistId
    val name: String get() = manifest.name
    val downloadedAt: Long get() = manifest.downloadedAt ?: 0
    val songCount: Int get() = manifest.tracks.size
    val durationSeconds: Double get() = manifest.tracks.sumOf { it.durationSeconds }

    /** It has been complete at least once: it's listed offline (while songs are fetched again too). */
    val isAvailableOffline: Boolean get() = manifest.downloadedAt != null

    /** A row's saved picture, if it's on disk. */
    fun artPath(track: PlaylistManifestTrack): String? =
        track.art?.let { "$directory/${DownloadPaths.COVERS}/${DownloadPaths.sanitize(it)}.jpg" }?.takeIf(LocalFiles::exists)

    /** The playlist's picture: its own, else its first song's; null when neither is saved. */
    val coverPath: String?
        get() = "$directory/${DownloadPaths.COVER}".takeIf(LocalFiles::exists) ?: manifest.tracks.firstOrNull()?.let(::artPath)
}

/** A playlist's song found on this device. */
data class LocalPlaylistTrack(
    val path: String,
    val artPath: String?,
    val qualityLabel: String?,
    val normalizationGain: Double?,
)

/**
 * Downloaded playlists (part of [DownloadManager], which runs them in the
 * same queue as albums, so Wi‑Fi only, pause, resume, retry, cancel and
 * the Android notification work alike). In the manager's `active` map a
 * playlist goes by [key], not its bare ID.
 *
 * A song is stored once per playlist, as `<trackId>.<ext>`. A song a
 * downloaded album already has isn't downloaded: the playlist plays the
 * album's file. When that album's download goes, the playlist's songs that
 * relied on it are downloaded into the playlist's folder.
 */
class PlaylistDownloads internal constructor(private val manager: DownloadManager) {

    val paths = PlaylistPaths(manager.paths.root)

    private val _all = MutableStateFlow<List<DownloadedPlaylist>>(emptyList())

    /** Every playlist with a download, complete or not, newest first. */
    val all: StateFlow<List<DownloadedPlaylist>> = _all.asStateFlow()

    /** Complete downloads. */
    val downloaded: List<DownloadedPlaylist> get() = _all.value.filter { it.isComplete }

    /** Those the offline lists show. */
    val availableOffline: List<DownloadedPlaylist> get() = _all.value.filter { it.isAvailableOffline }

    fun saved(playlistId: String): DownloadedPlaylist? = _all.value.firstOrNull { it.playlistId == playlistId }

    fun isDownloaded(playlistId: String): Boolean = saved(playlistId)?.isComplete == true

    fun state(playlistId: String): DownloadAlbumState? = manager.state(key(playlistId))

    // Public API

    /** Downloads a playlist with the songs its page shows. Nothing happens if it has a download already. */
    fun download(playlist: JellyfinPlaylist, entries: List<PlaylistEntry>, format: AudioFormat? = manager.settings.defaultFormat) {
        val id = playlist.id
        if (read(id) != null) return
        LocalFiles.createDirectories(paths.dir(id))
        val start = PlaylistManifest(
            playlistId = id, name = playlist.displayName, format = DownloadManifest.formatName(format),
            hasArt = playlist.hasArt, dateCreated = playlist.dateCreated,
        )
        val manifest = start.copy(tracks = plan(entries, start).map { it.row })
        write(manifest)
        begin(manifest)
    }

    /**
     * The playlist as the server has it now (its page loaded, or it was
     * edited): a downloaded playlist's manifest follows, files of songs no
     * longer in it go, and new songs are downloaded. `playlist` null keeps
     * the saved name.
     */
    fun sync(playlistId: String, entries: List<PlaylistEntry>, playlist: JellyfinPlaylist? = null) {
        val old = read(playlistId) ?: return
        val manifest = old.copy(
            name = playlist?.displayName ?: old.name,
            hasArt = playlist?.hasArt ?: old.hasArt,
            dateCreated = playlist?.dateCreated ?: old.dateCreated,
            tracks = plan(entries, old).map { it.row },
        )
        if (manifest != old) {
            write(manifest)
            prune(manifest)
        }
        val key = key(playlistId)
        val state = manager.state(key)
        if (state != null) manager.modify(key) { it.copy(title = manifest.name) }
        when {
            // Paused or failed: the new list waits for Resume or Retry.
            state?.status == DownloadStatus.PAUSED || state?.status == DownloadStatus.FAILED -> refresh(manifest)
            // Under way: start it again with the new list.
            state != null -> {
                refresh(manifest)
                manager.restart(key)
            }
            missing(manifest).isNotEmpty() -> begin(manifest)
            else -> {
                refresh(manifest)
                // Pictures for new rows, quietly.
                if (manifest != old) manager.scope.launch {
                    saveCovers(manifest)
                    read(playlistId)?.let(::refresh)
                }
            }
        }
    }

    /** Removes downloads (complete or not) and their files. */
    fun delete(playlistIds: Collection<String>) {
        for (id in playlistIds) {
            val job = manager.dequeue(key(id))
            val dir = paths.dir(id)
            LocalFiles.deleteRecursively(dir)
            // A track may still be closing its file.
            job?.let { manager.scope.launch { it.join(); LocalFiles.deleteRecursively(dir) } }
        }
        _all.update { list -> list.filter { it.playlistId !in playlistIds } }
    }

    fun delete(playlistId: String) = delete(listOf(playlistId))

    /**
     * Finds a song on this device: the playlist's own folder first, then a downloaded album that has
     * it; null means stream. Songs in downloaded albums are found for any playlist, downloaded or not.
     *
     * With `anywhere` (what the player asks for every queue it builds, from any page) the search is the
     * same set of downloads the pages show as Downloaded, plus what is on disk of those under way: an
     * album being downloaded (or downloaded again) has its finished files used, and every other
     * playlist's own files count too. A track ID is compared without dashes or capitals, and a file
     * is found by its name without the extension (ALAC saved as FLAC).
     */
    fun resolver(playlistId: String, anywhere: Boolean = false): (trackId: String) -> LocalPlaylistTrack? {
        val saved = saved(playlistId)
        val own = saved?.manifest?.uniqueTracks.orEmpty().associateBy { DownloadPaths.idKey(it.trackId) }
        val albums = albumTracks()
        val partial = if (anywhere) manager.inProgressAlbums().let(::tracksOf) else emptyMap()
        val others = if (anywhere) _all.value.filter { it.playlistId != playlistId } else emptyList()
        return resolve@{ trackId ->
            val key = DownloadPaths.idKey(trackId)
            val row = own[key]
            val file = row?.file?.let { LocalFiles.existingFile("${saved!!.directory}/$it") }
            if (row != null && file != null) {
                return@resolve LocalPlaylistTrack(file, saved!!.artPath(row), row.qualityLabel, row.normalizationGain)
            }
            for (source in listOf(albums, partial)) {
                val (album, track) = source[key] ?: continue
                val path = LocalFiles.existingFile(album.trackPath(track)) ?: continue
                val art = row?.let { saved!!.artPath(it) } ?: listOf(album.artPath(track), album.coverPath).firstOrNull(LocalFiles::exists)
                return@resolve LocalPlaylistTrack(path, art, track.qualityLabel, track.normalizationGain)
            }
            for (playlist in others) {
                val other = playlist.manifest.tracks.firstOrNull { DownloadPaths.idKey(it.trackId) == key && it.file != null } ?: continue
                val path = LocalFiles.existingFile("${playlist.directory}/${other.file}") ?: continue
                return@resolve LocalPlaylistTrack(path, playlist.artPath(other), other.qualityLabel, other.normalizationGain)
            }
            null
        }
    }

    // Called by the manager

    /** An album download was deleted: playlists that played its files download those songs themselves. */
    internal fun albumsChanged() {
        for (playlist in _all.value) {
            if (!playlist.isComplete) continue
            if (missing(playlist.manifest).isNotEmpty()) begin(playlist.manifest)
        }
    }

    internal fun setStatus(key: String, status: ManifestStatus) {
        edit(id(key)) { it.copy(status = status) }?.let(::refresh)
    }

    /** Tries a failed playlist again; what's on disk is kept. */
    internal fun retry(key: String) {
        val manifest = edit(id(key)) { it.copy(status = ManifestStatus.DOWNLOADING, error = null) } ?: return
        refresh(manifest)
        manager.modify(key) { it.copy(status = DownloadStatus.QUEUED, error = null) }
        manager.enqueue(key)
    }

    /** One run of a queued playlist: the server's list, then the songs not on the device yet. */
    internal suspend fun run(key: String) {
        val id = id(key)
        val start = read(id)
        if (start == null) {
            manager.finished(key)
            return
        }
        val entries = try {
            manager.api.playlistTracks(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(key, e.message ?: "Failed to fetch track list")
        }
        val dir = paths.dir(id)
        val planned = withContext(manager.ioDispatcher) {
            LocalFiles.createDirectories(dir)
            plan(entries, start)
        }
        val manifest = start.copy(status = ManifestStatus.DOWNLOADING, error = null, tracks = planned.map { it.row })
        write(manifest)
        prune(manifest)
        refresh(manifest)

        val unique = planned.distinctBy { it.row.trackId }
        val wanted = unique.filter { it.row.file != null && !LocalFiles.exists("$dir/${it.row.file}") }
        val done = unique.size - wanted.size
        manager.modify(key) {
            it.copy(
                title = manifest.name, expectedTracks = unique.size, completedTracks = done, error = null,
                progress = if (unique.isEmpty()) 0.0 else done.toDouble() / unique.size,
            )
        }

        saveCovers(manifest)

        val fractions = MutableStateFlow<Map<String, Double>>(emptyMap())
        val slots = Semaphore(2)
        try {
            coroutineScope {
                for (p in wanted) {
                    launch {
                        slots.withPermit {
                            if (!manager.networkAllowed()) throw DownloadManager.WaitForWifi()
                            manager.downloader.download(DownloadUrls.url(manager.api, p.entry.track, p.format), "$dir/${p.row.file}") { bytes, total ->
                                val f = if (total != null && total > 0) (bytes.toDouble() / total).coerceIn(0.0, 0.99) else 0.0
                                fractions.update { it + (p.row.trackId to f) }
                                manager.publish(key, done, unique.size, fractions.value)
                            }
                            fractions.update { it + (p.row.trackId to 1.0) }
                            manager.publish(key, done, unique.size, fractions.value)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DownloadManager.WaitForWifi) {
            manager.markWaiting()
            return
        } catch (e: Exception) {
            return fail(key, "Track download failed: ${e.message ?: e::class.simpleName}")
        }
        // An album it relied on may have gone meanwhile: go round again.
        if (missing(manifest).isNotEmpty()) return
        manager.finished(key)
        edit(id) { it.copy(status = ManifestStatus.COMPLETE, downloadedAt = it.downloadedAt ?: manager.now(), error = null) }?.let(::refresh)
    }

    /** Rebuilds the list from the manifests on disk; unfinished playlists carry on. Folders without a manifest go. */
    internal fun restore() {
        val found = mutableListOf<PlaylistManifest>()
        for (name in LocalFiles.list(paths.root)) {
            val dir = "${paths.root}/$name"
            val manifest = LocalFiles.readText("$dir/${PlaylistPaths.MANIFEST}")?.let(PlaylistManifest::decode)
            if (manifest == null) {
                LocalFiles.deleteRecursively(dir)
                continue
            }
            found += manifest
        }
        for (manifest in found) {
            val key = key(manifest.playlistId)
            val unique = manifest.uniqueTracks
            val absent = missing(manifest)
            val state = DownloadAlbumState(
                albumId = key, title = manifest.name, artist = "", format = manifest.preferredFormat,
                expectedTracks = unique.size, completedTracks = unique.size - absent.size,
                progress = if (unique.isEmpty()) 0.0 else (unique.size - absent.size).toDouble() / unique.size,
                playlistId = manifest.playlistId,
            )
            when {
                manifest.status == ManifestStatus.PAUSED -> manager.setState(state.copy(status = DownloadStatus.PAUSED))
                manifest.status == ManifestStatus.FAILED -> manager.setState(state.copy(status = DownloadStatus.FAILED, error = manifest.error))
                manifest.status == ManifestStatus.COMPLETE && absent.isEmpty() -> Unit
                else -> {
                    // Unfinished, or files (or the album they were in) went: download what's missing.
                    if (manifest.status != ManifestStatus.DOWNLOADING) write(manifest.copy(status = ManifestStatus.DOWNLOADING))
                    manager.setState(state)
                    manager.queueRestored(key)
                }
            }
        }
        _all.value = newestFirst(found.mapNotNull { read(it.playlistId) }.map(::toDownloaded))
    }

    // Work

    internal class Planned(val entry: PlaylistEntry, val format: AudioFormat, val row: PlaylistManifestTrack)

    /**
     * The manifest rows for `entries`. A song keeps the file it has in the
     * playlist's folder (whatever its format); one without a file relies on
     * a downloaded album that has it, else it gets `<trackId>.<ext>` in the
     * download format, to be downloaded.
     */
    internal fun plan(entries: List<PlaylistEntry>, old: PlaylistManifest): List<Planned> {
        val existing = LocalFiles.list(paths.dir(old.playlistId)).filter { !it.endsWith(DownloadPaths.PART) && !isOtherFile(it) }
        val before = old.tracks.associateBy { it.trackId }
        val albums = albumTracks()
        return entries.map { entry ->
            val track = entry.track
            val format = manager.storedFormat(AudioFormat.downloadFormat(track, old.preferredFormat))
            val name = PlaylistPaths.trackFileName(track.id, format)
            val onDisk = existing.firstOrNull { DownloadPaths.stem(it) == DownloadPaths.stem(name) }
            val inAlbum = albums[DownloadPaths.idKey(track.id)]?.let { (album, t) -> LocalFiles.exists(album.trackPath(t)) } == true
            val file = onDisk ?: if (inAlbum) null else name
            val kept = before[track.id]?.takeIf { onDisk != null && it.file == onDisk }
            val label = when {
                file == null -> null
                kept != null -> kept.qualityLabel
                DownloadUrls.isOriginal(track, format) -> PlaybackTrack.qualityLabel(format, track.mediaSource?.bitrate)
                else -> format.displayName
            }
            Planned(
                entry, format,
                PlaylistManifestTrack(
                    trackId = track.id, title = track.displayName, artist = entry.albumArtist.orEmpty(),
                    album = entry.albumName.orEmpty(), albumId = entry.albumId,
                    durationSeconds = (track.runTimeTicks ?: 0) / 10_000_000.0, trackNumber = track.trackNumber,
                    file = file, art = entry.artItemId, normalizationGain = track.normalizationGain, qualityLabel = label,
                    format = if (file == null) null else kept?.format ?: format.displayName,
                ),
            )
        }
    }

    /** The songs not on this device: no file in the playlist's folder and no downloaded album with it. */
    internal fun missing(manifest: PlaylistManifest): List<PlaylistManifestTrack> {
        val dir = paths.dir(manifest.playlistId)
        val albums = albumTracks()
        return manifest.uniqueTracks.filter { row ->
            val own = row.file != null && LocalFiles.exists("$dir/${row.file}")
            !own && albums[DownloadPaths.idKey(row.trackId)]?.let { (album, t) -> LocalFiles.exists(album.trackPath(t)) } != true
        }
    }

    /** Deletes songs and pictures the manifest no longer lists. */
    private fun prune(manifest: PlaylistManifest) {
        val dir = paths.dir(manifest.playlistId)
        val songs = manifest.tracks.map { DownloadPaths.sanitize(it.trackId) }.toSet()
        for (name in LocalFiles.list(dir)) {
            if (isOtherFile(name)) continue
            if (DownloadPaths.stem(name.removeSuffix(DownloadPaths.PART)) !in songs) LocalFiles.deleteRecursively("$dir/$name")
        }
        val art = manifest.tracks.mapNotNull { it.art }.map(DownloadPaths::sanitize).toSet()
        val covers = "$dir/${DownloadPaths.COVERS}"
        for (name in LocalFiles.list(covers)) {
            if (DownloadPaths.stem(name) !in art) LocalFiles.deleteRecursively("$covers/$name")
        }
        if (!manifest.hasArt) LocalFiles.deleteRecursively(paths.coverPath(manifest.playlistId))
    }

    private fun isOtherFile(name: String): Boolean =
        name == PlaylistPaths.MANIFEST || name == DownloadPaths.COVER || name == DownloadPaths.COVERS || name.endsWith(".tmp")

    /** The playlist's own picture and each row's (600 px), if not saved yet. Failures are skipped. */
    private suspend fun saveCovers(manifest: PlaylistManifest) {
        val id = manifest.playlistId
        val wanted = buildList {
            if (manifest.hasArt) add(id to paths.coverPath(id))
            manifest.tracks.mapNotNull { it.art }.distinct().forEach { add(it to paths.artPath(id, it)) }
        }
        for ((itemId, path) in wanted) {
            if (LocalFiles.exists(path)) continue
            val bytes = manager.fetchImage(itemId) ?: continue
            withContext(manager.ioDispatcher) {
                runCatching {
                    // The playlist may have been removed meanwhile.
                    if (!LocalFiles.exists(paths.manifestPath(id))) return@runCatching
                    path.substringBeforeLast('/').let(LocalFiles::createDirectories)
                    LocalFiles.writeBytes(path, bytes)
                }
            }
        }
    }

    /** Marks a playlist as downloading and queues it. */
    private fun begin(manifest: PlaylistManifest) {
        val key = key(manifest.playlistId)
        val started = manifest.copy(status = ManifestStatus.DOWNLOADING, error = null)
        write(started)
        val unique = started.uniqueTracks
        val done = unique.size - missing(started).size
        manager.setState(
            DownloadAlbumState(
                albumId = key, title = started.name, artist = "", format = started.preferredFormat,
                expectedTracks = unique.size, completedTracks = done,
                progress = if (unique.isEmpty()) 0.0 else done.toDouble() / unique.size,
                playlistId = started.playlistId,
            ),
        )
        refresh(started)
        manager.enqueue(key)
    }

    private fun fail(key: String, message: String) {
        manager.failed(key, message)
        edit(id(key)) { it.copy(status = ManifestStatus.FAILED, error = message) }?.let(::refresh)
    }

    // Bookkeeping

    /** Track ID → the downloaded album that has it. */
    private fun albumTracks(): Map<String, Pair<DownloadedAlbum, ManifestTrack>> =
        tracksOf(manager.downloaded.value.filter { manager.state(it.albumId) == null })

    private fun tracksOf(albums: List<DownloadedAlbum>): Map<String, Pair<DownloadedAlbum, ManifestTrack>> = buildMap {
        for (album in albums) {
            for (track in album.tracks) putIfAbsent(DownloadPaths.idKey(track.trackId), album to track)
        }
    }

    private fun read(playlistId: String): PlaylistManifest? =
        LocalFiles.readText(paths.manifestPath(playlistId))?.let(PlaylistManifest::decode)

    private fun write(manifest: PlaylistManifest) {
        runCatching { LocalFiles.writeText(paths.manifestPath(manifest.playlistId), manifest.encode()) }
    }

    private fun edit(playlistId: String, change: (PlaylistManifest) -> PlaylistManifest): PlaylistManifest? =
        read(playlistId)?.let(change)?.also(::write)

    private fun toDownloaded(manifest: PlaylistManifest): DownloadedPlaylist {
        val dir = paths.dir(manifest.playlistId)
        return DownloadedPlaylist(
            manifest, dir, LocalFiles.totalSize(dir),
            isComplete = manifest.status == ManifestStatus.COMPLETE && manager.state(key(manifest.playlistId)) == null,
        )
    }

    /** Puts the playlist's current manifest in [all]. */
    private fun refresh(manifest: PlaylistManifest) {
        val playlist = toDownloaded(manifest)
        _all.update { list -> newestFirst(list.filter { it.playlistId != playlist.playlistId } + playlist) }
    }

    companion object {
        private const val KEY_PREFIX = "playlist:"

        /** A playlist's name in the manager's queue and `active` map. */
        fun key(playlistId: String): String = KEY_PREFIX + playlistId
        fun isKey(key: String): Boolean = key.startsWith(KEY_PREFIX)
        internal fun id(key: String): String = key.removePrefix(KEY_PREFIX)

        /** Newest download first (not complete yet: first of all); ties by ID. */
        fun newestFirst(playlists: List<DownloadedPlaylist>): List<DownloadedPlaylist> =
            playlists.sortedWith(compareByDescending<DownloadedPlaylist> { it.manifest.downloadedAt ?: Long.MAX_VALUE }.thenBy { it.playlistId })
    }
}
