package com.example.viavox.ui.components

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tan

/** Pure, Android-independent map gesture geometry, matching the canvas projection.
 *
 * User interactions move MAP CONTENT with the fingers:
 * - drag right => map content moves right (camera travels west);
 * - fingers apart => zoom in while keeping their focal map point pinned;
 * - clockwise twist => map content rotates clockwise.
 */
data class MapGestureCamera(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
    val bearing: Double
)

object MapGestureMath {
    private const val TILE_SIZE = 256.0
    private const val MERCATOR_MAX_LAT = 85.05112878

    private fun longitudeToWorld(lon: Double, worldSize: Double): Double =
        (lon + 180.0) / 360.0 * worldSize

    private fun latitudeToWorld(lat: Double, worldSize: Double): Double {
        val rad = lat.coerceIn(-MERCATOR_MAX_LAT, MERCATOR_MAX_LAT) * PI / 180.0
        return (0.5 - ln(tan(PI / 4.0 + rad / 2.0)) / (2.0 * PI)) * worldSize
    }

    private fun worldToLongitude(worldX: Double, worldSize: Double): Double =
        worldX / worldSize * 360.0 - 180.0

    private fun worldToLatitude(worldY: Double, worldSize: Double): Double =
        atan(sinh(PI - 2.0 * PI * worldY / worldSize)) * 180.0 / PI

    private fun normalizeLongitude(lon: Double): Double =
        ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

    private fun normalizeBearing(bearing: Double): Double =
        ((bearing % 360.0) + 360.0) % 360.0

    /** Point of the earth currently under a given screen pixel. */
    fun atScreen(camera: MapGestureCamera, x: Double, y: Double,
                 width: Double, height: Double): Pair<Double, Double> {
        val worldSize = TILE_SIZE * 2.0.pow(camera.zoom)
        val screenX = x - width / 2.0
        val screenY = y - height / 2.0
        val angle = camera.bearing * PI / 180.0
        // Inverse of nativeCanvas.rotate(-cameraBearing).
        val worldDx = screenX * cos(angle) - screenY * sin(angle)
        val worldDy = screenX * sin(angle) + screenY * cos(angle)
        val lon = worldToLongitude(
            longitudeToWorld(camera.longitude, worldSize) + worldDx, worldSize)
        val lat = worldToLatitude(
            latitudeToWorld(camera.latitude, worldSize) + worldDy, worldSize)
        return lat.coerceIn(-MERCATOR_MAX_LAT, MERCATOR_MAX_LAT) to
            normalizeLongitude(lon)
    }

    /** Screen location of a map point. Useful for proving focal-point stability. */
    fun toScreen(camera: MapGestureCamera, latitude: Double, longitude: Double,
                 width: Double, height: Double): Pair<Double, Double> {
        val worldSize = TILE_SIZE * 2.0.pow(camera.zoom)
        val dx = (longitudeToWorld(longitude, worldSize) -
            longitudeToWorld(camera.longitude, worldSize))
        val dy = (latitudeToWorld(latitude, worldSize) -
            latitudeToWorld(camera.latitude, worldSize))
        val angle = -camera.bearing * PI / 180.0
        val sx = dx * cos(angle) - dy * sin(angle)
        val sy = dx * sin(angle) + dy * cos(angle)
        return width / 2.0 + sx to height / 2.0 + sy
    }

    /**
     * Compose centroid is the CURRENT gesture centroid, pan = current - previous.
     * Freeze the geographic anchor under the previous centroid, then solve the
     * new center using current centroid, new zoom and new rotation.
     */
    fun transform(
        old: MapGestureCamera,
        width: Double, height: Double,
        centroidX: Double, centroidY: Double,
        panX: Double, panY: Double,
        zoomFactor: Double, rotationDegrees: Double,
        minimumZoom: Double, maximumZoom: Double
    ): MapGestureCamera {
        if (!width.isFinite() || !height.isFinite() || width <= 0.0 || height <= 0.0 ||
            !centroidX.isFinite() || !centroidY.isFinite() ||
            !panX.isFinite() || !panY.isFinite() ||
            !zoomFactor.isFinite() || zoomFactor <= 0.0 ||
            !rotationDegrees.isFinite()) return old

        val (anchorLatitude, anchorLongitude) = atScreen(
            old, centroidX - panX, centroidY - panY, width, height
        )
        val newZoom = (old.zoom + ln(zoomFactor) / ln(2.0))
            .coerceIn(minimumZoom, maximumZoom)
        // Gesture angle is physical content rotation; map bearing rotates
        // the canvas by the opposite angle, so SUBTRACT it.
        val newBearing = normalizeBearing(old.bearing - rotationDegrees)

        val worldSize = TILE_SIZE * 2.0.pow(newZoom)
        val angle = newBearing * PI / 180.0
        val sx = centroidX - width / 2.0
        val sy = centroidY - height / 2.0
        val worldDx = sx * cos(angle) - sy * sin(angle)
        val worldDy = sx * sin(angle) + sy * cos(angle)
        val centerX = longitudeToWorld(anchorLongitude, worldSize) - worldDx
        val centerY = latitudeToWorld(anchorLatitude, worldSize) - worldDy

        return MapGestureCamera(
            latitude = worldToLatitude(centerY, worldSize)
                .coerceIn(-MERCATOR_MAX_LAT, MERCATOR_MAX_LAT),
            longitude = normalizeLongitude(worldToLongitude(centerX, worldSize)),
            zoom = newZoom,
            bearing = newBearing
        )
    }
}
