package gg.dekiru.musicplayer.ui.nowplaying

import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.mediarouter.app.SystemOutputSwitcherDialogController
import kotlinx.coroutines.delay

@Composable
actual fun rememberOutputSwitcher(): (() -> Unit)? {
    val context = LocalContext.current
    return remember(context) { { SystemOutputSwitcherDialogController.showDialog(context) } }
}

@Composable
actual fun rememberExternalOutput(): Boolean {
    val context = LocalContext.current
    val audio = remember(context) { context.getSystemService(AudioManager::class.java) }
    var external by remember { mutableStateOf(isExternalOutput(audio)) }
    // Headphones plugged in or out, Bluetooth connected or not.
    DisposableEffect(audio) {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                external = isExternalOutput(audio)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                external = isExternalOutput(audio)
            }
        }
        runCatching { audio?.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper())) }
        onDispose { runCatching { audio?.unregisterAudioDeviceCallback(callback) } }
    }
    // A change of output with nothing plugged in or out (picked in the output switcher).
    LaunchedEffect(audio) {
        while (true) {
            delay(3_000)
            external = isExternalOutput(audio)
        }
    }
    return external
}

/** Outputs that aren't this device: headphones, Bluetooth, USB, HDMI, line out, hearing aids. */
private val externalTypes: Set<Int> = buildSet {
    addAll(
        listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE, AudioDeviceInfo.TYPE_DOCK,
            AudioDeviceInfo.TYPE_HEARING_AID,
        ),
    )
    if (Build.VERSION.SDK_INT >= 31) addAll(listOf(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HDMI_EARC))
    if (Build.VERSION.SDK_INT >= 33) add(AudioDeviceInfo.TYPE_BLE_BROADCAST)
}

/**
 * Where music goes now. Android 13+ says which output media is routed to;
 * before that, a connected external output counts (Android routes music
 * to it).
 */
private fun isExternalOutput(audio: AudioManager?): Boolean {
    audio ?: return false
    return runCatching {
        val devices = if (Build.VERSION.SDK_INT >= 33) {
            val music = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            audio.getAudioDevicesForAttributes(music)
        } else {
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }
        devices.any { it.type in externalTypes }
    }.getOrDefault(false)
}

