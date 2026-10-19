package com.example.viavox.model

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

data class ManeuverProgress(
    val nextStepIndex: Int,
    val instruction: String,
    val metersToStep: Double,
    val distanceOffRouteMeters: Double,
    val traveledMeters: Double,
    val metersRemaining: Double
)

/**
 * Navigation progress comes from monotonically advancing along route geometry,
 * not the geographically nearest maneuver (which fails on roundabouts/loops).
 * Geometry and step positions are always provided by the routing engine.
 */
class RouteProgressEngine(private val route: NavigationRoute) {
    private val cumulative: DoubleArray = DoubleArray(route.coordinates.size)
    private val maneuverDistances: List<Double>
    private var traveled = -1.0
    private var updatedAt = 0L

    init {
        for (i in 1 until route.coordinates.size) {
            val a = route.coordinates[i - 1]
            val b = route.coordinates[i]
            cumulative[i] = cumulative[i - 1] +
                NavigationMath.meters(a.first,a.second,b.first,b.second)
        }
        var lastManeuverS = 0.0
        maneuverDistances = route.steps.map { step ->
            val near = nearestProjection(step.latitude,step.longitude)
            val result = max(lastManeuverS,near.routeMeters)
            lastManeuverS = result
            result
        }
    }

    fun update(latitude: Double, longitude: Double, speedKmh: Float,
               timestampMs: Long = System.currentTimeMillis()): ManeuverProgress? {
        if (!latitude.isFinite() || !longitude.isFinite() || route.coordinates.size < 2) return null
        val projected = nearestProjection(latitude,longitude)
        if (traveled < 0) {
            traveled = projected.routeMeters
        } else {
            val seconds = ((timestampMs - updatedAt).coerceAtLeast(0L) / 1000.0).coerceAtMost(1800.0)
            // Avoid jumping to a repeated crossing hundreds of meters later.
            // GPS can reacquire after a gap, so allow motion over elapsed time.
            val allowedAdvance = max(130.0,(speedKmh.coerceAtLeast(0f) / 3.6 * seconds * 1.9) + 65.0)
            if (projected.routeMeters >= traveled - 30.0 &&
                projected.routeMeters <= traveled + allowedAdvance &&
                projected.crossTrackMeters < 85.0
            ) {
                traveled = max(traveled,projected.routeMeters)
            }
        }
        updatedAt = timestampMs
        val next = route.steps.indices.drop(1).firstOrNull { i ->
            maneuverDistances[i] >= traveled - 15.0
        } ?: route.steps.indices.lastOrNull()
        val maneuver = next?.let { route.steps[it] }
        val distance = next?.let { (maneuverDistances[it] - traveled).coerceAtLeast(0.0) }
            ?: (cumulative.last() - traveled).coerceAtLeast(0.0)
        return ManeuverProgress(
            nextStepIndex = next ?: -1,
            instruction = maneuver?.instruction ?: "Continue",
            metersToStep = distance,
            distanceOffRouteMeters = projected.crossTrackMeters,
            traveledMeters = traveled,
            metersRemaining = (cumulative.last() - traveled).coerceAtLeast(0.0)
        )
    }

    private data class Projection(val routeMeters: Double, val crossTrackMeters: Double)

    private fun nearestProjection(latitude: Double, longitude: Double): Projection {
        var nearest = Projection(0.0,Double.POSITIVE_INFINITY)
        for (i in 0 until route.coordinates.size - 1) {
            val a = route.coordinates[i]
            val b = route.coordinates[i + 1]
            val lonScale = cos(Math.toRadians(latitude))
            val dx = (b.second - a.second) * lonScale
            val dy = b.first - a.first
            val lengthSq = dx*dx + dy*dy
            val t = if (lengthSq < 1e-15) 0.0 else
                (((longitude - a.second) * lonScale * dx + (latitude - a.first)*dy)
                    / lengthSq).coerceIn(0.0,1.0)
            val footLat = a.first + t * (b.first - a.first)
            val footLon = a.second + t * (b.second - a.second)
            val across = NavigationMath.meters(latitude,longitude,footLat,footLon)
            if (across < nearest.crossTrackMeters) {
                nearest = Projection(
                    cumulative[i] + (cumulative[i+1] - cumulative[i]) * t,
                    across
                )
            }
        }
        return nearest
    }
}
