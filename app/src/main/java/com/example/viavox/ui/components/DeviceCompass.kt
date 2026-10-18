package com.example.viavox.ui.components

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs

/**
 * Screen-corrected azimuth relative to TRUE north (matching the map).
 * Phone TOP must point towards the intended forward direction.
 * Active only while the map is visible and tracking is running.
 */
@Composable
fun rememberDeviceCompassHeading(
    enabled: Boolean,
    latitude: Double,
    longitude: Double
): Float? {
    val context = LocalContext.current
    val sensors = remember(context) {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    val lat by rememberUpdatedState(latitude)
    val lon by rememberUpdatedState(longitude)
    var heading by remember { mutableStateOf<Float?>(null) }

    DisposableEffect(enabled, sensors) {
        val vector = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensors.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        if (!enabled || vector == null) {
            heading = null
            onDispose { }
        } else {
            var lastUpdateMs = 0L
            val listener = object : SensorEventListener {
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                    if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) heading = null
                }

                override fun onSensorChanged(event: SensorEvent) {
                    if (event.sensor.type != vector.type) return
                    if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
                        heading = null
                        return
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUpdateMs < 700L) return

                    val matrix = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(matrix, event.values)
                    val adjusted = FloatArray(9)
                    // Screen rotation changes the heading of the visual top of the app.
                    @Suppress("DEPRECATION")
                    val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                        ?.defaultDisplay?.rotation ?: Surface.ROTATION_0
                    when (rotation) {
                        Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(
                            matrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, adjusted
                        )
                        Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(
                            matrix, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, adjusted
                        )
                        Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(
                            matrix, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, adjusted
                        )
                        else -> System.arraycopy(matrix, 0, adjusted, 0, 9)
                    }
                    val angles = FloatArray(3)
                    SensorManager.getOrientation(adjusted, angles)
                    val magneticAzimuth = Math.toDegrees(angles[0].toDouble()).toFloat()
                    val declination = GeomagneticField(
                        lat.toFloat(), lon.toFloat(), 0f, System.currentTimeMillis()
                    ).declination
                    val corrected = ((magneticAzimuth + declination) % 360f + 360f) % 360f
                    val previous = heading
                    val delta = if (previous == null) 360f
                        else abs(((corrected - previous + 540f) % 360f) - 180f)
                    if (delta >= 5f || now - lastUpdateMs >= 2_200L) {
                        heading = corrected
                        lastUpdateMs = now
                    }
                }
            }
            sensors.registerListener(listener, vector, SensorManager.SENSOR_DELAY_UI)
            onDispose { sensors.unregisterListener(listener) }
        }
    }
    return heading
}
