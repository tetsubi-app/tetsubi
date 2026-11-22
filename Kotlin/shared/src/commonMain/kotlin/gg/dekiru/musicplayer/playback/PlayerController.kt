package gg.dekiru.musicplayer.playback

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.api.JellyfinError
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinPlaylist
import gg.dekiru.musicplayer.data.models.JellyfinTrack
import gg.dekiru.musicplayer.data.models.PlaylistEntry
import gg.dekiru.musicplayer.data.models.allDiscs
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.downloads.DownloadedAlbum
import gg.dekiru.musicplayer.downloads.LocalFiles
import gg.dekiru.musicplayer.downloads.LocalPlaylistTrack
import gg.dekiru.musicplayer.downloads.ManifestTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The player as the screens see it (as `PlayerManager` on iOS): queue,
 * current track, play/pause, skip, seek, shuffle, repeat, Maniac Mode, Up
 * next, play reporting, the mobile-data cap and volume normalisation. The
 * platform [engine] does the playing; this keeps the state, plans the
 * queue and reports plays to the server. A downloaded album plays from
 * its files, online or not. A playlist's queue is streamed, each track
 * with its own album and picture, and may hold the same song twice: the
 * queue goes by position, never by track ID.
 *
 * State is Compose state, read on the main thread.
 */
