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
 *
 * Distances are evaluated per street NAME (a street is many OSM ways), and
 * the recent GPS trail is blended in: the road being driven stays close to
 * every recent fix, a crossing or neighbouring street does not.
 */
object RoadMapMatcher {
    private const val METERS_PER_DEGREE = 111_195.0
    /** Good fixes may match alone; weaker ones need a moving trail. */
    const val MAX_SINGLE_FIX_ACCURACY = 12f
    const val MAX_TRAIL_ACCURACY = 20f
    private const val TRAIL_DISTANCE_CAP = 40.0
    private val TRAIL_WEIGHTS = doubleArrayOf(0.5, 0.3, 0.2)

    private class NameScore(val name: String, val cityCode: Int) {
        var distanceNow = Double.POSITIVE_INFINITY
        var angleNow = 180.0
        val trail = DoubleArray(2) { Double.POSITIVE_INFINITY }
        var score = Double.POSITIVE_INFINITY
    }

    /** Distance (m) and undirected angle difference to the nearest segment. */
    private fun nearest(
        line: IndexedStreetLine, lat: Double, lon: Double, bearing: Double
    ): Pair<Double, Double> {
        val longitudeScale = METERS_PER_DEGREE * cos(Math.toRadians(lat))
        var nearestDistance = Double.POSITIVE_INFINITY
        var nearestAngle = 180.0
        for (i in 0 until line.latLon.size - 1) {
            val a = line.latLon[i]
            val b = line.latLon[i + 1]
            val ax = (a.second - lon) * longitudeScale
            val ay = (a.first - lat) * METERS_PER_DEGREE
            val bx = (b.second - lon) * longitudeScale
            val by = (b.first - lat) * METERS_PER_DEGREE
            val dx = bx - ax
            val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            if (lengthSquared < 0.04) continue
            val t = (-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0)
            val distance = hypot(ax + dx * t, ay + dy * t)
            if (distance < nearestDistance) {
                nearestDistance = distance
                val heading = (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
                val diff = abs(((bearing - heading + 540.0) % 360.0) - 180.0)
                nearestAngle = minOf(diff, 180.0 - diff)
            }
        }
        return nearestDistance to nearestAngle
    }

    /** Previous fixes usable as a trail: recent, moving, and not a GPS jump. */
    fun usableTrail(fix: GeoFix, previous: List<GeoFix>): List<GeoFix> {
        val result = ArrayList<GeoFix>(2)
        var reference = fix
        for (older in previous.sortedByDescending { it.timestampMs }) {
            if (result.size == 2) break
            val age = fix.timestampMs - older.timestampMs
            if (age <= 0L || age > 9_000L) continue
            if (!older.accuracyMeters.isFinite() || older.accuracyMeters <= 0f ||
                older.accuracyMeters > MAX_TRAIL_ACCURACY) continue
            val step = RoadDetectionEngine.calculateMeters(
                reference.latitude, reference.longitude, older.latitude, older.longitude)
            if (step < 3.0 || step > 120.0) continue
            result.add(older)
            reference = older
        }
        return result
    }

    fun choose(
        fix: GeoFix, lines: List<IndexedStreetLine>,
        previousFixes: List<GeoFix> = emptyList()
    ): ConfidentStreet? {
        if (lines.isEmpty() || !fix.latitude.isFinite() || !fix.longitude.isFinite() ||
            !fix.accuracyMeters.isFinite() || fix.accuracyMeters <= 0f ||
            fix.accuracyMeters > MAX_TRAIL_ACCURACY) return null
        // Only treat sensor heading as road alignment while moving.
        val headingUseful = fix.hasBearing && fix.speedMps >= 2.0f
        val trail = usableTrail(fix, previousFixes)
        // A weak fix alone is never enough: it needs heading and a trail.
        if (fix.accuracyMeters > MAX_SINGLE_FIX_ACCURACY &&
            (!headingUseful || trail.isEmpty())) return null

        val byName = LinkedHashMap<String, NameScore>()
        for (line in lines) {
            if (line.latLon.size < 2 || line.name.isBlank()) continue
            val key = RoadDetectionEngine.normalizeForComparison(line.name)
            if (key.isEmpty()) continue
            val entry = byName.getOrPut(key) { NameScore(line.name, line.cityCode) }
            val (distance, angle) = nearest(
                line, fix.latitude, fix.longitude, fix.bearing.toDouble())
            if (distance.isFinite() && distance <= 30.0 &&
                !(headingUseful && angle > 55.0) && distance < entry.distanceNow) {
                entry.distanceNow = distance
                entry.angleNow = angle
            }
            trail.forEachIndexed { index, older ->
                val d = nearest(line, older.latitude, older.longitude, 0.0).first
                if (d < entry.trail[index]) entry.trail[index] = d
            }
        }
        val allowedDistance = max(4.5, fix.accuracyMeters.toDouble() + 1.5)
        val requiredGap = max(7.0, fix.accuracyMeters.toDouble() * 0.85)
        // A street farther than this can neither be the answer nor make a
        // close one ambiguous; dropping it stops the street just left behind
        // from delaying the one being entered.
        val scored = byName.values.filter {
            it.distanceNow <= allowedDistance + requiredGap
        }
        for (entry in scored) {
            var weighted = TRAIL_WEIGHTS[0] * entry.distanceNow
            var weights = TRAIL_WEIGHTS[0]
            for (index in trail.indices) {
                weighted += TRAIL_WEIGHTS[index + 1] *
                    entry.trail[index].coerceAtMost(TRAIL_DISTANCE_CAP)
                weights += TRAIL_WEIGHTS[index + 1]
            }
            entry.score = weighted / weights +
                if (headingUseful) entry.angleNow / 10.0 else 0.0
        }
        val ranked = scored.sortedBy { it.score }
        val best = ranked.firstOrNull() ?: return null
        if (best.distanceNow > allowedDistance) return null
        val other = ranked.getOrNull(1)
        val gap = (other?.score ?: Double.POSITIVE_INFINITY) - best.score
        if (gap < requiredGap) return null
        return ConfidentStreet(best.name, best.cityCode, best.distanceNow, gap)
    }

    /**
     * Normalised names of mapped streets plausibly under this fix, used to
     * veto an online geocoder answer naming a street that is not here.
     * Null means the local map has no coverage around the fix.
     */
    fun namesNear(fix: GeoFix, lines: List<IndexedStreetLine>): Set<String>? {
        if (!fix.latitude.isFinite() || !fix.longitude.isFinite()) return null
        val limit = max(18.0, fix.accuracyMeters.toDouble() + 8.0)
        var covered = false
        val names = HashSet<String>()
        for (line in lines) {
            if (line.latLon.size < 2 || line.name.isBlank()) continue
            val distance = nearest(line, fix.latitude, fix.longitude, 0.0).first
            if (distance <= 60.0) covered = true
            if (distance <= limit)
                names.add(RoadDetectionEngine.normalizeForComparison(line.name))
        }
        return if (covered) names else null
    }
}
