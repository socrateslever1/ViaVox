package com.example.viavox.ui.components

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs

/**
 * Foreground-only, low-frequency hardware compass. Used when GPS has no
 * trustworthy travel direction (for example while stopped).
 * On devices without a rotation-vector sensor, returns null.
 */
@Composable
fun rememberDeviceCompassHeading(enabled: Boolean): Float? {
    val context = LocalContext.current
    val sensors = remember(context) {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    var heading by remember { mutableStateOf<Float?>(null) }

    DisposableEffect(enabled, sensors) {
        val vector = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (!enabled || vector == null) {
            heading = null
            onDispose { }
        } else {
            var lastUpdate = 0L
            val listener = object : SensorEventListener {
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                override fun onSensorChanged(event: SensorEvent) {
                    if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUpdate < 400L) return
                    val rotation = FloatArray(9)
                    val angles = FloatArray(3)
                    SensorManager.getRotationMatrixFromVector(rotation, event.values)
                    SensorManager.getOrientation(rotation, angles)
                    val azimuth = ((Math.toDegrees(angles[0].toDouble()).toFloat() % 360f) + 360f) % 360f
                    val previous = heading
                    val delta = if (previous == null) 360f else {
                        abs(((azimuth - previous + 540f) % 360f) - 180f)
                    }
                    if (delta >= 4f || now - lastUpdate >= 1_500L) {
                        heading = azimuth
                        lastUpdate = now
                    }
                }
            }
            sensors.registerListener(listener, vector, SensorManager.SENSOR_DELAY_UI)
            onDispose { sensors.unregisterListener(listener) }
        }
    }
    return heading
}