class PlayerController(
    private val api: JellyfinApi,
    private val engine: PlayerEngine,
    private val settings: PlaybackSettings,
    private val scope: CoroutineScope,
    /** Whether the network is metered (mobile data, hotspot): streams are capped. */
    private val isExpensiveNetwork: () -> Boolean = { false },
    /** Plays are reported to the server only when online. */
    private val isOnline: () -> Boolean = { true },
    /** The signed-in server's downloads, if any. */
    private val downloads: () -> DownloadManager? = { null },
    private val random: kotlin.random.Random = kotlin.random.Random,
) : EngineListener {

    var queue by mutableStateOf<List<PlaybackTrack>>(emptyList())
        private set
    var currentIndex by mutableStateOf(0)
        private set
    val currentTrack: PlaybackTrack? by derivedStateOf { queue.getOrNull(currentIndex) }
    var isPlaying by mutableStateOf(false)
        private set

    /** Seconds into the current track (updated twice a second while playing). */
    var position by mutableStateOf(0.0)
        private set
    var duration by mutableStateOf(0.0)
        private set
    var repeatMode by mutableStateOf(RepeatMode.OFF)
        private set
    var shuffle by mutableStateOf(false)
        private set
    var maniacMode by mutableStateOf(settings.maniacMode)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set

    /** Whether the full Now Playing screen is open (Play on an album opens it). */
    var isNowPlayingOpen by mutableStateOf(false)

    val isSupported: Boolean get() = engine.isSupported

    /** What the queue came from: the album's ID or the playlist's; null when nothing is loaded. */
    var queueSourceId by mutableStateOf<String?>(null)
        private set

    /**
     * The playing entry's place in its album or playlist (from 0), whatever
     * the queue's order; null when nothing is playing. Tells a playlist's
     * two rows of the same song apart.
     */
    val currentPlace: Int? get() = if (currentTrack == null) null else originalPlaces.getOrNull(currentIndex) ?: currentIndex

    /** Each queue entry's place in album or playlist order (before shuffling), so Maniac Mode can un-shuffle. Empty: nothing to restore. */
    private var originalPlaces: List<Int> = emptyList()

    /** The queue's engine items, entry for entry (Maniac Mode re-sends them in a new order). */
    private var engineItems: List<EngineItem> = emptyList()

    /**
     * The playing album's discs, in order (one for a single-disc album);
     * empty for a playlist's queue. The home-screen widget keeps them to
     * play the album again after the app was closed.
     */
    var playingDiscIds by mutableStateOf<List<String>>(emptyList())
        private set

    /** The album playing (the merged entry for a multi-disc album) and its discs; empty for a playlist's queue. */
    private var playingAlbumIds: Set<String> = emptySet()

    /** The streamed track with an open server report. */
    private var reportedTrack: PlaybackTrack? = null

    private var loadJob: Job? = null

    /** Asking the server for a downloaded album's Cast streams (see [playLocal]). */
    private var castLookupJob: Job? = null
    private var tickJob: Job? = null

    init {
        engine.listener = this
        engine.setAuthorization(api.authorizationHeader())
        engine.setSkipControlsEnabled(!maniacMode)
    }

    // Playing

    /**
     * Plays an album, every disc in order for a multi-disc album, from its
     * first track or from `startTrackId`. Opens Now Playing. When the album
     * (every disc of it) is downloaded, it plays from the files, always in
     * order from track 1.
     */
    fun play(album: JellyfinAlbum, startTrackId: String? = null, openNowPlaying: Boolean = true) {
        if (!engine.isSupported) return
        if (openNowPlaying) isNowPlayingOpen = true
        // Maniac Mode always starts at track 1.
        val start = if (maniacMode) null else startTrackId
        lastError = null
        loadJob?.cancel()
        downloadedDiscs(album)?.let { discs ->
            playLocal(discs, album)
            return
        }
        isLoading = true
        loadJob = scope.launch {
            val discs = album.allDiscs
            // Failures are caught inside each fetch: a failed child would cancel this whole load.
            val results = discs.map { disc ->
                async {
                    try {
                        Result.success(api.tracks(disc.id))
                    } catch (e: JellyfinError) {
                        Result.failure(e)
                    }
                }
            }.awaitAll()
            results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let {
                fail(it.message ?: "Couldn't load the album.")
                return@launch
            }
            val tracks = results.flatMap { it.getOrThrow() }
            if (tracks.isEmpty()) {
                fail("This album has no tracks.")
                return@launch
            }
            val (planned, startIndex) = planQueue(
                tracks.withIndex().toList(),
                forcedStart = start?.let { id -> tracks.indexOfFirst { it.id == id }.takeIf { it >= 0 } },
                shuffle = shuffle,
                random = random,
            )
            val entries = streamEntries(planned.map { it.value }) { i, track -> PlaybackTrack.from(track, album, i) }
            loadQueue(entries, album.id, albumIds(album), startIndex, discIds = discIds(album), places = planned.map { it.index })
        }
    }

    /**
     * Plays a playlist's loaded entries from the first, or from the entry at
     * position `startingAt` (a position, not a track ID: a song may be there
     * twice). Shuffle, Maniac Mode and the rest as for an album.
     *
     * Each song plays from this device when it's here: the playlist's own
     * download first, then a downloaded album that has it; else it's
     * streamed. With no connection the songs that aren't here are left out.
     *
     * Refused with no connection when the tapped song, or every song, isn't
     * on the device: the reason is returned for the page to show, and
     * nothing changes (what's playing, the queue, shuffle, Now Playing, the
     * player's error). `shuffled` turns shuffle on first (the Shuffle
     * button), but not for a refused play. Returns null otherwise.
     */
    fun play(
        playlist: JellyfinPlaylist,
        entries: List<PlaylistEntry>,
        startingAt: Int? = null,
        openNowPlaying: Boolean = true,
        shuffled: Boolean = false,
    ): String? {
        if (!engine.isSupported) return null
        // Maniac Mode always starts at the first song.
        val start = if (maniacMode) null else startingAt?.takeIf { it in entries.indices }
        val local = downloads()?.playlists?.resolver(playlist.id) ?: { null }
        val all = entries.withIndex().toList()
        val playable = if (isOnline()) all else all.filter { local(it.value.track.id) != null }
        if (all.isNotEmpty() && playable.isEmpty()) return NOTHING_DOWNLOADED
        val forced = start?.let { place ->
            playable.indexOfFirst { it.index == place }.takeIf { it >= 0 } ?: return SONG_NOT_DOWNLOADED
        }
        lastError = null
        loadJob?.cancel()
        if (entries.isEmpty()) {
            fail("This playlist has no songs.")
            return null
        }
        if (shuffled) changeShuffle(true)
        if (openNowPlaying) isNowPlayingOpen = true
        val (planned, startIndex) = planQueue(playable, forcedStart = forced, shuffle = shuffle, random = random)
        val cap = if (isExpensiveNetwork()) settings.mobileDataBitrate else null
        val queued = planned.mapIndexed { i, p ->
            val pt = PlaybackTrack.from(p.value, queueIndex = i)
            local(p.value.track.id)?.let { localEntry(pt, it, p.value.track) } ?: streamEntry(p.value.track, pt, cap)
        }
        loadQueue(queued, playlist.id, albumIds = emptySet(), startIndex, places = planned.map { it.index })
        return null
    }

    /**
     * Whether `album` (or one of its discs) is what's playing: the queue
     * came from it ([queueSourceId]). A playlist's queue with one of its
     * songs in doesn't count.
     */
    fun isPlaying(album: JellyfinAlbum): Boolean =
        currentTrack != null && queueSourceId != null &&
            (album.id in playingAlbumIds || album.allDiscs.any { it.id in playingAlbumIds })

    /** Whether the queue came from `playlist`. */
    fun isPlaying(playlist: JellyfinPlaylist): Boolean = currentTrack != null && queueSourceId == playlist.id

    private fun albumIds(album: JellyfinAlbum): Set<String> = (album.allDiscs.map { it.id } + album.id).toSet()

    private fun discIds(album: JellyfinAlbum): List<String> = album.allDiscs.map { it.id }

    /** Every disc of `album`, downloaded, in disc order; null if any isn't. */
    private fun downloadedDiscs(album: JellyfinAlbum): List<DownloadedAlbum>? {
        val manager = downloads() ?: return null
        if (!manager.isDownloaded(album)) return null
        return album.allDiscs.map { manager.downloadedAlbum(it.id) ?: return null }
    }

    /**
     * Plays downloaded discs from their files: titles, lengths, gains and
     * quality from the saved manifest, each track's own picture or the
     * album cover. Downloads always play in order from track 1, as on iOS:
     * shuffle and the tapped track are ignored.
     */
    private fun playLocal(discs: List<DownloadedAlbum>, album: JellyfinAlbum) {
        val tracks = discs.flatMap { disc -> disc.tracks.map { disc to it } }
        if (tracks.isEmpty()) return fail("This album has no tracks.")
        val entries = tracks.map { (disc, track) -> localEntry(disc, track, album) }
        val cast = engine.cast
        if (cast == null || !isOnline()) {
            // Already in order, so there's nothing for Maniac Mode to restore.
            loadQueue(entries, album.id, albumIds(album), startIndex = 0, discIds = discIds(album), places = emptyList())
            return
        }
        // A Cast device streams from the server, in a format it plays: ask the
        // server what each file is. While casting, before playing (a few
        // seconds at most); otherwise alongside, for when casting starts.
        if (cast.deviceName.value != null) {
            isLoading = true
            loadJob = scope.launch {
                // On the clock, not the caller's (its own requests have a longer timeout).
                val urls = withContext(Dispatchers.Default) { withTimeoutOrNull(CAST_LOOKUP_TIMEOUT_MS) { castUrls(discs) } }.orEmpty()
                val better = entries.map { (pt, item) -> pt to (urls[item.id]?.let { item.copy(castUrl = it) } ?: item) }
                loadQueue(better, album.id, albumIds(album), startIndex = 0, discIds = discIds(album), places = emptyList())
            }
        } else {
            loadQueue(entries, album.id, albumIds(album), startIndex = 0, discIds = discIds(album), places = emptyList())
            castLookupJob = scope.launch {
                val urls = castUrls(discs)
                if (urls.isEmpty()) return@launch
                engineItems = engineItems.map { item -> urls[item.id]?.let { item.copy(castUrl = it) } ?: item }
                engine.updateCastUrls(urls)
            }
        }
    }

    /** A downloaded album's tracks as the server has them, as Cast streams by track ID; empty when the server can't be reached. */
    private suspend fun castUrls(discs: List<DownloadedAlbum>): Map<String, String> = buildMap {
        for (disc in discs) {
            val tracks = try {
                api.tracks(disc.albumId)
            } catch (e: JellyfinError) {
                continue
            }
            tracks.forEach { put(it.id, StreamUrls.castUrl(api, it)) }
        }
    }

    private fun localEntry(disc: DownloadedAlbum, track: ManifestTrack, album: JellyfinAlbum): Pair<PlaybackTrack, EngineItem> {
        // The track's own picture, else the cover; neither saved: the server's, as when streaming.
        val art = listOf(disc.artPath(track), disc.coverPath).firstOrNull { LocalFiles.exists(it) }
        val pt = PlaybackTrack(
            id = track.trackId,
            trackNumber = track.number,
            title = track.title,
            artist = album.artistName.ifEmpty { disc.artist },
            albumArtistId = album.artistId.takeIf { album.artistName.isNotEmpty() },
            album = album.displayName,
            albumId = album.id,
            durationSeconds = track.durationSeconds,
            normalizationGain = track.normalizationGain,
            qualityLabel = track.qualityLabel,
            artItemId = if (track.hasOwnArt) track.trackId else album.id,
            localArtPath = art,
        ).withFileInfo(disc.trackPath(track))
        val item = EngineItem(
            id = pt.id,
            url = fileUri(disc.trackPath(track)),
            title = pt.title,
            artist = pt.artist,
            album = pt.album,
            artworkUrl = art?.let(::fileUri) ?: api.imageUrl(pt.artItemId, 600),
            durationMillis = (pt.durationSeconds * 1000).toLong(),
            // The receiver can't reach this device's files: the server's stream (format unknown here yet).
            castUrl = castable { StreamUrls.castFallbackUrl(api, pt.id) },
            castArtworkUrl = castable { StreamUrls.castArtworkUrl(api, pt.artItemId) },
        )
        return pt to item
    }

    /** `make()` where there's Google Cast, else null. */
    private inline fun castable(make: () -> String): String? = if (engine.cast != null) make() else null

    /** A playlist's song played from a file on this device (its own download or a downloaded album's). */
    private fun localEntry(described: PlaybackTrack, file: LocalPlaylistTrack, track: JellyfinTrack): Pair<PlaybackTrack, EngineItem> {
        val pt = described.copy(
            qualityLabel = file.qualityLabel ?: described.qualityLabel,
            normalizationGain = file.normalizationGain ?: described.normalizationGain,
            localArtPath = file.artPath,
        ).withFileInfo(file.path)
        val item = EngineItem(
            id = pt.id,
            url = fileUri(file.path),
            title = pt.title,
            artist = pt.artist,
            album = pt.album,
            artworkUrl = file.artPath?.let(::fileUri) ?: api.imageUrl(pt.artItemId, 600),
            durationMillis = (pt.durationSeconds * 1000).toLong(),
            castUrl = castable { StreamUrls.castUrl(api, track) },
            castArtworkUrl = castable { StreamUrls.castArtworkUrl(api, pt.artItemId) },
        )
        return pt to item
    }

    /** Streams: the server's file, or AAC at the cap on mobile data. */
    private fun streamEntries(tracks: List<JellyfinTrack>, describe: (Int, JellyfinTrack) -> PlaybackTrack): List<Pair<PlaybackTrack, EngineItem>> {
        val cap = if (isExpensiveNetwork()) settings.mobileDataBitrate else null
        return tracks.mapIndexed { i, track -> streamEntry(track, describe(i, track), cap) }
    }

    private fun streamEntry(track: JellyfinTrack, described: PlaybackTrack, cap: Int?): Pair<PlaybackTrack, EngineItem> {
        // On mobile data the stream is AAC at the cap, whatever the file is.
        // The sample rate is the file's; AAC has no bit depth.
        var pt = if (cap != null) described.copy(qualityLabel = "AAC ${cap / 1000} kbps", bitDepth = null) else described
        val item = engineItem(track, pt, cap)
        // A file the server converts (e.g. ALAC, which Android can't decode) says what's streamed.
        if (cap == null && item.seeksByRestart) {
            pt = pt.copy(qualityLabel = PlaybackTrack.convertedLabel(AudioFormat.source(track), track.mediaSource?.bitrate))
        }
        return pt to item
    }

    /** `places`: each entry's place in album or playlist order; empty when the queue is already in order for good. */
    private fun loadQueue(
        entries: List<Pair<PlaybackTrack, EngineItem>>,
        sourceId: String,
        albumIds: Set<String>,
        startIndex: Int,
        places: List<Int>,
        discIds: List<String> = emptyList(),
    ) {
        castLookupJob?.cancel()
        reportStopped(atEnd = false)
        originalPlaces = places
        queueSourceId = sourceId
        playingAlbumIds = albumIds
        playingDiscIds = discIds
        val items = entries.map { it.second }
        engineItems = items
        queue = entries.map { it.first }
        currentIndex = startIndex.coerceIn(0, queue.lastIndex)
        duration = currentTrack?.durationSeconds ?: 0.0
        position = 0.0
        engine.setRepeatMode(repeatMode)
        engine.setQueue(items, currentIndex)
        applyNormalization()
        engine.play()
        isLoading = false
        reportStarted()
        startTicking()
    }

    private fun engineItem(track: JellyfinTrack, pt: PlaybackTrack, cap: Int?) = EngineItem(
        id = track.id,
        url = StreamUrls.url(api, track, engine.directPlayFormats, maxBitrate = cap),
        title = pt.title,
        artist = pt.artist,
        album = pt.album,
        artworkUrl = api.imageUrl(pt.artItemId, 600),
        durationMillis = (pt.durationSeconds * 1000).toLong(),
        castUrl = castable { StreamUrls.castUrl(api, track) },
        castArtworkUrl = castable { StreamUrls.castArtworkUrl(api, pt.artItemId) },
    )

    private fun fail(message: String) {
        lastError = message
        isLoading = false
        isPlaying = false
    }

    // Controls

    fun pause() = engine.pause()

    fun resume() {
        if (currentTrack == null) return
        // After a failed track, Play tries it again (the engine reloads it); that's a new play.
        if (trackFailed) {
            trackFailed = false
            lastError = null
            reportedTrack = null
        }
        // After the album finished and went back to the start, this play is a new one.
        if (reportedTrack == null) reportStarted()
        engine.play()
    }

    fun togglePlayPause() = if (isPlaying) pause() else resume()

    fun skipToNext() {
        if (maniacMode || queue.isEmpty()) return
        when {
            currentIndex + 1 < queue.size -> jump(currentIndex + 1)
            repeatMode == RepeatMode.ALL -> jump(0)
        }
    }

    /** Back to the start of this track after 3 s, else the previous track. */
    fun skipToPrevious() {
        if (maniacMode || queue.isEmpty()) return
        if (position > 3) {
            seek(0.0)
        } else if (currentIndex > 0) {
            jump(currentIndex - 1)
        }
    }

    /** Plays queue entry `index` (tapped in Up next). */
    fun skipTo(index: Int) {
        if (!maniacMode) jump(index)
    }

    private fun jump(index: Int) {
        if (index !in queue.indices) return
        engine.skipTo(index)
        engine.play()
        // The engine reports the change (onIndexChanged) and the report follows from there.
    }

    fun seek(seconds: Double) {
        if (maniacMode || currentTrack == null) return
        val clamped = seconds.coerceIn(0.0, if (duration > 0) duration else seconds)
        engine.seekTo((clamped * 1000).toLong())
        position = clamped
    }

    /** Seek by fraction 0…1 of the track. */
    fun seekToProgress(fraction: Double) {
        if (duration > 0) seek(fraction * duration)
    }

    fun changeRepeatMode(mode: RepeatMode) {
        if (maniacMode && mode != RepeatMode.OFF) return
        repeatMode = mode
        engine.setRepeatMode(mode)
    }

    /** Off → All → One → Off. */
    fun cycleRepeatMode() {
        if (maniacMode) return
        changeRepeatMode(
            when (repeatMode) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            },
        )
    }

    fun toggleShuffle() = changeShuffle(!shuffle)

    /** Read at the next play (the queue isn't reshuffled), as on iOS. */
    fun changeShuffle(on: Boolean) {
        if (maniacMode && on) return
        shuffle = on
    }

    /**
     * Turns Maniac Mode on or off and saves it. On: shuffle and repeat go
     * off, and the rest of a shuffled album goes back in album order (the
     * playing track carries on).
     */
    fun changeManiacMode(on: Boolean) {
        settings.maniacMode = on
        if (on == maniacMode) return
        maniacMode = on
        if (on) {
            changeRepeatMode(RepeatMode.OFF)
            if (shuffle) {
                shuffle = false
                restoreAlbumOrder()
            }
        }
        engine.setSkipControlsEnabled(!on)
    }

    private fun restoreAlbumOrder() {
        if (originalPlaces.size != queue.size || engineItems.size != queue.size || currentIndex !in queue.indices) return
        val order = albumOrderAfterCurrent(originalPlaces, currentIndex)
        if (order == queue.indices.toList()) return
        queue = order.map { queue[it] }
        engineItems = order.map { engineItems[it] }
        originalPlaces = order.map { originalPlaces[it] }
        engine.replaceAfterCurrent(engineItems.drop(currentIndex + 1))
    }

    /** Sets the volume from the track's gain when "Normalise volume" is on (call after changing the setting). */
    fun applyNormalization() {
        val gain = if (settings.normalizeVolume) currentTrack?.normalizationGain else null
        engine.setVolume(PlaybackSettings.volumeForGain(gain))
    }

    /** Stops and clears the queue (sign out). */
    fun stop() {
        reportStopped(atEnd = false)
        loadJob?.cancel()
        castLookupJob?.cancel()
        tickJob?.cancel()
        engine.stop()
        queue = emptyList()
        engineItems = emptyList()
        originalPlaces = emptyList()
        queueSourceId = null
        playingAlbumIds = emptySet()
        playingDiscIds = emptyList()
        currentIndex = 0
        isPlaying = false
        position = 0.0
        duration = 0.0
        isNowPlayingOpen = false
    }

    // Up next and times

    /** What plays after this track, with queue indices. Wraps with Repeat All. */
    val upNext: List<Pair<Int, PlaybackTrack>>
        get() = upNextIndices(queue.size, currentIndex, repeatMode).map { it to queue[it] }

    /** The whole queue's length, in seconds. */
    val albumLength: Double get() = queue.sumOf { it.durationSeconds }

    /** Time left: the rest of this track and every track after it. */
    val albumRemaining: Double
        get() = maxOf(0.0, duration - position) + queue.drop(currentIndex + 1).sumOf { it.durationSeconds }

    // From the engine

    override fun onIndexChanged(index: Int, automatic: Boolean) {
        if (index !in queue.indices) return
        if (rewinding) {
            // Back to the start after the album ended: nothing is playing, nothing to report.
            rewinding = false
            return
        }
        reportStopped(atEnd = automatic)
        currentIndex = index
        duration = queue[index].durationSeconds
        position = 0.0
        applyNormalization()
        reportStarted()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        this.isPlaying = isPlaying
        if (isPlaying) startTicking()
    }

    /** Set while going back to track 1 after the album ended, so that move isn't reported as a play. */
    private var rewinding = false

    /**
     * The last track ended (repeat off): close Now Playing and go back to the
     * queue's first track at 0:00, paused, so the bar's progress line is at
     * zero and Play starts the album again. Nothing is reported until Play.
     */
    override fun onEnded() {
        reportStopped(atEnd = true)
        isPlaying = false
        isNowPlayingOpen = false
        if (queue.isEmpty()) return
        engine.pause()
        // The engine only reports a change of track; on a one-track album it stays put.
        rewinding = currentIndex != 0
        engine.skipTo(0)
        currentIndex = 0
        duration = queue[0].durationSeconds
        position = 0.0
        applyNormalization()
    }

    /** Whether the playing track failed (Play then retries it). */
    var trackFailed by mutableStateOf(false)
        private set

    /** The playing track failed: stop, end its server report and say what to do. */
    override fun onError(message: String) {
        reportStopped(atEnd = false)
        engine.pause()
        isPlaying = false
        trackFailed = currentTrack != null
        lastError = currentTrack?.let {
            "Couldn't play \u201C${it.title}\u201D. Check the connection to your server, then press Play to try again."
        } ?: message
    }

    // Play reporting

    private fun reportStarted() {
        val track = currentTrack ?: return
        reportedTrack = track
        if (!isOnline()) return
        scope.launch { runCatching { api.reportPlayback(track.id, 0.0, stopped = false) } }
    }

    /** `atEnd` reports the whole length so the server counts the play. */
    private fun reportStopped(atEnd: Boolean) {
        val track = reportedTrack ?: return
        reportedTrack = null
        val seconds = if (atEnd) maxOf(track.durationSeconds, position) else position
        if (!isOnline()) return
        scope.launch { runCatching { api.reportPlayback(track.id, seconds, stopped = true) } }
    }

    // Position

    private fun startTicking() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                position = engine.positionMillis / 1000.0
                if (duration <= 0) engine.durationMillis.takeIf { it > 0 }?.let { duration = it / 1000.0 }
                if (!isPlaying && !isLoading) break
                delay(500)
            }
        }
    }

    companion object {
        /** Offline, the tapped song of a playlist isn't on this device. */
        const val SONG_NOT_DOWNLOADED = "This song isn't downloaded."

        /** Offline, none of the playlist's songs are on this device. */
        const val NOTHING_DOWNLOADED = "None of this playlist's songs are downloaded."

        /** How long a downloaded album waits, while casting, for the server to say what its files are. */
        private const val CAST_LOOKUP_TIMEOUT_MS = 8_000L
    }
}

/** A local file as a `file://` URI (download file names keep to letters, digits, spaces and `-_.`). */
internal fun fileUri(path: String): String = "file://$path"

/**
 * A downloaded track's sample rate and bit depth from its file (downloads
 * don't save them, and the file may have been converted, e.g. ALAC to FLAC),
 * as iOS does. Unreadable: none, and Now Playing shows the format and bitrate.
 */
private fun PlaybackTrack.withFileInfo(path: String): PlaybackTrack {
    val info = audioFileInfo(path)
    return copy(sampleRate = info.sampleRate, bitDepth = info.bitDepth)
}
