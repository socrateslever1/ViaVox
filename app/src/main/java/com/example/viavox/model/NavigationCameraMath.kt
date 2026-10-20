package com.example.viavox.model

import kotlin.math.*

/**
 * Camera follows a point ahead of the vehicle. Rotating the map so the vehicle
 * points toward the top places the puck in the lower portion of the viewport.
 * No simulated GPS direction is introduced when course is unavailable.
 */
object NavigationCameraMath {
    const val GUIDANCE_ZOOM = 18.1
    const val EXPLORE_FOLLOW_ZOOM = 17.9
    const val GUIDE_TILT = 51.0

    fun lookAheadMeters(speedKmh: Float): Double =
        (39.0 + speedKmh.coerceIn(0f, 90f) * 0.42).coerceIn(39.0, 77.0)

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

    /** Follow the vehicle at street level; only fast roads widen the field of view. */
    fun followZoom(speedKmh: Float): Double = when {
        speedKmh < 20f -> 18.35
        speedKmh < 48f -> 18.05
        speedKmh < 80f -> 17.65
        else -> 17.3
    }

    /** When leaving an overview, return to the street rather than staying at city zoom. */
    fun closeAfterPreview(currentZoom: Double, previousWasNavigating: Boolean): Double =
        if (previousWasNavigating || currentZoom < 16.8) EXPLORE_FOLLOW_ZOOM
        else currentZoom
}
