package gg.dekiru.musicplayer.android

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import gg.dekiru.musicplayer.android.auto.AutoCallback
import gg.dekiru.musicplayer.android.auto.AutoLibrary
import gg.dekiru.musicplayer.android.auto.AutoMediaItems
import gg.dekiru.musicplayer.android.auto.AutoTab
import gg.dekiru.musicplayer.android.auto.PlayRequests
import gg.dekiru.musicplayer.playback.ArtworkLoader
import gg.dekiru.musicplayer.playback.Media3Engine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps music playing with the app closed: a foreground service holding a
 * MediaSession around the app's one player. Media3 shows the notification
 * and lock-screen controls from it, and routes Bluetooth / headset buttons
 * to it.
 *
 * It's also a media library, for Android Auto and Google Assistant: the
 * browse tree (Home, Artists, Playlists, Downloads, as CarPlay), search,
 * and "Play <album> on Tetsubi" ([AutoCallback]). Android Auto can start it
 * without the app ever opening, so it restores the saved login itself;
 * offline, the downloads play.
 */
@UnstableApi
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val app: TetsubiApplication get() = application as TetsubiApplication
    private val engine: Media3Engine get() = app.engine

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        val appSession = app.session
        // Started by Android Auto or Assistant with no screen open: sign in from the saved login.
        appSession.restore()
        // Started by the widget, Android Auto or a media button with no screen open: the widget follows from here.
        app.startWidgetFeed()
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val callback = AutoCallback(AutoLibrary(appSession), AutoMediaItems(this), scope, packageName, openApp)
        val session = MediaLibrarySession.Builder(this, PlayRequests(engine.sessionPlayer()), callback)
            .setSessionActivity(openApp)
            .setBitmapLoader(ArtworkLoader(this) { engine.authorization })
            .build()
        this.session = session
        // Maniac Mode on/off: swap in a player with (or without) skip and seek; the car's pages lose (or gain) Shuffle.
        scope.launch {
            engine.skipEnabled.drop(1).collect {
                session.player = PlayRequests(engine.sessionPlayer())
                callback.maniacModeChanged(session)
            }
        }
        // Casting started or stopped: the session follows the player that's playing, so the
        // notification, lock screen and volume keys control the Cast device (or this one again).
        scope.launch {
            engine.activePlayer.drop(1).collect { session.player = PlayRequests(engine.sessionPlayer()) }
        }
        // Signed in or out: every tab changes.
        scope.launch { appSession.api.drop(1).collect { callback.tabsChanged(session) } }
        // The connection came or went: Home, Artists and Playlists come from somewhere else.
        scope.launch {
            appSession.environment.network.state.map { it.online }.distinctUntilChanged().drop(1)
                .collect { callback.tabsChanged(session, listOf(AutoTab.HOME, AutoTab.ARTISTS, AutoTab.PLAYLISTS)) }
        }
        // A download finished or was removed.
        scope.launch {
            appSession.downloads.flatMapLatest { it?.downloaded ?: flowOf(emptyList()) }.drop(1)
                .collect { callback.tabsChanged(session, listOf(AutoTab.DOWNLOADS)) }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    /** Swiped away from recents while paused: stop. While playing it carries on. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.release()
        session = null
        super.onDestroy()
    }
}
