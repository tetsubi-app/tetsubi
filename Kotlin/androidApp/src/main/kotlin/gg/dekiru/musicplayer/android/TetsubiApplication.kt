package gg.dekiru.musicplayer.android

import android.app.Application
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import gg.dekiru.musicplayer.AppSession
import gg.dekiru.musicplayer.android.widget.WidgetFeed
import gg.dekiru.musicplayer.data.auth.AndroidSecretStore
import gg.dekiru.musicplayer.data.auth.Authenticator
import gg.dekiru.musicplayer.data.auth.androidDeviceName
import gg.dekiru.musicplayer.data.settings.androidEnvironment
import gg.dekiru.musicplayer.downloads.DownloadManager
import gg.dekiru.musicplayer.downloads.DownloadsHost
import gg.dekiru.musicplayer.playback.Media3Engine
import gg.dekiru.musicplayer.ui.components.tetsubiImageLoader

/**
 * Owns the app's one player and [AppSession], so they outlive the activity:
 * the playback service and the screens share them. Also the image loader,
 * so the notification's artwork uses the same caches as the screens.
 * Download work finds the session's download manager here (see [DownloadsHost]).
 * And the home-screen widget's writer ([WidgetFeed]).
 */
@UnstableApi
class TetsubiApplication : Application(), SingletonImageLoader.Factory, DownloadsHost {

    /** Created on first use, on the main thread (ExoPlayer requires it). */
    val engine: Media3Engine by lazy { Media3Engine(this) }

    val session: AppSession by lazy {
        AppSession(
            Authenticator(AndroidSecretStore(this), androidDeviceName(this)),
            // The player tries a dropped converted stream again as soon as the network is back.
            androidEnvironment(this, engine).also { engine.watchNetwork(it.network.state) },
        )
    }

    private val widgetFeed by lazy { WidgetFeed(this) }

    /**
     * Keeps the home-screen widget up to date from now on (main thread;
     * called by the screen and the playback service, whichever starts
     * first). Restores the saved login first, so the widget isn't told
     * "signed out" while the app is still starting.
     */
    fun startWidgetFeed() {
        session.restore()
        widgetFeed.start()
    }

    /** Restores the saved login first when the process was started just for download work. */
    override fun downloadManager(): DownloadManager? {
        session.restore()
        return session.downloads.value
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = tetsubiImageLoader(context, cacheDir.absolutePath)
}
