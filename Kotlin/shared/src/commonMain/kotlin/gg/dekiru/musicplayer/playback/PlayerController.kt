package gg.dekiru.musicplayer.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.data.api.JellyfinError
import gg.dekiru.musicplayer.data.models.JellyfinAlbum
import gg.dekiru.musicplayer.data.models.JellyfinArtist
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/** A short message after Play Next / Add to Queue ("Playing next"); [id] tells two of the same apart. */
data class QueueNotice(val message: String, val isError: Boolean = false, val id: Long = 0)

/**
 * The player as the screens see it (as `PlayerManager` on iOS): the
 * listening queue ([playQueue]), current track, play/pause, skip, seek,
 * shuffle, repeat, Maniac Mode, Up next, play reporting, the mobile-data
 * cap and volume normalisation. The platform [engine] does the playing;
 * this keeps the state, feeds the engine from the queue and reports plays
 * to the server.
 *
 * The queue holds albums, playlists and single tracks one after another
 * ([PlayQueue]). Play replaces it; Play Next and Add to Queue add to it.
 * The engine is given the queue's entries in play order (those it can play:
 * offline, the downloaded ones), so it joins tracks gaplessly across albums;
 * an edit changes what's around the playing track without stopping it
 * ([PlayerEngine.replaceAroundCurrent]). The engine's items are mapped to
 * queue entries by [engineOrder]; entries are known by their entry ID,
 * never by track ID: a song may be queued twice. A downloaded album plays
 * from its files, online or not.
 *
 * The queue is saved as it changes ([queueSaver]) and restored paused at
 * launch ([restore]).
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
    private val random: Random = Random,
    /** Saves the queue between runs (debounced); null: it isn't saved. */
    private val queueSaver: PlayQueueSaver? = null,
    /** A clock in ms, for how long a track has been starting. */
    private val clock: () -> Long = { gg.dekiru.musicplayer.nowMillis() },
) : EngineListener {

    /** What one entry plays: the track as shown, the engine's item, what the queue keeps, and its place in its album or playlist (-1: unknown). */
    private class Media(val track: PlaybackTrack, val item: EngineItem, val queueTrack: QueueTrack, val place: Int)

    /** An album's own ID and its discs', for "is this album playing" and the widget. */
    private class AlbumIds(val all: Set<String>, val discs: List<String>)

    /** The listening queue: what plays, in what order, shuffle and repeat. Published whole on every change. */
    var playQueue by mutableStateOf(PlayQueue())
        private set

    /** Each entry's track as Now Playing shows it (quality label, local art), by entry ID. */
    private val shown = mutableStateMapOf<String, PlaybackTrack>()

    /** Each entry's engine item, by entry ID. */
    private val engineItems = mutableMapOf<String, EngineItem>()

    /** Each entry's place in its album or playlist (from 0), by entry ID. */
    private val places = mutableMapOf<String, Int>()

    /** Queued albums' IDs, by the album's queue group ID. */
    private val albumIds = mutableMapOf<String, AlbumIds>()

    /** The entry IDs the engine holds, in its order (the queue's play order, less what can't play): engine index ↔ entry. */
    private var engineOrder: List<String> = emptyList()

    /** The queue's tracks in play order. */
    val queue: List<PlaybackTrack> get() = playQueue.entriesInPlayOrder.map(::track)

    /** The playing entry's place in [queue]. */
    val currentIndex: Int get() = playQueue.currentPlayIndex.coerceAtLeast(0)

    val currentTrack: PlaybackTrack? get() = playQueue.currentEntry?.let(::track)

    /** An entry's track as Now Playing shows it. */
    fun track(entry: QueueEntry): PlaybackTrack = shown[entry.id] ?: entry.track.toPlaybackTrack()

    var isPlaying by mutableStateOf(false)
        private set

    /**
     * The playing track is loading after Play, a skip, a seek or a
     * reconnect and no sound has come yet (the engine's buffering). The
     * Play/Pause buttons show a spinner and [togglePlayPause] does nothing,
     * so a press while waiting can't pause what's about to start.
     */
    var isStarting by mutableStateOf(false)
        private set

    /** The start is a dropped stream reconnecting after it had played: presses pause. */
    private var startIsReconnect = false

    /** When the user last asked for sound: Play, a skip or a seek (a stall long after isn't a start). */
    private var startedAt = 0L

    /**
     * Whether a Play/Pause press is ignored now: only in the first
     * [IGNORE_PRESSES_MILLIS] of a track's start (not a reconnect), so an
     * impatient press can't pause what's about to play, while one later
     * still stops a start that's going nowhere.
     */
    val ignoresPlayPause: Boolean
        get() = isStarting && !startIsReconnect && clock() - startedAt < IGNORE_PRESSES_MILLIS

    private fun markStart() {
        startedAt = clock()
    }

    /** Seconds into the current track (updated twice a second while playing). */
    var position by mutableStateOf(0.0)
        private set
    var duration by mutableStateOf(0.0)
        private set
    val repeatMode: RepeatMode get() = playQueue.repeatMode
    val shuffle: Boolean get() = playQueue.shuffle
    var maniacMode by mutableStateOf(settings.maniacMode)
        private set
    var isLoading by mutableStateOf(false)
        private set
    var lastError by mutableStateOf<String?>(null)
        private set

    /** What Play Next / Add to Queue did, for a moment's banner; null: nothing to say. */
    var queueNotice by mutableStateOf<QueueNotice?>(null)
        private set

    /** Whether the full Now Playing screen is open (Play on an album opens it). */
    var isNowPlayingOpen by mutableStateOf(false)

    val isSupported: Boolean get() = engine.isSupported

    /** The album or playlist the playing entry was queued from (its ID); null when nothing is loaded. */
    val queueSourceId: String? get() = playQueue.currentEntry?.group?.id

    /**
     * The playing entry's place in its album or playlist (from 0), whatever
     * the queue's order; null when nothing is playing. Tells a playlist's
     * two rows of the same song apart.
     */
    val currentPlace: Int?
        get() {
            val entry = playQueue.currentEntry ?: return null
            return places[entry.id] ?: playQueue.entries.filter { it.batchId == entry.batchId }.indexOf(entry)
        }

    /**
     * The playing album's discs, in order (one for a single-disc album);
     * empty when the playing entry isn't from an album. The home-screen
     * widget keeps them to play the album again after the app was closed.
     */
    val playingDiscIds: List<String>
        get() {
            val group = playQueue.currentEntry?.group?.takeIf { it.kind == QueueGroupKind.ALBUM } ?: return emptyList()
            return albumIds[group.id]?.discs ?: listOf(group.id)
        }

    /** The streamed track with an open server report. */
    private var reportedTrack: PlaybackTrack? = null

    private var loadJob: Job? = null

    /** Asking the server for a downloaded album's Cast streams (see [playLocal]). */
    private var castLookupJob: Job? = null
    private var tickJob: Job? = null

    /** Set once stopped (signed out, or replaced by a new sign-in): nothing more is saved. */
    private var stopped = false

    init {
        engine.listener = this
        engine.setAuthorization(api.authorizationHeader())
        engine.setSkipControlsEnabled(!maniacMode)
    }

    // Playing

    /**
     * Plays an album, every disc in order for a multi-disc album, from its
     * first track or from `startTrackId`: the queue becomes this album.
     * Opens Now Playing. When the album (every disc of it) is downloaded, it
     * plays from the files, from track 1.
     */
    fun play(album: JellyfinAlbum, startTrackId: String? = null, openNowPlaying: Boolean = true) {
        if (!engine.isSupported) return
        // A new Play starts clean: adds (and an earlier Play) still waiting for their tracks are
        // dropped. It takes its own turn, so adds tapped after it land in the new queue.
        addTurns.cancelAll()
        val turn = addTurns.take()
        if (openNowPlaying) isNowPlayingOpen = true
        // Maniac Mode always starts at track 1.
        val start = if (maniacMode) null else startTrackId
        lastError = null
        loadJob?.cancel()
        downloadedDiscs(album)?.let { discs ->
            addTurns.finish(turn) { playLocal(discs, album) }
            return
        }
        isLoading = true
        loadJob = scope.launch {
            try {
                val tracks = fetchAlbum(album).getOrElse {
                    if (!turn.isCancelled) fail(it.message ?: "Couldn't load the album.")
                    return@launch
                }
                if (tracks.isEmpty()) {
                    if (!turn.isCancelled) fail("This album has no tracks.")
                    return@launch
                }
                val startIndex = start?.let { id -> tracks.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
                val media = streamMedia(tracks, album)
                addTurns.finish(turn) { startQueue(QueueGroup.album(album), media, startIndex, idsOf(album)) }
            } finally {
                addTurns.drop(turn)
            }
        }
    }

    /**
     * Plays a playlist's loaded entries from the first, or from the entry at
     * position `startingAt` (a position, not a track ID: a song may be there
     * twice): the queue becomes this playlist. Shuffle, Maniac Mode and the
     * rest as for an album.
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
        val media = playlistMedia(playlist, entries)
        if (entries.isNotEmpty() && media.isEmpty()) return NOTHING_DOWNLOADED
        val forced = start?.let { place ->
            media.indexOfFirst { it.place == place }.takeIf { it >= 0 } ?: return SONG_NOT_DOWNLOADED
        }
        lastError = null
        loadJob?.cancel()
        // A new Play starts clean: adds (and an earlier Play) still waiting for their tracks are dropped.
        addTurns.cancelAll()
        if (entries.isEmpty()) {
            fail("This playlist has no songs.")
            return null
        }
        if (shuffled) changeShuffle(true)
        if (openNowPlaying) isNowPlayingOpen = true
        addTurns.finish(addTurns.take()) { startQueue(QueueGroup.playlist(playlist), media, forced, null) }
        return null
    }

    /**
     * Plays an artist's `albums` (as their page shows them), oldest release first, each in disc and track
     * order, up to [ArtistTracks.CAP] tracks: the queue becomes these tracks, from the first. `shuffled`
     * turns shuffle on first (the Shuffle button; refused in Maniac Mode, which plays in order). Shuffle
     * otherwise stays as it is, as for an album. Downloaded albums play from their files; offline, only
     * those play. Opens Now Playing; a failure shows as the player's error.
     */
    fun play(artist: JellyfinArtist, albums: List<JellyfinAlbum>, shuffled: Boolean = false, openNowPlaying: Boolean = true) {
        if (!engine.isSupported) return
        addTurns.cancelAll()
        val turn = addTurns.take()
        if (openNowPlaying) isNowPlayingOpen = true
        lastError = null
        loadJob?.cancel()
        isLoading = true
        loadJob = scope.launch {
            try {
                val (media, capped) = artistMedia(albums).getOrElse {
                    if (!turn.isCancelled) fail(it.message ?: ARTIST_NOT_LOADED)
                    return@launch
                }
                if (media.isEmpty()) {
                    if (!turn.isCancelled) fail(if (isOnline()) "This artist has no tracks." else ARTIST_NOT_DOWNLOADED)
                    return@launch
                }
                addTurns.finish(turn) {
                    if (shuffled) changeShuffle(true)
                    startQueue(QueueGroup.artist(artist), media, null, null)
                    if (capped) say(ArtistTracks.cappedMessage())
                }
            } finally {
                addTurns.drop(turn)
            }
        }
    }

    /** Queues an artist's tracks (as [play]) after the playing album, or at the end. */
    fun playNext(artist: JellyfinArtist, albums: List<JellyfinAlbum>) = enqueue(artist, albums, next = true)

    fun addToQueue(artist: JellyfinArtist, albums: List<JellyfinAlbum>) = enqueue(artist, albums, next = false)

    private fun enqueue(artist: JellyfinArtist, albums: List<JellyfinAlbum>, next: Boolean) {
        if (!engine.isSupported || refusesPlayNext(next)) return
        val turn = addTurns.take()
        launchInTurn(turn) {
            val (media, capped) = artistMedia(albums).getOrElse {
                if (!turn.isCancelled) say(it.message ?: ARTIST_NOT_LOADED, isError = true)
                return@launchInTurn
            }
            if (turn.isCancelled) return@launchInTurn
            if (media.isEmpty()) return@launchInTurn say(if (isOnline()) "This artist has no tracks." else ARTIST_NOT_DOWNLOADED, isError = true)
            addTurns.finish(turn) {
                add(next, QueueGroup.artist(artist), media, null)
                if (capped) say(ArtistTracks.cappedMessage())
            }
        }
    }

    /** Whether `album` (or one of its discs) is what the playing entry was queued from. A playlist with one of its songs doesn't count. */
    fun isPlaying(album: JellyfinAlbum): Boolean {
        if (currentTrack == null) return false
        val group = playQueue.currentEntry?.group?.takeIf { it.kind == QueueGroupKind.ALBUM } ?: return false
        val playing = albumIds[group.id]?.all ?: setOf(group.id)
        return album.id in playing || album.allDiscs.any { it.id in playing }
    }

    /** Whether the playing entry was queued from `playlist`. */
    fun isPlaying(playlist: JellyfinPlaylist): Boolean {
        val group = playQueue.currentEntry?.group ?: return false
        return currentTrack != null && group.kind == QueueGroupKind.PLAYLIST && group.id == playlist.id
    }

    // Play Next and Add to Queue

    /** Queues `album` (every disc) after the playing album (offline: its downloaded discs). Confirming first is the caller's. */
    fun playNext(album: JellyfinAlbum) = enqueue(album, next = true)

    /** Queues `album` (every disc) at the end of the queue (offline: its downloaded discs). */
    fun addToQueue(album: JellyfinAlbum) = enqueue(album, next = false)

    /** Queues a playlist's loaded entries after the playing album (offline: the songs on this device). */
    fun playNext(playlist: JellyfinPlaylist, entries: List<PlaylistEntry>) = enqueue(playlist, entries, next = true)

    /** Queues a playlist's loaded entries at the end of the queue (offline: the songs on this device). */
    fun addToQueue(playlist: JellyfinPlaylist, entries: List<PlaylistEntry>) = enqueue(playlist, entries, next = false)

    /** Queues some of `album`'s tracks (a song's own menu) after the playing album (offline: the ones on this device). */
    fun playNext(album: JellyfinAlbum, tracks: List<JellyfinTrack>) = enqueue(album, tracks, next = true)

    /** Queues some of `album`'s tracks at the end of the queue (offline: the ones on this device). */
    fun addToQueue(album: JellyfinAlbum, tracks: List<JellyfinTrack>) = enqueue(album, tracks, next = false)

    /** Queues a playlist (a card's menu: its songs are fetched; offline, its download's) after the playing album. */
    fun playNext(playlist: JellyfinPlaylist) = enqueue(playlist, next = true)

    /** Queues a playlist (a card's menu) at the end of the queue. */
    fun addToQueue(playlist: JellyfinPlaylist) = enqueue(playlist, next = false)

    /**
     * Whether Play on an album or playlist should ask first (Replace queue,
     * Play Next, Add to Queue): something from another album or playlist is
     * queued after the playing one ([PlayQueue.asksBeforeReplacing]).
     */
    val asksBeforePlay: Boolean get() = playQueue.asksBeforeReplacing

    /** Adds land in the order they were tapped ([AddTurns]). */
    private val addTurns = AddTurns()

    /** Fetches for `turn`; ending without resolving it (failed, cancelled) drops the turn. */
    private fun launchInTurn(turn: AddTurns.Turn, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } finally {
                addTurns.drop(turn)
            }
        }
    }

    /** Adds `media` now, after any adds still waiting for their tracks. */
    private fun addInTurn(next: Boolean, group: QueueGroup, media: List<Media>, ids: AlbumIds?, after: () -> Unit = {}) =
        addTurns.finish(addTurns.take()) {
            add(next, group, media, ids)
            after()
        }

    private fun enqueue(playlist: JellyfinPlaylist, next: Boolean) {
        if (!engine.isSupported || refusesPlayNext(next)) return
        if (!isOnline()) {
            val saved = downloads()?.playlists?.saved(playlist.id)?.takeIf { it.isAvailableOffline }
                ?: return say(NOTHING_DOWNLOADED, isError = true)
            return enqueue(playlist, saved.manifest.toEntries(), next)
        }
        val turn = addTurns.take()
        launchInTurn(turn) {
            val entries = try {
                api.playlistTracks(playlist.id)
            } catch (e: JellyfinError) {
                if (!turn.isCancelled) say(PLAYLIST_NOT_LOADED, isError = true)
                return@launchInTurn
            }
            if (turn.isCancelled) return@launchInTurn
            if (entries.isEmpty()) return@launchInTurn say(PLAYLIST_EMPTY, isError = true)
            val media = playlistMedia(playlist, entries)
            if (media.isEmpty()) return@launchInTurn say(NOTHING_DOWNLOADED, isError = true)
            addTurns.finish(turn) { add(next, QueueGroup.playlist(playlist), media, null) }
        }
    }

    private fun enqueue(album: JellyfinAlbum, next: Boolean) {
        if (!engine.isSupported || refusesPlayNext(next)) return
        val group = QueueGroup.album(album)
        downloadedDiscs(album)?.let { discs ->
            addInTurn(next, group, localMedia(discs, album), idsOf(album)) { lookUpCastUrls(discs) }
            return
        }
        if (!isOnline()) {
            val manager = downloads()
            val discs = album.allDiscs.mapNotNull { manager?.downloadedAlbum(it.id) }
            if (discs.isEmpty()) return say(ALBUM_NOT_QUEUED, isError = true)
            addInTurn(next, group, localMedia(discs, album), idsOf(album))
            return
        }
        val turn = addTurns.take()
        launchInTurn(turn) {
            val tracks = fetchAlbum(album).getOrElse {
                if (!turn.isCancelled) say(ALBUM_NOT_QUEUED, isError = true)
                return@launchInTurn
            }
            val media = streamMedia(tracks, album)
            addTurns.finish(turn) { add(next, group, media, idsOf(album)) }
        }
    }

    /**
     * A playlist's entries (all of them, or a song's own menu: those songs,
     * still as part of the playlist), each at its place in the playlist.
     */
    private fun enqueue(playlist: JellyfinPlaylist, entries: List<PlaylistEntry>, next: Boolean) {
        if (!engine.isSupported || refusesPlayNext(next)) return
        val media = playlistMedia(playlist, entries, byPosition = true)
        if (entries.isNotEmpty() && media.isEmpty()) {
            return say(if (entries.size == 1) SONG_NOT_QUEUED else NOTHING_DOWNLOADED, isError = true)
        }
        addInTurn(next, QueueGroup.playlist(playlist), media, null)
    }

    private fun enqueue(album: JellyfinAlbum, tracks: List<JellyfinTrack>, next: Boolean) {
        if (!engine.isSupported || refusesPlayNext(next)) return
        val local = downloads()?.playlists?.resolver("") ?: { null }
        val online = isOnline()
        val cap = cap()
        val media = tracks.mapIndexedNotNull { i, track ->
            val described = PlaybackTrack.from(track, album, i)
            val file = local(track.id)
            if (file == null && !online) return@mapIndexedNotNull null
            val (pt, item) = file?.let { localEntry(described, it, track) } ?: streamEntry(track, described, cap)
            Media(pt, item, QueueTrack.from(track, album, i), i)
        }
        if (tracks.isNotEmpty() && media.isEmpty()) return say(SONG_NOT_QUEUED, isError = true)
        val group = QueueGroup.tracks(media.map { it.queueTrack }) ?: return
        addInTurn(next, group, media, null)
    }

    /** Adds `media` from `group` after the playing album or at the end; an empty queue plays it (Now Playing stays as it is). */
    private fun add(next: Boolean, group: QueueGroup, media: List<Media>, ids: AlbumIds?) {
        // An album still being fetched when the player was stopped (signed out) isn't played.
        if (stopped || media.isEmpty()) return
        if (!PlayQueue.canEdit(if (next) QueueEdit.PLAY_NEXT else QueueEdit.ADD_TO_QUEUE, maniacMode)) return
        if (playQueue.currentEntry == null) {
            lastError = null
            loadJob?.cancel()
            startQueue(group, media, null, ids)
        } else {
            ids?.let { albumIds[group.id] = it }
            val q = playQueue.copy()
            val tracks = media.map { it.queueTrack }
            val added = if (next) q.playNext(group, tracks, random) else q.addToQueue(group, tracks, random)
            remember(added, media)
            publish(q)
            syncEngine()
        }
        say(if (next) PLAYING_NEXT else ADDED_TO_QUEUE)
    }

    /** Maniac Mode refuses Play Next (before anything is fetched); [add] checks again. */
    private fun refusesPlayNext(next: Boolean) = next && !PlayQueue.canEdit(QueueEdit.PLAY_NEXT, maniacMode)

    private var noticeCount = 0L

    private fun say(message: String, isError: Boolean = false) {
        queueNotice = QueueNotice(message, isError, ++noticeCount)
    }

    /** The banner went away. */
    fun dismissQueueNotice() {
        queueNotice = null
    }

    // Editing the queue

    /**
     * Moves entries (one, or a whole group's) to `toIndex` in the queue as
     * shown ([PlayQueue.move]). The playing track carries on. Refused in
     * Maniac Mode.
     */
    fun moveQueue(entryIds: Collection<String>, toIndex: Int) {
        if (!PlayQueue.canEdit(QueueEdit.MOVE, maniacMode)) return
        val q = playQueue.copy()
        if (!q.move(entryIds, toIndex)) return
        publish(q)
        syncEngine()
    }

    /**
     * Removes entries. The playing one among them: the next playable entry
     * plays from its start (playing or paused as before), or, with nothing
     * after it, playback stops at the first remaining entry. Refused in
     * Maniac Mode.
     */
    fun removeFromQueue(entryIds: Collection<String>) {
        if (!PlayQueue.canEdit(QueueEdit.REMOVE, maniacMode)) return
        val ids = entryIds.filter { playQueue.entry(it) != null }.toSet()
        if (ids.isEmpty()) return
        val q = playQueue.copy()
        val change = q.remove(ids, ::isPlayable)
        // A new entry is current: it starts from 0 (and is saved so).
        if (q.currentEntryId != playQueue.currentEntryId) position = 0.0
        publish(q)
        when (change) {
            QueueCurrentChange.UNCHANGED -> syncEngine()
            QueueCurrentChange.MOVED -> {
                val index = engineOrder.indexOf(q.currentEntryId)
                if (index >= 0) {
                    // The engine moves to it (reported as a skip), then drops what was removed.
                    engine.skipTo(index)
                    syncEngine()
                } else {
                    reportStopped(atEnd = false)
                    position = 0.0
                    loadEngine(play = isPlaying)
                }
            }
            QueueCurrentChange.ENDED -> endQueue()
        }
        forget(ids)
    }

    /** Removes everything but the playing entry (played entries too). Refused in Maniac Mode. */
    fun clearUpcoming() {
        if (!PlayQueue.canEdit(QueueEdit.CLEAR_UPCOMING, maniacMode)) return
        val current = playQueue.currentEntryId ?: return
        val gone = playQueue.entries.map { it.id }.filter { it != current }
        if (gone.isEmpty()) return
        val q = playQueue.copy()
        q.clearUpcoming()
        publish(q)
        syncEngine()
        forget(gone)
    }

    /** Plays entry `entryId` from its start (tapped in Up next). Refused in Maniac Mode, and for an entry that can't play now. */
    fun skipTo(entryId: String) {
        if (!PlayQueue.canEdit(QueueEdit.SKIP_TO, maniacMode)) return
        val entry = playQueue.entry(entryId) ?: return
        if (!isPlayable(entry)) return
        jump(entryId)
    }

    /** Plays the entry at `index` in [queue] (play order). */
    fun skipTo(index: Int) {
        playQueue.playOrder.getOrNull(index)?.let(::skipTo)
    }

    /** Nothing playable after the removed playing entry: stop, paused at the queue's first remaining entry (none: empty). */
    private fun endQueue() {
        reportStopped(atEnd = false)
        isPlaying = false
        if (playQueue.currentEntry == null) {
            clearPlayback()
            return
        }
        position = 0.0
        loadEngine(play = false)
    }

    // Building the queue

    private fun cap(): Int? = if (isExpensiveNetwork()) settings.mobileDataBitrate else null

    /** Every disc's tracks, in disc order; a failure is returned, not thrown (a failed child would cancel the caller). */
    private suspend fun fetchAlbum(album: JellyfinAlbum): Result<List<JellyfinTrack>> {
        val results = coroutineScope {
            album.allDiscs.map { disc ->
                async {
                    try {
                        Result.success(api.tracks(disc.id))
                    } catch (e: JellyfinError) {
                        Result.failure(e)
                    }
                }
            }.awaitAll()
        }
        results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { return Result.failure(it) }
        return Result.success(results.flatMap { it.getOrThrow() })
    }

    /**
     * An artist's albums as queue media: [ArtistTracks.albumOrder], from the files for a downloaded album
     * (offline only those), else streamed; at most [ArtistTracks.CAP] tracks (the flag says some were left
     * out). Albums are fetched one at a time and no more once the cap is passed.
     */
    private suspend fun artistMedia(albums: List<JellyfinAlbum>): Result<Pair<List<Media>, Boolean>> {
        val online = isOnline()
        val manager = downloads()
        val all = ArrayList<Media>()
        for (album in ArtistTracks.albumOrder(albums)) {
            if (!ArtistTracks.needsMore(all.size)) break
            val discs = downloadedDiscs(album)
            all += when {
                discs != null -> localMedia(discs, album)
                !online -> album.allDiscs.mapNotNull { manager?.downloadedAlbum(it.id) }.takeIf { it.isNotEmpty() }?.let { localMedia(it, album) } ?: continue
                else -> streamMedia(fetchAlbum(album).getOrElse { return Result.failure(it) }, album)
            }
        }
        val (kept, capped) = ArtistTracks.limit(all)
        // Each entry's place is its position in this queue (Maniac Mode's order).
        return Result.success(kept.mapIndexed { i, m -> Media(m.track, m.item, m.queueTrack, i) } to capped)
    }

    private fun idsOf(album: JellyfinAlbum) = AlbumIds((album.allDiscs.map { it.id } + album.id).toSet(), album.allDiscs.map { it.id })

    /** Streams: the server's file, or AAC at the cap on mobile data. */
    private fun streamMedia(tracks: List<JellyfinTrack>, album: JellyfinAlbum): List<Media> {
        val cap = cap()
        return tracks.mapIndexed { i, track ->
            val (pt, item) = streamEntry(track, PlaybackTrack.from(track, album, i), cap)
            Media(pt, item, QueueTrack.from(track, album, i), i)
        }
    }

    /** A playlist's songs: from this device when they're here, else streamed; offline, only those here. */
    private fun playlistMedia(playlist: JellyfinPlaylist, entries: List<PlaylistEntry>, byPosition: Boolean = false): List<Media> {
        val local = downloads()?.playlists?.resolver(playlist.id) ?: { null }
        val online = isOnline()
        val cap = cap()
        return entries.withIndex().mapNotNull { (index, entry) ->
            // `byPosition`: each entry's own place in the playlist (some of its songs queued); else its index here.
            val place = if (byPosition) entry.position else index
            val file = local(entry.track.id)
            if (file == null && !online) return@mapNotNull null
            val described = PlaybackTrack.from(entry, place)
            val (pt, item) = file?.let { localEntry(described, it, entry.track) } ?: streamEntry(entry.track, described, cap)
            Media(pt, item, QueueTrack.from(entry, place), place)
        }
    }

    private fun localMedia(discs: List<DownloadedAlbum>, album: JellyfinAlbum): List<Media> =
        discs.flatMap { disc -> disc.tracks.map { disc to it } }.mapIndexed { i, (disc, track) ->
            val (pt, item) = localEntry(disc, track, album)
            Media(pt, item, QueueTrack.from(pt, null), i)
        }

    /**
     * An entry with no media yet (a restored queue): the downloaded file
     * (the playlist's own, else a downloaded album's) when it's here, else
     * the stream, decided from the saved format as for a fresh play.
     */
    private fun mediaFor(entry: QueueEntry, resolvers: MutableMap<String, (String) -> LocalPlaylistTrack?>): Media {
        val key = entry.group.id.takeIf { entry.group.kind == QueueGroupKind.PLAYLIST }.orEmpty()
        val local = resolvers.getOrPut(key) { downloads()?.playlists?.resolver(key) ?: { null } }
        val described = entry.track.toPlaybackTrack()
        val track = entry.track.toJellyfinTrack()
        val (pt, item) = local(entry.track.id)?.let { localEntry(described, it, track) } ?: streamEntry(track, described, cap())
        return Media(pt, item, entry.track, -1)
    }

    /** Every disc of `album`, downloaded, in disc order; null if any isn't. */
    private fun downloadedDiscs(album: JellyfinAlbum): List<DownloadedAlbum>? {
        val manager = downloads() ?: return null
        if (!manager.isDownloaded(album)) return null
        return album.allDiscs.map { manager.downloadedAlbum(it.id) ?: return null }
    }

    /**
     * Plays downloaded discs from their files: titles, lengths, gains and
     * quality from the saved manifest, each track's own picture or the
     * album cover, from track 1 (the tapped track is ignored, as on iOS).
     */
    private fun playLocal(discs: List<DownloadedAlbum>, album: JellyfinAlbum) {
        val media = localMedia(discs, album)
        if (media.isEmpty()) return fail("This album has no tracks.")
        val cast = engine.cast
        if (cast == null || !isOnline()) {
            startQueue(QueueGroup.album(album), media, null, idsOf(album))
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
                val better = media.map { m -> urls[m.item.id]?.let { Media(m.track, m.item.copy(castUrl = it), m.queueTrack, m.place) } ?: m }
                startQueue(QueueGroup.album(album), better, null, idsOf(album))
            }
        } else {
            startQueue(QueueGroup.album(album), media, null, idsOf(album))
            castLookupJob = scope.launch { applyCastUrls(castUrls(discs)) }
        }
    }

    /** For downloaded discs added to the queue: their Cast streams, in the background. */
    private fun lookUpCastUrls(discs: List<DownloadedAlbum>) {
        if (engine.cast == null || !isOnline()) return
        scope.launch { applyCastUrls(castUrls(discs)) }
    }

    private fun applyCastUrls(urls: Map<String, String>) {
        if (urls.isEmpty()) return
        for ((entryId, item) in engineItems.toList()) urls[item.id]?.let { engineItems[entryId] = item.copy(castUrl = it) }
        engine.updateCastUrls(urls)
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

    /** A song played from a file on this device (a playlist's own download or a downloaded album's). */
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

    /** Keeps each new entry's media. */
    private fun remember(entries: List<QueueEntry>, media: List<Media>) {
        for ((entry, m) in entries.zip(media)) {
            shown[entry.id] = m.track
            engineItems[entry.id] = m.item.copy(entryId = entry.id)
            if (m.place >= 0) places[entry.id] = m.place
        }
    }

    private fun forget(entryIds: Collection<String>) {
        for (id in entryIds) {
            shown.remove(id)
            engineItems.remove(id)
            places.remove(id)
        }
        val queuedGroups = playQueue.entries.mapTo(HashSet()) { it.group.id }
        albumIds.keys.retainAll(queuedGroups)
    }

    private fun forgetAll() {
        shown.clear()
        engineItems.clear()
        places.clear()
        albumIds.clear()
        engineOrder = emptyList()
    }

    /** The queue becomes `media` from `group`, playing `media[startIndex]` (else the first; shuffled as shuffle says), and plays. */
    private fun startQueue(group: QueueGroup, media: List<Media>, startIndex: Int?, ids: AlbumIds?) {
        castLookupJob?.cancel()
        reportStopped(atEnd = false)
        forgetAll()
        ids?.let { albumIds[group.id] = it }
        val q = playQueue.copy()
        val entries = q.replace(group, media.map { it.queueTrack }, startIndex, random)
        remember(entries, media)
        position = 0.0
        publish(q)
        loadEngine(play = true)
    }

    private fun fail(message: String) {
        lastError = message
        isLoading = false
        isPlaying = false
    }

    // The engine

    /** Offline, only what's on this device plays (what isn't is skipped). */
    private fun isPlayable(entry: QueueEntry): Boolean =
        isOnline() || engineItems[entry.id]?.url?.startsWith("file://") == true

    /** What the engine should hold: the queue in play order, less what can't play (the playing entry always). */
    private fun wantedEngineOrder(): List<String> {
        val current = playQueue.currentEntryId
        return playQueue.entriesInPlayOrder.filter { (it.id == current || isPlayable(it)) && it.id in engineItems }.map { it.id }
    }

    /**
     * Gives the engine the queue from the playing entry (at `startMillis`),
     * and plays it, or leaves it paused without opening any stream
     * ([PlayerEngine.cue]): the first Play opens it.
     */
    private fun loadEngine(play: Boolean, startMillis: Long = 0) {
        val current = playQueue.currentEntryId ?: return
        val order = wantedEngineOrder()
        engineOrder = order
        val index = order.indexOf(current).coerceAtLeast(0)
        duration = currentTrack?.durationSeconds ?: 0.0
        engine.setRepeatMode(repeatMode)
        val items = order.map(engineItems::getValue)
        if (play) {
            engine.setQueue(items, index)
            if (startMillis > 0) engine.seekTo(startMillis)
        } else {
            engine.cue(items, index, startMillis)
        }
        applyNormalization()
        isLoading = false
        if (play) {
            markStart()
            engine.play()
            reportStarted()
            startTicking()
        }
    }

    /**
     * The queue changed round the playing entry: the engine is given what
     * now comes before and after it, without interrupting it. Only what
     * changed is sent (most edits change only what follows).
     */
    private fun syncEngine() {
        if (engineOrder.isEmpty()) return
        val current = playQueue.currentEntryId ?: return
        val wanted = wantedEngineOrder()
        val at = wanted.indexOf(current)
        val was = engineOrder.indexOf(current)
        if (at < 0 || was < 0) {
            // The engine isn't on the queue's entry (shouldn't happen): load it again there.
            loadEngine(play = isPlaying)
            return
        }
        val before = wanted.take(at)
        val after = wanted.drop(at + 1)
        if (before != engineOrder.take(was)) {
            engine.replaceAroundCurrent(before.map(engineItems::getValue), after.map(engineItems::getValue))
        } else if (after != engineOrder.drop(was + 1)) {
            engine.replaceAfterCurrent(after.map(engineItems::getValue))
        }
        engineOrder = wanted
    }

    // Controls

    fun pause() = engine.pause()

    fun resume() {
        if (currentTrack == null) return
        // A restored queue whose entry couldn't be loaded then: load it now.
        if (engineOrder.isEmpty()) {
            loadEngine(play = true, startMillis = (position * 1000).toLong())
            return
        }
        // After a failed track, Play tries it again (the engine reloads it); that's a new play.
        if (trackFailed) {
            trackFailed = false
            lastError = null
            reportedTrack = null
        }
        // After the queue finished and went back to the start, or after a restore, this play is a new one.
        if (reportedTrack == null) reportStarted()
        markStart()
        engine.play()
    }

    /**
     * Play or Pause. Nothing in the first seconds of a track's start
     * ([ignoresPlayPause]); later in a start, or while reconnecting, it
     * pauses (ending the tries; Play then resumes at the held point).
     */
    fun togglePlayPause() {
        if (ignoresPlayPause) return
        if (isPlaying || isStarting) pause() else resume()
    }

    /** The next entry ([PlayQueue.next]: Repeat All goes round the whole queue). */
    fun skipToNext() {
        if (maniacMode || playQueue.isEmpty) return
        val next = playQueue.copy().next(trackEnded = false, isPlayable = ::isPlayable) ?: return
        jump(next)
    }

    /** Back to the start of this track after 3 s, else the previous entry ([PlayQueue.previous]). */
    fun skipToPrevious() {
        if (maniacMode || playQueue.isEmpty) return
        if (position > 3) {
            seek(0.0)
            return
        }
        val previous = playQueue.copy().previous(isPlayable = ::isPlayable) ?: return
        jump(previous)
    }

    private fun jump(entryId: String) {
        val index = engineOrder.indexOf(entryId)
        if (index < 0) {
            // Not in the engine (it couldn't play when the engine was loaded): load it there.
            reportStopped(atEnd = false)
            val q = playQueue.copy()
            if (!q.skipTo(entryId)) return
            position = 0.0
            publish(q)
            loadEngine(play = true)
            return
        }
        markStart()
        engine.skipTo(index)
        engine.play()
        // The engine reports the change (onIndexChanged) and the report follows from there.
    }

    fun seek(seconds: Double) {
        if (maniacMode || currentTrack == null) return
        val clamped = seconds.coerceIn(0.0, if (duration > 0) duration else seconds)
        markStart()
        engine.seekTo((clamped * 1000).toLong())
        position = clamped
    }

    /** Seek by fraction 0…1 of the track. */
    fun seekToProgress(fraction: Double) {
        if (duration > 0) seek(fraction * duration)
    }

    /** Repeat One repeats the track; Repeat All goes round the whole queue (the engine holds all of it). Maniac Mode allows only off. */
    fun changeRepeatMode(mode: RepeatMode) {
        if (maniacMode && mode != RepeatMode.OFF) return
        val q = playQueue.copy()
        q.repeatMode = mode
        publish(q)
        engine.setRepeatMode(mode)
    }

    /** Off → All → One → Off. */
    fun cycleRepeatMode() {
        if (!PlayQueue.canEdit(QueueEdit.REPEAT_MODE, maniacMode)) return
        changeRepeatMode(
            when (repeatMode) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            },
        )
    }

    fun toggleShuffle() = changeShuffle(!shuffle)

    /**
     * Shuffles the whole queue (every album in it) now, the playing track
     * first; off puts the queue back in order, the playing track kept.
     * The playing track carries on either way. Maniac Mode refuses on.
     */
    fun changeShuffle(on: Boolean) {
        if (on && !PlayQueue.canEdit(QueueEdit.SHUFFLE, maniacMode)) return
        if (on == shuffle) return
        val q = playQueue.copy()
        q.setShuffle(on, random)
        publish(q)
        syncEngine()
    }

    /**
     * Turns Maniac Mode on or off and saves it. On: shuffle and repeat go
     * off, and the queue goes back in order (the playing track carries on).
     */
    fun changeManiacMode(on: Boolean) {
        settings.maniacMode = on
        if (on == maniacMode) return
        maniacMode = on
        if (on) {
            changeRepeatMode(RepeatMode.OFF)
            changeShuffle(false)
        }
        engine.setSkipControlsEnabled(!on)
    }

    /** Sets the volume from the track's gain when "Normalise volume" is on (call after changing the setting). */
    fun applyNormalization() {
        val gain = if (settings.normalizeVolume) currentTrack?.normalizationGain else null
        engine.setVolume(PlaybackSettings.volumeForGain(gain))
    }

    /**
     * Stops and clears the queue (sign out, or a new sign-in). Nothing more
     * is saved, a save that was waiting included: the saved file is the caller's.
     */
    fun stop() {
        stopped = true
        addTurns.cancelAll()
        queueSaver?.cancel()
        reportStopped(atEnd = false)
        loadJob?.cancel()
        castLookupJob?.cancel()
        clearPlayback()
        isNowPlayingOpen = false
    }

    private fun clearPlayback() {
        tickJob?.cancel()
        engine.stop()
        forgetAll()
        playQueue = PlayQueue(shuffle = shuffle, repeatMode = repeatMode)
        isPlaying = false
        isStarting = false
        position = 0.0
        duration = 0.0
    }

    // Up next and times

    /** What plays after this entry, in play order (Repeat All: then the earlier ones). */
    val upcomingEntries: List<QueueEntry> get() = playQueue.upcomingEntries

    /** The queue as the editor groups it: runs of one batch, in play order. */
    val displayGroups: List<QueueDisplayGroup> get() = playQueue.displayGroups

    /** What plays after this track, as (entry ID, track). Wraps with Repeat All. */
    val upNext: List<Pair<String, PlaybackTrack>>
        get() = playQueue.upcomingEntries.map { it.id to track(it) }

    /** Seconds left in the playing album (or playlist, or batch of tracks). */
    val currentGroupRemaining: Double get() = playQueue.currentGroupRemaining(position)

    /** Seconds left in the whole queue (no Repeat All going round). */
    val queueRemaining: Double get() = playQueue.queueRemaining(position)

    /** The whole queue's length, in seconds. */
    val queueLength: Double get() = playQueue.entries.sumOf { it.track.durationSeconds }

    // Saving and restoring

    private fun publish(q: PlayQueue) {
        playQueue = q
        scheduleSave()
    }

    private fun scheduleSave() {
        if (stopped) return
        queueSaver?.schedule(playQueue.snapshot(position, api.baseUrl, api.userId))
    }

    /** Writes the queue now (the app going to the background, the window closing). */
    suspend fun saveQueueNow() {
        if (stopped) return
        scheduleSave()
        queueSaver?.flush()
    }

    /**
     * Puts back a saved queue at launch, paused at the same entry and point:
     * each entry from its downloaded file when it's here, else streamed. An
     * entry that can't play (offline and not downloaded) is skipped: the
     * next that can is current, from its start. No stream is opened (the
     * network may not be up yet at launch) and nothing is reported until
     * Play, which opens the track at the saved point. Ignored once
     * something is queued.
     */
    fun restore(snapshot: PlayQueueSnapshot) {
        if (!engine.isSupported || playQueue.currentEntry != null || isLoading || stopped) return
        if (snapshot.entries.isEmpty()) return
        val q = snapshot.toQueue()
        val first = q.currentEntry ?: return
        if (maniacMode) {
            // Maniac Mode: back in order, no repeat.
            q.setShuffle(false)
            q.repeatMode = RepeatMode.OFF
        }
        forgetAll()
        val resolvers = mutableMapOf<String, (String) -> LocalPlaylistTrack?>()
        for (entry in q.entries) remember(listOf(entry), listOf(mediaFor(entry, resolvers)))
        var at = snapshot.positionSeconds.coerceAtLeast(0.0)
        if (!isPlayable(first)) {
            at = 0.0
            if (q.next(trackEnded = false, isPlayable = ::isPlayable) == null) {
                // Nothing in it plays now: nothing is restored.
                forgetAll()
                return
            }
        }
        reportedTrack = null
        isPlaying = false
        playQueue = q
        position = at
        loadEngine(play = false, startMillis = (at * 1000).toLong())
    }

    // From the engine

    override fun onIndexChanged(index: Int, automatic: Boolean) {
        val entryId = engineOrder.getOrNull(index) ?: return
        if (rewinding) {
            // Back to the start after the queue ended: nothing is playing, nothing to report.
            rewinding = false
            return
        }
        reportStopped(atEnd = automatic)
        position = 0.0
        if (entryId != playQueue.currentEntryId) {
            // Gapless into the next entry (another album, maybe), a skip, or the lock screen.
            val q = playQueue.copy()
            q.skipTo(entryId)
            publish(q)
        }
        duration = currentTrack?.durationSeconds ?: 0.0
        applyNormalization()
        reportStarted()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        this.isPlaying = isPlaying
        if (isPlaying) startTicking() else scheduleSave()
    }

    override fun onStartingChanged(starting: Boolean, reconnecting: Boolean) {
        startIsReconnect = reconnecting
        isStarting = starting
    }

    /** Set while going back to the first entry after the queue ended, so that move isn't reported as a play. */
    private var rewinding = false

    /**
     * The last entry ended (repeat off): close Now Playing and go back to the
     * queue's first entry at 0:00, paused, so the bar's progress line is at
     * zero and Play starts the queue again. Nothing is reported until Play.
     */
    override fun onEnded() {
        reportStopped(atEnd = true)
        isPlaying = false
        isNowPlayingOpen = false
        val first = engineOrder.firstOrNull() ?: return
        engine.pause()
        // The engine only reports a change of track; on a one-track queue it stays put.
        rewinding = engineOrder.indexOf(playQueue.currentEntryId) != 0
        engine.skipTo(0)
        position = 0.0
        val q = playQueue.copy()
        q.skipTo(first)
        publish(q)
        duration = currentTrack?.durationSeconds ?: 0.0
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
        isStarting = false
        trackFailed = currentTrack != null
        lastError = currentTrack?.let {
            "Couldn't play “${it.title}”. Check the connection to your server, then press Play to try again."
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
            var ticks = 0
            while (isActive) {
                position = engine.positionMillis / 1000.0
                if (duration <= 0) engine.durationMillis.takeIf { it > 0 }?.let { duration = it / 1000.0 }
                if (!isPlaying && !isLoading) break
                // The point in the track is saved every 10 s.
                if (++ticks % SAVE_EVERY_TICKS == 0) scheduleSave()
                delay(500)
            }
        }
    }

    companion object {
        /** Offline, the tapped song of a playlist isn't on this device. */
        const val SONG_NOT_DOWNLOADED = "This song isn't downloaded."

        /** Offline, none of the playlist's songs are on this device. */
        const val NOTHING_DOWNLOADED = "None of this playlist's songs are downloaded."

        /** An album couldn't be queued: its tracks couldn't be fetched, or offline it isn't downloaded. */
        const val ALBUM_NOT_QUEUED = "Couldn't add this album. Check the connection, or download it first."

        /** A song couldn't be queued (offline and not downloaded). */
        const val SONG_NOT_QUEUED = "Couldn't add this song. Check the connection, or download it first."

        /** An artist's tracks couldn't be fetched. */
        const val ARTIST_NOT_LOADED = "Couldn't load this artist's music. Check the connection, then try again."

        /** Offline, none of the artist's albums are on this device. */
        const val ARTIST_NOT_DOWNLOADED = "None of this artist's music is downloaded."

        /** A playlist card's songs couldn't be fetched. */
        const val PLAYLIST_NOT_LOADED = "Couldn't load this playlist. Check the connection, then try again."

        /** A playlist card with no songs. */
        const val PLAYLIST_EMPTY = "This playlist is empty."

        /** The confirmations. */
        const val PLAYING_NEXT = "Playing next"
        const val ADDED_TO_QUEUE = "Added to queue"

        /** How long a downloaded album waits, while casting, for the server to say what its files are. */
        private const val CAST_LOOKUP_TIMEOUT_MS = 8_000L

        /** How long into a track's start Play/Pause presses are ignored. */
        const val IGNORE_PRESSES_MILLIS = 10_000L

        /** Half-second ticks between saves of the point in the track. */
        private const val SAVE_EVERY_TICKS = 20
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
