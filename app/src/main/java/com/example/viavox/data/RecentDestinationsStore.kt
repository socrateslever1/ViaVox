package com.example.viavox.data

import android.content.Context
import com.example.viavox.model.MapPlace
import com.example.viavox.model.NavigationMath
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local storage (SharedPreferences JSON, analogous to web localStorage) that persists
 * the recent address history ("locais visitados" / últimos destinos buscados) and
 * pinned favorite addresses ("favoritos") for quick access in the search dropdown.
 */
class RecentDestinationsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun load(): List<MapPlace> {
        val raw = prefs.getString(KEY_RECENT_JSON, null)
            ?: prefs.getString(KEY_VISITED_LOCAL_STORAGE, null)
            ?: return emptyList()
        return decodeJson(raw, MAX_RECENT_DESTINATIONS)
    }

    fun record(place: MapPlace): List<MapPlace> {
        val current = load()
        val updated = pushRecent(current, place, MAX_RECENT_DESTINATIONS)
        val encoded = encodeJson(updated, MAX_RECENT_DESTINATIONS)
        prefs.edit()
            .putString(KEY_RECENT_JSON, encoded)
            .putString(KEY_VISITED_LOCAL_STORAGE, encoded)
            .apply()
        return updated
    }

    fun remove(place: MapPlace): List<MapPlace> {
        val current = load()
        val updated = current.filterNot { isSameDestination(it, place) }
        val encoded = encodeJson(updated, MAX_RECENT_DESTINATIONS)
        prefs.edit()
            .putString(KEY_RECENT_JSON, encoded)
            .putString(KEY_VISITED_LOCAL_STORAGE, encoded)
            .apply()
        return updated
    }

    fun clear(): List<MapPlace> {
        prefs.edit()
            .remove(KEY_RECENT_JSON)
            .remove(KEY_VISITED_LOCAL_STORAGE)
            .apply()
        return emptyList()
    }

    fun loadFavorites(): List<MapPlace> {
        val raw = prefs.getString(KEY_FAVORITES_JSON, null)
            ?: prefs.getString(KEY_FAVORITES_LOCAL_STORAGE, null)
            ?: return emptyList()
        return decodeJson(raw, MAX_FAVORITE_DESTINATIONS)
    }

    fun saveFavorites(places: List<MapPlace>): List<MapPlace> {
        val normalized = places.take(MAX_FAVORITE_DESTINATIONS)
        val encoded = encodeJson(normalized, MAX_FAVORITE_DESTINATIONS)
        prefs.edit()
            .putString(KEY_FAVORITES_JSON, encoded)
            .putString(KEY_FAVORITES_LOCAL_STORAGE, encoded)
            .apply()
        return normalized
    }

    fun toggleFavorite(place: MapPlace): List<MapPlace> {
        val current = loadFavorites()
        val updated = toggleFavoriteInList(current, place, MAX_FAVORITE_DESTINATIONS)
        return saveFavorites(updated)
    }

    fun removeFavorite(place: MapPlace): List<MapPlace> {
        val current = loadFavorites()
        val updated = current.filterNot { isSameDestination(it, place) }
        return saveFavorites(updated)
    }

    companion object {
        const val PREFS_NAME = "viavox_recent_destinations"
        const val KEY_RECENT_JSON = "recent_destinations_json"
        const val KEY_VISITED_LOCAL_STORAGE = "localStorage_locais_visitados"
        const val KEY_FAVORITES_JSON = "favorite_destinations_json"
        const val KEY_FAVORITES_LOCAL_STORAGE = "localStorage_favoritos"
        const val MAX_RECENT_DESTINATIONS = 5
        const val MAX_FAVORITE_DESTINATIONS = 25

        fun isSameDestination(a: MapPlace, b: MapPlace): Boolean {
            val sameName = a.name.trim().equals(b.name.trim(), ignoreCase = true)
            val closeCoords = NavigationMath.meters(
                a.latitude, a.longitude,
                b.latitude, b.longitude
            ) < 35.0
            return sameName || closeCoords
        }

        fun isFavoriteInList(favorites: List<MapPlace>, place: MapPlace): Boolean =
            favorites.any { isSameDestination(it, place) }

        fun toggleFavoriteInList(
            existing: List<MapPlace>,
            place: MapPlace,
            maxItems: Int = MAX_FAVORITE_DESTINATIONS
        ): List<MapPlace> {
            val cleanedName = place.name.trim()
            if (cleanedName.isBlank()) return existing.take(maxItems)
            val normalized = place.copy(name = cleanedName)
            return if (isFavoriteInList(existing, normalized)) {
                existing.filterNot { isSameDestination(it, normalized) }
            } else {
                (listOf(normalized) + existing).take(maxItems)
            }
        }

        fun pushRecent(
            existing: List<MapPlace>,
            newPlace: MapPlace,
            maxItems: Int = MAX_RECENT_DESTINATIONS
        ): List<MapPlace> {
            val cleanedName = newPlace.name.trim()
            if (cleanedName.isBlank()) return existing.take(maxItems)
            val normalizedNew = newPlace.copy(name = cleanedName)
            val filtered = existing.filterNot { isSameDestination(it, normalizedNew) }
            return (listOf(normalizedNew) + filtered).take(maxItems)
        }

        fun encodeJson(
            places: List<MapPlace>,
            maxItems: Int = MAX_RECENT_DESTINATIONS
        ): String {
            val array = JSONArray()
            val now = System.currentTimeMillis()
            places.take(maxItems).forEach { place ->
                array.put(
                    JSONObject().apply {
                        put("name", place.name)
                        put("latitude", place.latitude)
                        put("longitude", place.longitude)
                        put("subtitle", place.subtitle)
                        put("visitedAt", now)
                    }
                )
            }
            return array.toString()
        }

        fun decodeJson(
            raw: String,
            maxItems: Int = MAX_RECENT_DESTINATIONS
        ): List<MapPlace> {
            if (raw.isBlank()) return emptyList()
            return try {
                val array = JSONArray(raw)
                val out = mutableListOf<MapPlace>()
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val name = obj.optString("name", "").trim()
                    val lat = obj.optDouble("latitude", Double.NaN)
                    val lon = obj.optDouble("longitude", Double.NaN)
                    val subtitle = obj.optString("subtitle", "")
                    if (name.isNotBlank() && !lat.isNaN() && !lon.isNaN()) {
                        out.add(MapPlace(name, lat, lon, subtitle))
                    }
                }
                out.take(maxItems)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}


