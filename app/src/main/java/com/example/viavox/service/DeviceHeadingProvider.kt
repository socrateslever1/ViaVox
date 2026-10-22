package com.example.viavox.service

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock

/**
 * Absolute heading uses the Android rotation-vector fusion sensor.
 * A gyroscope by itself measures rotation, NOT geographic north.
 * Do not claim right/left when heading is missing, unreliable or stale.
 */
class DeviceHeadingProvider(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: manager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
    private var lastHeading: Float? = null
    private var updatedAtMs = 0L
    private var trusted = false
    private var registered = false

    fun start() {
        if (!registered && sensor != null) {
            registered = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        if (registered) manager.unregisterListener(this)
        registered = false
        lastHeading = null
        updatedAtMs = 0L
    }

    fun headingOrNull(): Float? =
        lastHeading?.takeIf { trusted && registered &&
            SystemClock.elapsedRealtime() - updatedAtMs <= 8_000L }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR &&
            event.sensor.type != Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR) return
        if (!trusted) return
        val rotation = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        val angles = FloatArray(3)
        SensorManager.getOrientation(rotation, angles)
        val heading = (Math.toDegrees(angles[0].toDouble()).toFloat() + 360f) % 360f
        if (heading.isFinite()) {
            lastHeading = heading
            updatedAtMs = SystemClock.elapsedRealtime()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        trusted = accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE &&
            accuracy != SensorManager.SENSOR_STATUS_ACCURACY_LOW
        if (!trusted) lastHeading = null
    }
}
