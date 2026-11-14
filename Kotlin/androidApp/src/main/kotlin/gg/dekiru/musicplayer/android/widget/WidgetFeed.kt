package gg.dekiru.musicplayer.android.widget

import android.util.Log
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.glance.appwidget.updateAll
import androidx.media3.common.util.UnstableApi
import gg.dekiru.musicplayer.android.TetsubiApplication
import gg.dekiru.musicplayer.ui.nowplaying.artAccent
import gg.dekiru.musicplayer.ui.nowplaying.loadArtImage
import gg.dekiru.musicplayer.widget.WidgetContent
import gg.dekiru.musicplayer.widget.WidgetLive
import gg.dekiru.musicplayer.widget.WidgetSnapshot
import gg.dekiru.musicplayer.widget.throttleLatest
import gg.dekiru.musicplayer.widget.widgetLive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Keeps the home-screen widget up to date: whenever the track, play/pause,
 * Maniac Mode or the sign-in changes, it writes the snapshot and the art
 * ([WidgetFiles]) and redraws the widgets. At most about once a second, so
 * skipping through an album doesn't redraw them for every track. While
 * paused it redraws them once a minute, as the finish time moves with the
 * clock.
 *
 * The key colour is worked out here from the same picture Now Playing
 * uses, so the widget's colour is the app's. Started by the app's screen
 * and by the playback service (whichever comes first); runs for the rest
 * of the process.
 */
@UnstableApi
class WidgetFeed(private val app: TetsubiApplication) {

    private val files = WidgetFiles(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The last snapshot written (read from the file at the start). */
    private var previous: WidgetSnapshot? = null

    /** The last picture worked out: its key, colour and saved file. */
    private var lastArt: SavedArt? = null

    /** Redraws the widgets each minute while paused. */
    private var ticker: Job? = null

    private data class SavedArt(val key: String, val accentRGB: Int?, val file: String?)

    /** Starts it (main thread); a second call does nothing. */
    fun start() {
        if (_running.value) return
        _running.value = true
        sendStateChanges()
        scope.launch {
            previous = withContext(Dispatchers.IO) { files.read() }
            app.session.widgetLive().throttleLatest(THROTTLE_MILLIS).collect { live ->
                try {
                    write(live)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The widget keeps what it had; the next change tries again.
                    Log.w(TAG, "Couldn't update the widget", e)
                }
            }
        }
    }

    private suspend fun write(live: WidgetLive) {
        val art = live.artKey?.let { art(it, live) }
        val snapshot = WidgetSnapshot.next(previous, live, art?.accentRGB, art?.file, System.currentTimeMillis())
        withContext(Dispatchers.IO) {
            files.write(snapshot)
            files.removeArtExcept(snapshot.artFile)
        }
        previous = snapshot
        _changes.value++
        TetsubiWidget().updateAll(app)
        tickWhilePaused(snapshot)
    }

    /** While paused with a finish time to show, redraws the widgets just after each minute; stops otherwise. */
    private fun tickWhilePaused(snapshot: WidgetSnapshot) {
        val paused = snapshot.signedIn && snapshot.trackTitle != null && !snapshot.isPlaying && snapshot.queueSeconds > 0
        if (!paused) {
            ticker?.cancel()
            ticker = null
            return
        }
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (true) {
                delay(WidgetContent.millisToNextMinute(System.currentTimeMillis()))
                _minute.value++
                try {
                    TetsubiWidget().updateAll(app)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't redraw the widget", e)
                }
            }
        }
    }

    /** The track's picture saved small, with its key colour; worked out once per picture. */
    private suspend fun art(key: String, live: WidgetLive): SavedArt {
        lastArt?.takeIf { it.key == key }?.let { return it }
        val track = live.track ?: return SavedArt(key, null, null)
        val api = app.session.api.value ?: return SavedArt(key, null, null)
        // The same picture and size as Now Playing, so it's normally cached and the colour matches.
        val url = track.localArtPath ?: api.imageUrl(track.artItemId, 600)
        val image = loadArtImage(app, url, api.authorizationHeader())
        // Not had (offline, not downloaded): no picture this time, and asked for again next change.
            ?: return SavedArt(key, null, null)
        val accent = artAccent(image)?.let { rgb(it.red, it.green, it.blue) }
        val file = withContext(Dispatchers.IO) { files.saveArt(key, image.asAndroidBitmap()) }
        return SavedArt(key, accent, file).also { lastArt = it }
    }

    /**
     * The player's state is Compose state. With no screen open (the app
     * was started for Android Auto or by the widget) nothing tells
     * `snapshotFlow` it changed, so this does, as Compose's own screens do.
     */
    private fun sendStateChanges() {
        val pending = Channel<Unit>(Channel.CONFLATED)
        Snapshot.registerGlobalWriteObserver { pending.trySend(Unit) }
        scope.launch { for (change in pending) Snapshot.sendApplyNotifications() }
    }

    companion object {
        private const val TAG = "TetsubiWidget"

        /** At most one widget update per this many milliseconds. */
        const val THROTTLE_MILLIS = 1_000L

        private val _running = MutableStateFlow(false)

        /**
         * Whether the app's player is running in this process. When it
         * isn't (the app was closed), a snapshot that says "playing" is out
         * of date.
         */
        val running: StateFlow<Boolean> = _running.asStateFlow()

        private val _changes = MutableStateFlow(0)

        /** Goes up each time a snapshot is written, so open widgets read it again. */
        val changes: StateFlow<Int> = _changes.asStateFlow()

        private val _minute = MutableStateFlow(0)

        /** Goes up each minute while paused, so open widgets work out the finish time again. */
        val minute: StateFlow<Int> = _minute.asStateFlow()

        /** 0..1 channels as 0xRRGGBB. */
        fun rgb(red: Double, green: Double, blue: Double): Int {
            fun byte(v: Double) = (v.coerceIn(0.0, 1.0) * 255).roundToInt()
            return (byte(red) shl 16) or (byte(green) shl 8) or byte(blue)
        }
    }
}
