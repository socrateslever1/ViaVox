package com.example.viavox.model

import kotlin.math.*

/**
 * Camera follows a point ahead of the vehicle. Rotating the map so the vehicle
 * points toward the top places the puck in the lower portion of the viewport.
 * No simulated GPS direction is introduced when course is unavailable.
 */
object NavigationCameraMath {
    const val GUIDANCE_ZOOM = 17.7
    const val GUIDE_TILT = 47.0

    fun lookAheadMeters(speedKmh: Float): Double =
        (52.0 + speedKmh.coerceIn(0f, 90f) * 0.65).coerceIn(52.0, 108.0)

    fun cameraTarget(latitude: Double, longitude: Double,
                     bearing: Double, lookAheadMeters: Double): Pair<Double,Double> {
        if (!latitude.isFinite() || !longitude.isFinite() || !bearing.isFinite())
            return latitude to longitude
        val bearingRad = Math.toRadians(bearing)
        val latitudeDelta = cos(bearingRad) * lookAheadMeters / 111_320.0
        val cosine = cos(Math.toRadians(latitude)).coerceAtLeast(0.01)
        val longitudeDelta = sin(bearingRad) * lookAheadMeters / (111_320.0 * cosine)
        return (latitude + latitudeDelta).coerceIn(-90.0,90.0) to
            (longitude + longitudeDelta).coerceIn(-180.0,180.0)
    }

    fun angularDelta(previous: Double, next: Double): Double =
        ((next - previous + 540.0) % 360.0) - 180.0

    fun smoothBearing(previous: Double, next: Double, factor: Double = 0.55): Double {
        val result = (previous + angularDelta(previous,next) * factor.coerceIn(0.0,1.0)) % 360.0
        return if (result < 0) result + 360.0 else result
    }

    fun followZoom(speedKmh: Float): Double = when {
        speedKmh < 25f -> 17.8
        speedKmh < 55f -> 17.35
        else -> 16.95
    }
}
