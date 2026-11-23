package gg.dekiru.musicplayer.android

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import gg.dekiru.musicplayer.App
import gg.dekiru.musicplayer.downloads.DownloadNotice
import gg.dekiru.musicplayer.ui.recent.GridSave
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@UnstableApi
class MainActivity : ComponentActivity() {

    /** Connected while the app is on screen, so the playback service (and its session) exists. */
    private var controller: ListenableFuture<MediaController>? = null

    // Downloads run whatever the answer; it only decides whether the notification shows.
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // Recently Played ▸ Save: registered here, not by the screen, so the
    // picker's answer arrives even if the activity is rebuilt while it's open.
    private val saveGrid = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        GridSave.documentChosen(applicationContext, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Navy splash with the disc, then the app (Android 12+ style, back to 8.0).
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as TetsubiApplication
        app.startWidgetFeed()
        val session = app.session
        GridSave.attach(this) { name -> saveGrid.launch(name) }
        setContent { App(session) }
        askForNotificationsOnFirstDownload()
        openNowPlayingIfAsked(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openNowPlayingIfAsked(intent)
    }

    /** Opened from the home-screen widget: Now Playing, when something's loaded (else Home, as it is). */
    private fun openNowPlayingIfAsked(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_NOW_PLAYING, false) != true) return
        intent.removeExtra(EXTRA_OPEN_NOW_PLAYING)
        val player = (application as TetsubiApplication).session.player.value ?: return
        if (player.currentTrack != null) player.isNowPlayingOpen = true
    }

    /** Android 13+: asks once, the first time a download starts (as the progress notification needs it). */
    private fun askForNotificationsOnFirstDownload() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val prefs = getSharedPreferences("app", Context.MODE_PRIVATE)
        if (prefs.getBoolean(ASKED_NOTIFICATIONS, false)) return
        val session = (application as TetsubiApplication).session
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.downloads
                    .flatMapLatest { it?.active ?: flowOf(emptyMap()) }
                    .first { DownloadNotice.hasQueued(it) }
                if (prefs.getBoolean(ASKED_NOTIFICATIONS, false)) return@repeatOnLifecycle
                prefs.edit().putBoolean(ASKED_NOTIFICATIONS, true).apply()
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    override fun onDestroy() {
        GridSave.detach(this)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controller = MediaController.Builder(this, token).buildAsync()
    }

    override fun onStop() {
        // Going to the background: the listening queue is written now (where it is in the track too).
        (application as TetsubiApplication).session.saveQueueSoon()
        controller?.let(MediaController::releaseFuture)
        controller = null
        super.onStop()
    }

    companion object {
        /** Set by the home-screen widget: open on Now Playing. */
        const val EXTRA_OPEN_NOW_PLAYING = "gg.dekiru.musicplayer.OPEN_NOW_PLAYING"

        private const val ASKED_NOTIFICATIONS = "askedNotificationsForDownloads"
    }
}
