package com.example.viavox.data

import com.example.viavox.model.PoiCategory
import com.example.viavox.model.PointOfInterest
import org.json.JSONObject
import java.util.Locale

/**
 * Overpass elements can be nodes, ways or relations. Ways/relations have a
 * centre only if the query uses "out center", and that position is approximate.
 */
object OverpassPoiParser {
    fun parse(response: String): List<PointOfInterest> {
        val elements = JSONObject(response).optJSONArray("elements") ?: return emptyList()
        val candidates = mutableListOf<PointOfInterest>()
        for (index in 0 until elements.length()) {
            val element = elements.optJSONObject(index) ?: continue
            val tags = element.optJSONObject("tags") ?: continue
            val name = tags.optString("name").trim()
            if (name.isBlank()) continue
            val category = categoryFor(tags) ?: continue
            val type = element.optString("type")
            if (type !in listOf("node", "way", "relation")) continue
            val id = element.optLong("id")
            if (id <= 0L) continue

            val point = if (type == "node") element else element.optJSONObject("center")
            val lat = point?.optDouble("lat", Double.NaN) ?: Double.NaN
            val lon = point?.optDouble("lon", Double.NaN) ?: Double.NaN
            if (!lat.isFinite() || !lon.isFinite() ||
                lat !in -90.0..90.0 || lon !in -180.0..180.0
            ) continue

            val poi = PointOfInterest(
                id = "osm_${type}_${id}",
                name = name,
                category = category,
                latitude = lat,
                longitude = lon
            )

            // Some landmarks are mapped both as a POI node and as a building.
            // Do not announce the same establishment twice in one area.
            val samePlace = candidates.any {
                it.category == poi.category &&
                    it.name.trim().lowercase(Locale.ROOT) == name.lowercase(Locale.ROOT) &&
                    it.distanceTo(poi.latitude, poi.longitude) <= 55.0
            }
            if (!samePlace) candidates += poi
        }
        return candidates
    }

    private fun categoryFor(tags: JSONObject): PoiCategory? {
        val amenity = tags.optString("amenity")
        val leisure = tags.optString("leisure")
        val historic = tags.optString("historic")
        val tourism = tags.optString("tourism")
        val building = tags.optString("building")

        return when {
            amenity in setOf("hospital", "clinic", "doctors", "health_post") ->
                PoiCategory.HEALTH
            amenity in setOf("school", "university", "college", "kindergarten") ->
                PoiCategory.EDUCATION
            amenity in setOf("fuel") -> PoiCategory.FUEL
            amenity in setOf("townhall", "police", "fire_station", "courthouse", "library", "bus_station") ||
                historic in setOf("monument", "memorial") ||
                tourism in setOf("museum", "attraction") ||
                building in setOf("civic", "public") -> PoiCategory.PUBLIC
            leisure in setOf("park", "garden", "playground", "pitch") -> PoiCategory.PARK
            else -> null
        }
    }
}
