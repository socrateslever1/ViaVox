package com.example.viavox.service

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/** An actual OSM street polyline, not a street centroid or nearby POI. */
data class IndexedStreetLine(
    val name: String,
    val cityCode: Int,
    val latLon: List<Pair<Double,Double>>
)

data class ConfidentStreet(
    val name: String,
    val cityCode: Int,
    val distanceMeters: Double,
    val separationMeters: Double
)

/**
 * Conservative road map matching. Nearest-point matching alone is unsafe at
 * intersections and parallel streets. Require an accurate fix, proximity to
 * the actual polyline and separation from competing DIFFERENT road names.
 */
object RoadMapMatcher {
    private const val METERS_PER_DEGREE = 111_195.0

    private data class RoadDistance(
        val name: String, val cityCode: Int,
        val distance: Double, val angleDifference: Double, val score: Double
    )

    fun choose(
        fix: GeoFix, lines: List<IndexedStreetLine>
    ): ConfidentStreet? {
        if (lines.isEmpty() || !fix.latitude.isFinite() || !fix.longitude.isFinite() ||
            !fix.accuracyMeters.isFinite() || fix.accuracyMeters <= 0f ||
            fix.accuracyMeters > 12f) return null
        val currentLat = fix.latitude
        val latitudeScale = METERS_PER_DEGREE
        val longitudeScale = latitudeScale * cos(Math.toRadians(currentLat))
        val scored = lines.mapNotNull { line ->
            if (line.latLon.size < 2 || line.name.isBlank()) return@mapNotNull null
            var nearestDistance = Double.POSITIVE_INFINITY
            var nearestAngle = 180.0
            for (i in 0 until line.latLon.size - 1) {
                val a = line.latLon[i]
                val b = line.latLon[i+1]
                val ax = (a.second - fix.longitude) * longitudeScale
                val ay = (a.first - currentLat) * latitudeScale
                val bx = (b.second - fix.longitude) * longitudeScale
                val by = (b.first - currentLat) * latitudeScale
                val dx = bx - ax
                val dy = by - ay
                val lengthSquared = dx*dx + dy*dy
                if (lengthSquared < 0.04) continue
                val t = (-(ax*dx+ay*dy)/lengthSquared).coerceIn(0.0,1.0)
                val distance = hypot(ax+dx*t,ay+dy*t)
                if (distance < nearestDistance) {
                    nearestDistance = distance
                    val heading = (Math.toDegrees(atan2(dx,dy))+360.0)%360.0
                    val diff = abs(((fix.bearing.toDouble()-heading+540.0)%360.0)-180.0)
                    nearestAngle = minOf(diff,180.0-diff)
                }
            }
            if (!nearestDistance.isFinite() || nearestDistance > 30.0) return@mapNotNull null
            // Only treat sensor heading as road alignment while moving.
            val headingUseful = fix.hasBearing && fix.speedMps >= 2.0f
            if (headingUseful && nearestAngle > 55.0) return@mapNotNull null
            val penalty = if (headingUseful) nearestAngle / 10.0 else 0.0
            RoadDistance(line.name,line.cityCode,nearestDistance,nearestAngle,
                nearestDistance+penalty)
        }.sortedBy { it.score }
        val best = scored.firstOrNull() ?: return null
        val allowedDistance = max(4.5,fix.accuracyMeters.toDouble()+1.5)
        if (best.distance > allowedDistance) return null
        val key = RoadDetectionEngine.normalizeForComparison(best.name)
        val other = scored.firstOrNull {
            RoadDetectionEngine.normalizeForComparison(it.name) != key
        }
        val distanceGap = (other?.score ?: Double.POSITIVE_INFINITY) - best.score
        if (distanceGap < max(7.0,fix.accuracyMeters.toDouble()*0.85)) return null
        return ConfidentStreet(best.name,best.cityCode,best.distance,distanceGap)
    }
}
