package com.example.viavox.service

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos

data class TerritoryMatch(
    val zone: String? = null,
    val neighbourhood: String? = null,
    val cicom: String? = null,
    val cpa: String? = null
) {
    fun isEmpty() = listOf(zone, neighbourhood, cicom, cpa).all { it.isNullOrBlank() }
}

/**
 * GeoJSON coordinates are [longitude,latitude] (WGS84). The bundled layers
 * allow an immediate GPS polygon lookup without internet or reverse geocoding.
 * This is a locally bundled map snapshot, not live official dispatch data.
 */
class TerritoryGeojsonResolver private constructor(
    private val neighbourhoods: List<Area>,
    private val policeAreas: List<Area>
) {
    data class Point(val lon: Double, val lat: Double)

    data class Area(
        val name: String,
        val zone: String?,
        val rings: List<List<Point>>,
        val minLon: Double,
        val maxLon: Double,
        val minLat: Double,
        val maxLat: Double
    ) {
        private fun contains(lon: Double, lat: Double): Boolean {
            if (lon !in minLon..maxLon || lat !in minLat..maxLat) return false
            if (!insideRing(rings.first(), lon, lat)) return false
            return rings.drop(1).none { insideRing(it, lon, lat) }
        }

        fun hasReliableFix(lon: Double, lat: Double, gpsAccuracyMeters: Float): Boolean {
            if (!gpsAccuracyMeters.isFinite() || gpsAccuracyMeters <= 0f ||
                gpsAccuracyMeters > 35f || !contains(lon, lat)) return false
            // Do not announce entry from an uncertainty circle that overlaps
            // the border of a neighbourhood or CICOM sector.
            val radius = gpsAccuracyMeters.toDouble().coerceAtLeast(4.0)
            val dy = radius / 111_195.0
            val dx = radius / (111_195.0 *
                cos(Math.toRadians(lat)).coerceAtLeast(0.15))
            return contains(lon + dx, lat) && contains(lon - dx, lat) &&
                contains(lon, lat + dy) && contains(lon, lat - dy)
        }

        companion object {
            private fun insideRing(ring: List<Point>, lon: Double, lat: Double): Boolean {
                var inside = false
                var last = ring.size - 1
                for (next in ring.indices) {
                    val a = ring[next]
                    val b = ring[last]
                    if ((a.lat > lat) != (b.lat > lat) &&
                        lon < (b.lon - a.lon) * (lat - a.lat) /
                            (b.lat - a.lat) + a.lon) inside = !inside
                    last = next
                }
                return inside
            }
        }
    }

    /** May be called on each accepted GPS fix. Geometry was parsed once on IO. */
    fun resolve(lat: Double, lon: Double, accuracyMeters: Float): TerritoryMatch? {
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 ||
            lon !in -180.0..180.0) return null
        val bairro = neighbourhoods.firstOrNull { it.hasReliableFix(lon, lat, accuracyMeters) }
        val cicomArea = policeAreas.firstOrNull { it.hasReliableFix(lon, lat, accuracyMeters) }
        val police = cicomArea?.let {
            PoliceJurisdictionSpeechResolver.resolve(it.name, "Manaus")
        }
        val match = TerritoryMatch(
            zone = bairro?.zone,
            neighbourhood = bairro?.name,
            cicom = police?.cicom,
            cpa = police?.cpa
        )
        return match.takeUnless { it.isEmpty() }
    }

    companion object {
        fun parse(neighbourhoods: String, policeAreas: String): TerritoryGeojsonResolver =
            TerritoryGeojsonResolver(
                readAreas(neighbourhoods),
                readAreas(policeAreas)
            )

        private fun readAreas(raw: String): List<Area> {
            val result = mutableListOf<Area>()
            val features = JSONObject(raw).optJSONArray("features") ?: return result
            for (i in 0 until features.length()) {
                val feature = features.optJSONObject(i) ?: continue
                val props = feature.optJSONObject("properties") ?: continue
                val geometry = feature.optJSONObject("geometry") ?: continue
                if (geometry.optString("type") != "Polygon") continue
                val name = props.optString("name").trim()
                if (name.isBlank()) continue
                val coordinates = geometry.optJSONArray("coordinates") ?: continue
                val rings = (0 until coordinates.length()).mapNotNull { ringIndex ->
                    val ring = coordinates.optJSONArray(ringIndex) ?: return@mapNotNull null
                    val points = (0 until ring.length()).mapNotNull { index ->
                        val pair = ring.optJSONArray(index) ?: return@mapNotNull null
                        if (pair.length() < 2) return@mapNotNull null
                        val lon = pair.optDouble(0, Double.NaN)
                        val lat = pair.optDouble(1, Double.NaN)
                        if (!lon.isFinite() || !lat.isFinite()) null else Point(lon, lat)
                    }
                    points.takeIf { it.size >= 3 }
                }
                if (rings.isEmpty()) continue
                val all = rings.flatten()
                result += Area(
                    name = name,
                    zone = props.optString("zona").trim().takeIf { it.isNotBlank() },
                    rings = rings,
                    minLon = all.minOf { it.lon }, maxLon = all.maxOf { it.lon },
                    minLat = all.minOf { it.lat }, maxLat = all.maxOf { it.lat }
                )
            }
            return result
        }
    }
}
