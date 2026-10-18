package com.example.viavox.model

import kotlin.math.*

/** A named, real location from geocoding or the user's saved pins. */
data class MapPlace(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val subtitle: String = ""
)

data class RouteStep(
    val latitude: Double,
    val longitude: Double,
    val instruction: String,
    val distanceMeters: Double
)

/** Route geometry is decoded from the routing provider, never a straight line. */
data class NavigationRoute(
    val destination: MapPlace,
    val coordinates: List<Pair<Double, Double>>, // lat, lon
    val distanceMeters: Double,
    val durationSeconds: Double,
    val steps: List<RouteStep>
)

data class SavedTrip(
    val id: Long,
    val startedAt: Long,
    val finishedAt: Long,
    val distanceMeters: Double,
    val points: List<TripPoint>
)

data class TripPoint(
    val latitude: Double,
    val longitude: Double,
    val recordedAt: Long,
    val accuracy: Float
)

data class StoredRoute(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val route: NavigationRoute
)

object NavigationMath {
    fun meters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val r1 = Math.toRadians(aLat)
        val r2 = Math.toRadians(bLat)
        val dy = Math.toRadians(bLat - aLat)
        val dx = Math.toRadians(bLon - aLon)
        val arc = sin(dy / 2).pow(2) + cos(r1) * cos(r2) * sin(dx / 2).pow(2)
        return 6_371_000.0 * 2.0 * atan2(sqrt(arc), sqrt((1.0 - arc).coerceAtLeast(0.0)))
    }

    fun distanceToPolylineMeters(latitude: Double, longitude: Double, points: List<Pair<Double, Double>>): Double {
        if (points.isEmpty()) return Double.POSITIVE_INFINITY
        val latitudeCos = cos(Math.toRadians(latitude))
        fun xy(pair: Pair<Double,Double>): Pair<Double,Double> =
            ((pair.second - longitude) * 111_195 * latitudeCos) to ((pair.first - latitude) * 111_195)
        var best = Double.POSITIVE_INFINITY
        var previous = xy(points.first())
        best = hypot(previous.first, previous.second)
        for (i in 1 until points.size) {
            val next = xy(points[i])
            val dx = next.first - previous.first
            val dy = next.second - previous.second
            val k = (dx * dx + dy * dy)
            val t = if (k < 1e-8) 0.0 else
                (-(previous.first * dx + previous.second * dy) / k).coerceIn(0.0, 1.0)
            val distance = hypot(previous.first + t * dx, previous.second + t * dy)
            if (distance < best) best = distance
            previous = next
        }
        return best
    }

    fun formatDistance(meters: Double): String =
        if (meters >= 1000) String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.1f km", meters / 1000.0)
        else "${meters.toInt()} m"
}
