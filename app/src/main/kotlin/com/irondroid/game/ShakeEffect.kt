package com.irondroid.game

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlin.math.sqrt

/** Pull, in g, past which a movement of the phone counts as a shake. */
private const val SHAKE_G = 2.3f

/** A shake right after another is the same shake. */
private const val SHAKE_GAP_MS = 1_000L

/**
 * Calls [onShake] when the player shakes the phone while this is on screen and [enabled]
 * (RF-DAD-06, IRON-73). Phones without an accelerometer never shake.
 */
@Composable
fun ShakeEffect(enabled: Boolean, onShake: () -> Unit) {
    val context = LocalContext.current
    val shake by rememberUpdatedState(onShake)
    DisposableEffect(enabled) {
        val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val accelerometer = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (!enabled || sensors == null || accelerometer == null) return@DisposableEffect onDispose {}
        var last = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val (x, y, z) = event.values
                val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
                val now = SystemClock.elapsedRealtime()
                if (g > SHAKE_G && now - last > SHAKE_GAP_MS) {
                    last = now
                    shake()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        sensors.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sensors.unregisterListener(listener) }
    }
}
