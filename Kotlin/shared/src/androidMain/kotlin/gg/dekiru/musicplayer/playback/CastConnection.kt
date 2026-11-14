package gg.dekiru.musicplayer.playback

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.cast.Cast
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastState
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Google Cast for [Media3Engine]: the Cast device's player (media3-cast's
 * `RemoteCastPlayer`, Default Media Receiver; options in the app's
 * `CastOptionsProvider`), whether there are Cast devices on the network,
 * and the name of the one playing. Made only where Play services is
 * installed ([isAvailable]); a failure along the way leaves Cast off and
 * never crashes. Main thread.
 */
@UnstableApi
internal class CastConnection(private val context: Context) : CastRoute {

    private val _devicesAvailable = MutableStateFlow(false)
    private val _deviceName = MutableStateFlow<String?>(null)
    override val devicesAvailable: StateFlow<Boolean> = _devicesAvailable.asStateFlow()
    override val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    /** The Cast device's player, once [start] has made it; null if it couldn't be. */
    var player: RemoteCastPlayer? = null
        private set

    /**
     * Makes the Cast player and starts watching for devices. `onSession`
     * is called with true when a Cast session starts (the music moves to
     * the device) and false when it ends. Main thread.
     */
    fun start(onSession: (Boolean) -> Unit) {
        if (player != null) return
        val remote = try {
            // Starts the Cast framework from the manifest's options provider (in the background).
            RemoteCastPlayer.Builder(context).build()
        } catch (e: Exception) {
            Log.w(TAG, "Google Cast isn't available", e)
            return
        }
        player = remote
        remote.setSessionAvailabilityListener(object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() {
                _deviceName.value = currentDeviceName() ?: DEFAULT_DEVICE_NAME
                onSession(true)
            }

            override fun onCastSessionUnavailable() {
                _deviceName.value = null
                onSession(false)
            }
        })
        try {
            CastContext.getSharedInstance(context, ContextCompat.getMainExecutor(context))
                .addOnSuccessListener { castContext ->
                    runCatching {
                        _devicesAvailable.value = castContext.castState != CastState.NO_DEVICES_AVAILABLE
                        castContext.addCastStateListener { state -> _devicesAvailable.value = state != CastState.NO_DEVICES_AVAILABLE }
                    }.onFailure { Log.w(TAG, "Couldn't watch for Cast devices", it) }
                }
                .addOnFailureListener { Log.w(TAG, "Google Cast didn't start", it) }
        } catch (e: Exception) {
            Log.w(TAG, "Google Cast didn't start", e)
        }
    }

    /** Why the receiver went idle (`MediaStatus.IDLE_REASON_…`), or null. */
    fun idleReason(): Int? = runCatching {
        Cast.getSingletonInstance(context).currentCastSession?.remoteMediaClient?.mediaStatus?.idleReason
    }.getOrNull()

    private fun currentDeviceName(): String? = runCatching {
        Cast.getSingletonInstance(context).currentCastSession?.castDevice?.friendlyName?.takeIf { it.isNotBlank() }
    }.getOrNull()

    companion object {
        private const val TAG = "TetsubiCast"

        /** When the device doesn't say its name. */
        const val DEFAULT_DEVICE_NAME = "Cast device"

        /** Whether Google Play services is here (Cast needs it). Never throws. */
        fun isAvailable(context: Context): Boolean = runCatching {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        }.getOrDefault(false)
    }
}
