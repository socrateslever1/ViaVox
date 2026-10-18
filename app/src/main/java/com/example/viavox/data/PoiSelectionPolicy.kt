package com.example.viavox.data

import com.example.viavox.model.PointOfInterest
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure, testable selection policy. A landmark's OSM position is approximate:
 * do not announce a side of the road unless GPS + travel bearing are reliable.
 */
object PoiSelectionPolicy {
    const val MAX_POI_DISTANCE_METERS = 135.0
    const val MAX_GPS_ACCURACY_METERS = 40.0f
    private const val HEADING_MIN_SPEED_MPS = 2.5f

    fun choose(
        pois: List<PointOfInterest>,
        latitude: Double,
        longitude: Double,
        gpsAccuracyMeters: Float,
        speedMps: Float,
        bearingDegrees: Float?,
        excludedIds: Set<String> = emptySet()
    ): PointOfInterest? {
        if (!gpsAccuracyMeters.isFinite() ||
            gpsAccuracyMeters > MAX_GPS_ACCURACY_METERS ||
            gpsAccuracyMeters <= 0f
        ) return null

        val trustworthyHeading = bearingDegrees?.takeIf {
            speedMps >= HEADING_MIN_SPEED_MPS && it.isFinite()
        }
        return pois.asSequence()
            .filter { it.id !in excludedIds }
            .map { it to it.distanceTo(latitude, longitude) }
            .filter { (_, distance) -> distance <= MAX_POI_DISTANCE_METERS }
            .filter { (poi, _) ->
                if (trustworthyHeading == null) true
                else {
                    val bearingToPoi = absoluteBearing(latitude, longitude, poi.latitude, poi.longitude)
                    // Don't announce a building already far behind the user.
                    val delta = angularDifference(bearingToPoi, trustworthyHeading.toDouble())
                    abs(delta) <= 125.0
                }
            }
            .minByOrNull { (_, distance) -> distance }
            ?.first
    }

    fun angularDifference(target: Double, heading: Double): Double =
        ((target - heading + 540.0) % 360.0) - 180.0

    fun absoluteBearing(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val a = Math.toRadians(aLat)
        val b = Math.toRadians(bLat)
        val longitudeDifference = Math.toRadians(bLon - aLon)
        val y = sin(longitudeDifference) * cos(b)
        val x = cos(a) * sin(b) - sin(a) * cos(b) * cos(longitudeDifference)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}
