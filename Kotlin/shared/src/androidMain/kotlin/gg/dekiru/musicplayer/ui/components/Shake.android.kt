package gg.dekiru.musicplayer.ui.components

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlin.math.sqrt

/** A shake: more than 2.5 g, at most one per second. */
private const val SHAKE_G = 2.5f
private const val GAP_MS = 1_000L

@Composable
actual fun ShakeEffect(onShake: () -> Unit): Boolean {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onShake)
    val sensors = remember(context) { context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager }
    val accelerometer = remember(sensors) { sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
    DisposableEffect(sensors, accelerometer) {
        if (sensors == null || accelerometer == null) return@DisposableEffect onDispose {}
        var last = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val (x, y, z) = event.values
                val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
                val now = System.currentTimeMillis()
                if (g > SHAKE_G && now - last > GAP_MS) {
                    last = now
                    latest()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensors.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_UI)
        onDispose { sensors.unregisterListener(listener) }
    }
    return accelerometer != null
}
