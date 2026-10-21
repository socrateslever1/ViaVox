package com.example.viavox.data

import android.content.Context
import java.text.Normalizer
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Resolves Manaus neighbourhoods from the bundled local polygon dataset. */
object ManausDistrictResolver {
    data class District(val name: String, val zone: String)

    private data class Point(val longitude: Double, val latitude: Double)
    private data class PolygonDistrict(
        val district: District,
        val ring: List<Point>,
        val minLatitude: Double,
        val maxLatitude: Double,
        val minLongitude: Double,
        val maxLongitude: Double
    )

    @Volatile
    private var polygons: List<PolygonDistrict> = emptyList()

    fun isManausCoordinates(lat: Double, lon: Double): Boolean =
        lat in -3.25..-2.90 && lon in -60.18..-59.80

    @Synchronized
    fun initializeFromAssets(context: Context): Boolean {
        if (polygons.isNotEmpty()) return true
        return runCatching {
            context.assets.open("bairros_manaus.geojson").bufferedReader(Charsets.UTF_8).use {
                loadGeoJson(it.readText())
            }
        }.getOrDefault(false)
    }

    /** Public for deterministic JVM tests and future signed dataset updates. */
    @Synchronized
    fun loadGeoJson(rawGeoJson: String): Boolean {
        val parsed = runCatching {
            val features = JSONObject(rawGeoJson).getJSONArray("features")
            buildList {
                for (index in 0 until features.length()) {
                    val feature = features.getJSONObject(index)
                    val geometry = feature.getJSONObject("geometry")
                    if (!geometry.optString("type").equals("Polygon", ignoreCase = true)) continue
                    val properties = feature.getJSONObject("properties")
                    val name = canonicalName(properties.optString("name"))
                    val zone = properties.optString("zona").trim()
                    val outerRing = geometry.getJSONArray("coordinates").getJSONArray(0)
                    val points = parseRing(outerRing)
                    if (name.isBlank() || zone.isBlank() || points.size < 4) continue
                    add(
                        PolygonDistrict(
                            district = District(name, zone),
                            ring = points,
                            minLatitude = points.minOf(Point::latitude),
                            maxLatitude = points.maxOf(Point::latitude),
                            minLongitude = points.minOf(Point::longitude),
                            maxLongitude = points.maxOf(Point::longitude)
                        )
                    )
                }
            }
        }.getOrElse { return false }
        if (parsed.isEmpty()) return false
        polygons = parsed
        return true
    }

    fun resolveDistrict(lat: Double, lon: Double, displayName: String = ""): District? {
        if (!isManausCoordinates(lat, lon)) return null

        polygons.firstOrNull { candidate ->
            lat in candidate.minLatitude..candidate.maxLatitude &&
                lon in candidate.minLongitude..candidate.maxLongitude &&
                contains(candidate.ring, latitude = lat, longitude = lon)
        }?.let { return it.district }

        if (displayName.isBlank()) return null
        val normalizedDisplay = comparisonKey(displayName)
        return polygons.firstOrNull { candidate ->
            normalizedDisplay.contains(comparisonKey(candidate.district.name))
        }?.district
    }

    private fun parseRing(array: JSONArray): List<Point> = buildList {
        for (index in 0 until array.length()) {
            val coordinate = array.getJSONArray(index)
            if (coordinate.length() >= 2) {
                add(Point(coordinate.getDouble(0), coordinate.getDouble(1)))
            }
        }
    }

    private fun contains(ring: List<Point>, latitude: Double, longitude: Double): Boolean {
        var inside = false
        var previous = ring.last()
        for (current in ring) {
            if (onSegment(previous, current, latitude, longitude)) return true
            val crosses = (current.latitude > latitude) != (previous.latitude > latitude)
            if (crosses) {
                val intersectionLongitude = (previous.longitude - current.longitude) *
                    (latitude - current.latitude) /
                    (previous.latitude - current.latitude) + current.longitude
                if (longitude < intersectionLongitude) inside = !inside
            }
            previous = current
        }
        return inside
    }

    private fun onSegment(a: Point, b: Point, latitude: Double, longitude: Double): Boolean {
        val cross = (longitude - a.longitude) * (b.latitude - a.latitude) -
            (latitude - a.latitude) * (b.longitude - a.longitude)
        if (kotlin.math.abs(cross) > 1e-10) return false
        return longitude in minOf(a.longitude, b.longitude)..maxOf(a.longitude, b.longitude) &&
            latitude in minOf(a.latitude, b.latitude)..maxOf(a.latitude, b.latitude)
    }

    private fun canonicalName(value: String): String {
        val minorWords = setOf("a", "as", "da", "das", "de", "do", "dos", "e")
        return value.trim().lowercase(Locale.forLanguageTag("pt-BR"))
            .split(Regex("\\s+"))
            .mapIndexed { index, word ->
                if (index > 0 && word in minorWords) word
                else word.replaceFirstChar { it.titlecase(Locale.forLanguageTag("pt-BR")) }
            }
            .joinToString(" ")
    }

    private fun comparisonKey(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.forLanguageTag("pt-BR"))
}
