package com.example.viavox.ui.components

import kotlin.math.abs
import kotlin.math.ln

/**
 * Screen-space Compose rotation uses the opposite sign from the map camera:
 * map drawing rotates canvas by -cameraBearing. Thus a clockwise two-finger
 * rotation must DECREASE the camera bearing to rotate the visible map clockwise.
 *
 * Kept independent of Compose to make the physical gesture contract testable.
 */
object MapGesturePolicy {
    fun isManualTransform(
        panX: Float,
        panY: Float,
        zoomFactor: Float,
        rotationDegrees: Float
    ): Boolean =
        abs(panX) > 1.5f ||
            abs(panY) > 1.5f ||
            abs(zoomFactor - 1f) > 0.01f ||
            abs(rotationDegrees) > 0.2f

    fun rotateCameraBearing(
        currentBearing: Float,
        rotationGestureDegrees: Float
    ): Float {
        if (!currentBearing.isFinite() || !rotationGestureDegrees.isFinite())
            return currentBearing
        return ((currentBearing - rotationGestureDegrees) % 360f + 360f) % 360f
    }

    fun zoomLevel(currentZoom: Double, zoomFactor: Float, minZoom: Double, maxZoom: Double): Double {
        if (!zoomFactor.isFinite() || zoomFactor <= 0f) return currentZoom
        return (currentZoom + ln(zoomFactor.toDouble()) / ln(2.0)).coerceIn(minZoom, maxZoom)
    }
}
