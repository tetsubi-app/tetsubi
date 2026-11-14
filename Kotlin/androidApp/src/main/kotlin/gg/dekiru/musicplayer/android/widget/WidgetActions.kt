package gg.dekiru.musicplayer.android.widget

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import gg.dekiru.musicplayer.android.PlaybackService
import gg.dekiru.musicplayer.android.TetsubiApplication
import gg.dekiru.musicplayer.android.auto.AutoId
import gg.dekiru.musicplayer.android.auto.AutoLibrary
import gg.dekiru.musicplayer.widget.WidgetPlay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** The widget's Play/Pause button. */
@UnstableApi
class PlayPauseAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        WidgetCommands.send(context, WidgetCommand.PLAY_PAUSE)
}

/** The widget's Next button. */
@UnstableApi
class NextAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        WidgetCommands.send(context, WidgetCommand.NEXT)
}

/** The widget's Previous button. */
@UnstableApi
class PreviousAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) =
        WidgetCommands.send(context, WidgetCommand.PREVIOUS)
}

enum class WidgetCommand { PLAY_PAUSE, NEXT, PREVIOUS }

/**
 * Carries out the widget's buttons. Each one connects a `MediaController`
 * to the playback service's session (which starts the service, and the
 * app with it, when they aren't running) and sends the command there, as
 * the notification's buttons do; Maniac Mode's rules are the session's.
 *
 * Play with nothing loaded (the app was closed) starts the album the
 * widget shows again from the start, through the same code Android Auto
 * and voice requests use ([AutoLibrary]); offline, from its download. If
 * nothing can play, the app opens. Nothing here ever throws.
 */
@UnstableApi
object WidgetCommands {

    private const val TAG = "TetsubiWidget"

    /** How long to wait for the playback service to answer. */
    private const val CONNECT_TIMEOUT_MILLIS = 5_000L

    /** How long a button waits before the widget's tap is let go; the work carries on after. */
    private const val ACTION_TIMEOUT_MILLIS = 8_000L

    /** The controller stays connected this long after a command, so the service can go to the foreground first. */
    private const val HOLD_MILLIS = 3_000L

    /** Outlives the widget's tap: starting an album can take longer than the tap may last. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    suspend fun send(context: Context, command: WidgetCommand) {
        val app = context.applicationContext
        val work = scope.async { run(app, command) }
        withTimeoutOrNull(ACTION_TIMEOUT_MILLIS) { work.await() }
    }

    private suspend fun run(context: Context, command: WidgetCommand) {
        try {
            val app = context as? TetsubiApplication ?: return
            val controller = connect(context)
            if (controller == null) {
                openApp(context)
                return
            }
            try {
                when (command) {
                    WidgetCommand.PLAY_PAUSE -> playPause(app, controller)
                    WidgetCommand.NEXT -> if (controller.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)) controller.seekToNext()
                    WidgetCommand.PREVIOUS -> if (controller.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS)) controller.seekToPrevious()
                }
                delay(HOLD_MILLIS)
            } finally {
                controller.release()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Widget button failed", e)
        }
    }

    private suspend fun playPause(app: TetsubiApplication, controller: MediaController) {
        // The service restored the login when it started; the session's player is the app's.
        val hasQueue = app.session.player.value?.currentTrack != null && controller.mediaItemCount > 0
        val snapshot = withContext(Dispatchers.IO) { WidgetFiles(app).read() }
        when (val play = WidgetPlay.of(hasQueue, snapshot)) {
            WidgetPlay.Toggle -> Util.handlePlayPauseButtonAction(controller)
            WidgetPlay.OpenApp -> openApp(app)
            is WidgetPlay.Replay -> {
                val library = AutoLibrary(app.session)
                library.restore()
                // As Android Auto's Play on an album: from the start, in order.
                val refusal = library.play(AutoId.PlayAlbum(play.discIds, shuffled = false))
                if (refusal != null) {
                    Log.i(TAG, "Couldn't play the widget's album: $refusal")
                    openApp(app)
                }
            }
        }
    }

    /** A controller connected to the playback service's session, or null when it doesn't answer. */
    private suspend fun connect(context: Context): MediaController? {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val controller = withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                future.addListener(
                    { continuation.resume(runCatching { future.get() }.getOrNull()) },
                    ContextCompat.getMainExecutor(context),
                )
            }
        }
        if (controller == null) MediaController.releaseFuture(future)
        return controller
    }

    /** Opens the app (on Now Playing when something's loaded). Android may not allow it once the tap is long past. */
    private fun openApp(context: Context) {
        try {
            context.startActivity(openAppIntent(context))
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't open the app from the widget", e)
        }
    }
}
