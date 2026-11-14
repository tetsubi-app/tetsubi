package gg.dekiru.musicplayer.android.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions

/**
 * Google Cast's options (named in the manifest): Google's Default Media
 * Receiver, which plays MP3, AAC and FLAC from a URL. The Cast SDK's own
 * media notification and media session are off: the app's MediaSession
 * (Media3) already follows the Cast device, so the notification, lock
 * screen and volume keys stay as they are.
 */
class CastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .setCastMediaOptions(
            CastMediaOptions.Builder()
                .setNotificationOptions(null)
                .setMediaSessionEnabled(false)
                .build(),
        )
        // As media3-cast's own options: a new start each time, and Stop casting stops the TV too.
        .setResumeSavedSession(false)
        .setEnableReconnectionService(false)
        .setStopReceiverApplicationWhenEndingSession(true)
        // The system output switcher can bring the music back to this device.
        .setRemoteToLocalEnabled(true)
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
