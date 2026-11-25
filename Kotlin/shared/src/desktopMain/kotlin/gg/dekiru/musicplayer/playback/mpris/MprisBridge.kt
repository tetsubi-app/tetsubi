package gg.dekiru.musicplayer.playback.mpris

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import gg.dekiru.musicplayer.AppSession
import gg.dekiru.musicplayer.data.api.JellyfinApi
import gg.dekiru.musicplayer.playback.PlayerController
import gg.dekiru.musicplayer.playback.PlayerEngine
import gg.dekiru.musicplayer.playback.RepeatMode
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.abs

/**
 * Connects the signed-in [PlayerController] to MPRIS: the player's state goes
 * out to the desktop (media keys, the now-playing panel, `playerctl`), and
 * its commands come back to the controller on the main thread, as the app's
 * own buttons do (so Maniac Mode, the play report and a failed track's retry
 * all behave the same). The Android counterpart is the MediaSession in `Media3Engine`.
 *
 * Covers are fetched with the sign-in header into the cache folder, since
 * the desktop can't send it; a downloaded album's own cover is used as it is.
 */
class MprisBridge private constructor(
    private val session: AppSession,
    private val engine: PlayerEngine,
    private val artDir: File,
    private val onRaise: () -> Unit,
    private val onQuit: () -> Unit,
) : MprisControls, AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var service: MprisService? = null

    /** Covers on disk, by item ID: a state map, so the metadata goes out again when one arrives. */
    private val covers = mutableStateMapOf<String, String>()
    private val fetching = mutableSetOf<String>()

    // Read from D-Bus threads.
    @Volatile private var playing = false
    @Volatile private var lastPositionSeconds = 0.0

    private val player: PlayerController? get() = session.player.value

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun start(service: MprisService) {
        this.service = service
        scope.launch {
            session.player.flatMapLatest { p -> if (p == null) emptyFlow() else snapshotFlow { stateOf(p) } }
                .distinctUntilChanged()
                .collect { state ->
                    playing = state.status == MprisStatus.PLAYING
                    service.update(state)
                }
        }
        scope.launch {
            // Signed out: nothing playing.
            session.player.collect { if (it == null) service.update(MprisState()) }
        }
        scope.launch {
            session.player.collectLatest { p -> if (p != null) watchSeeks(p, service) }
        }
    }

    /** The state as MPRIS shows it; read inside snapshotFlow, so it runs again when any of it changes. */
    private fun stateOf(p: PlayerController): MprisState {
        val entry = p.playQueue.currentEntry
        val track = p.currentTrack
        val mprisTrack = if (entry == null || track == null) null else MprisTrack(
            entryId = entry.id,
            title = track.title,
            artist = track.artist,
            album = track.album,
            lengthMicros = ((if (p.duration > 0) p.duration else track.durationSeconds) * 1_000_000).toLong(),
            artUrl = coverFor(track.localArtPath, track.artItemId),
        )
        val queueSize = p.playQueue.entriesInPlayOrder.size
        return MprisState(
            track = mprisTrack,
            status = when {
                p.isPlaying || p.isStarting -> MprisStatus.PLAYING
                mprisTrack != null -> MprisStatus.PAUSED
                else -> MprisStatus.STOPPED
            },
            repeat = p.repeatMode,
            shuffle = p.shuffle,
            canSkip = !p.maniacMode,
            hasNext = p.currentIndex < queueSize - 1 || (p.repeatMode == RepeatMode.ALL && queueSize > 0),
            canPlay = mprisTrack != null && p.isSupported,
        )
    }

    /**
     * Signals Seeked when the position jumps (a seek from here or from the
     * app): more than 1.5 s away from where the clock says it should be.
     * Position ticks every 0.5 s while playing.
     */
    private suspend fun watchSeeks(p: PlayerController, service: MprisService) {
        var entry: String? = null
        var seconds = 0.0
        var at = System.nanoTime()
        var wasPlaying = false
        snapshotFlow { Triple(p.playQueue.currentEntryId, p.position, p.isPlaying) }.collect { (id, position, isPlaying) ->
            val now = System.nanoTime()
            val expected = seconds + if (wasPlaying) (now - at) / 1e9 else 0.0
            if (id == entry && abs(position - expected) > SEEK_JUMP_SECONDS) service.seeked((position * 1_000_000).toLong())
            entry = id
            seconds = position
            at = now
            wasPlaying = isPlaying
            lastPositionSeconds = position
        }
    }

    /** The cover's `file://` URI, if it's on disk; otherwise fetches it (once) and answers null for now. */
    private fun coverFor(localPath: String?, itemId: String): String? {
        localPath?.let { path -> File(path).takeIf { it.isFile }?.let { return it.toPath().toUri().toString() } }
        covers[itemId]?.let { return it }
        val api = session.api.value ?: return null
        if (fetching.add(itemId)) scope.launch { fetchCover(api, itemId) }
        return null
    }

    private suspend fun fetchCover(api: JellyfinApi, itemId: String) {
        val file = File(artDir, safeName(itemId) + ".jpg")
        val saved = withContext(Dispatchers.IO) {
            try {
                if (!file.isFile) {
                    val response = api.http.get(api.imageUrl(itemId, COVER_WIDTH)) {
                        header(HttpHeaders.Authorization, api.authorizationHeader())
                    }
                    if (!response.status.isSuccess()) return@withContext false
                    artDir.mkdirs()
                    val part = File(artDir, file.name + ".part")
                    part.writeBytes(response.bodyAsBytes())
                    if (!part.renameTo(file)) return@withContext false
                    prune()
                }
                true
            } catch (e: IOException) {
                false
            } catch (e: Exception) {
                // Ktor's own errors (timeouts, a bad response): no cover this time.
                false
            }
        }
        fetching.remove(itemId)
        if (saved) covers[itemId] = file.toPath().toUri().toString()
    }

    /** Keeps the newest [KEPT_COVERS] covers. */
    private fun prune() {
        val files = artDir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") } ?: return
        if (files.size <= KEPT_COVERS) return
        files.sortedByDescending { it.lastModified() }.drop(KEPT_COVERS).forEach { it.delete() }
    }

    // MprisControls: called on D-Bus threads; everything moves to the main thread.

    private fun onMain(block: PlayerController.() -> Unit) {
        scope.launch { player?.block() }
    }

    override fun play() = onMain { if (!isPlaying && !isStarting) resume() }
    override fun pause() = onMain { if (isPlaying || isStarting) pause() }
    override fun playPause() = onMain { togglePlayPause() }

    /** No "stopped with a queue" state in the app: Stop pauses. */
    override fun stop() = pause()
    override fun next() = onMain { skipToNext() }
    override fun previous() = onMain { skipToPrevious() }

    override fun seekBy(offsetMicros: Long) = onMain {
        val target = position + offsetMicros / 1_000_000.0
        // Past the end is Next, as the spec says.
        if (duration > 0 && target >= duration) skipToNext() else seek(target.coerceAtLeast(0.0))
    }

    override fun setPosition(entryId: String, positionMicros: Long) = onMain {
        if (playQueue.currentEntryId == entryId) seek(positionMicros / 1_000_000.0)
    }

    override fun setRepeat(mode: RepeatMode) = onMain { changeRepeatMode(mode) }
    override fun setShuffle(on: Boolean) = onMain { changeShuffle(on) }

    override fun raise() {
        scope.launch { onRaise() }
    }

    override fun quit() {
        scope.launch { onQuit() }
    }

    /** From the player itself while playing (exact); else the last position the app showed. */
    override fun positionMicros(): Long {
        val seconds = if (playing) engine.positionMillis / 1000.0 else lastPositionSeconds
        return (seconds * 1_000_000).toLong()
    }

    override fun close() {
        scope.cancel()
        service?.close()
        service = null
    }

    companion object {
        private const val SEEK_JUMP_SECONDS = 1.5
        private const val COVER_WIDTH = 600
        private const val KEPT_COVERS = 30

        /**
         * Puts the app on the session bus; null if there's no bus (logged).
         * `cacheDir`: covers go in its `mpris-art` folder. `onRaise` and
         * `onQuit` run on the main thread.
         */
        fun start(
            session: AppSession,
            engine: PlayerEngine,
            cacheDir: File,
            onRaise: () -> Unit,
            onQuit: () -> Unit,
        ): MprisBridge? {
            val bridge = MprisBridge(session, engine, File(cacheDir, "mpris-art"), onRaise, onQuit)
            val service = MprisService.start(bridge, desktopEntry = desktopEntryName()) ?: return null
            bridge.start(service)
            return bridge
        }

        /**
         * The installed .desktop file's name, for the desktop to find the icon. Guess (unverified):
         * jpackage's .deb and .rpm install `tetsubi-tetsubi.desktop`; the AppImage carries `tetsubi.desktop`.
         */
        private fun desktopEntryName(): String = if (System.getenv("APPIMAGE") != null) "tetsubi" else "tetsubi-tetsubi"

        private fun safeName(id: String) = id.map { if (it.isLetterOrDigit() || it == '-') it else '_' }.joinToString("")
    }
}
