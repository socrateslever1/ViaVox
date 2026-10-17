package com.example.viavox.data

import android.util.Log
import com.example.viavox.model.PoiCategory
import com.example.viavox.model.PointOfInterest
import com.example.viavox.model.ViaVoxConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class PoiDiscoveryService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {

    private val cachedPois = mutableListOf<PointOfInterest>()
    private var lastQueryCenterLat = 0.0
    private var lastQueryCenterLon = 0.0
    private var lastQueryTimestamp = 0L
    private var isQuerying = false

    // Avoid repeating recently announced POIs (key: POI id or clean name, value: announced timestamp)
    private val recentlyAnnouncedPois = ConcurrentHashMap<String, Long>()

    /**
     * Checks if a relevant landmark POI is nearby and ready to be announced.
     */
    suspend fun findNearbyPoi(
        userLat: Double,
        userLon: Double,
        config: ViaVoxConfig
    ): PointOfInterest? {
        // Purge expired announcements (> 15 minutes)
        val now = System.currentTimeMillis()
        val iterator = recentlyAnnouncedPois.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value > 15 * 60 * 1000L) {
                iterator.remove()
            }
        }

        // Check if we need to refresh the local spatial cache
        val distFromLastQuery = if (lastQueryCenterLat != 0.0) {
            calculateDistance(userLat, userLon, lastQueryCenterLat, lastQueryCenterLon)
        } else {
            Double.POSITIVE_INFINITY
        }

        if (distFromLastQuery >= 250.0 || (now - lastQueryTimestamp >= 90_000L && distFromLastQuery >= 50.0)) {
            refreshPoiCache(userLat, userLon)
        }

        // Search within current in-memory cache for close POIs (<= 75 meters)
        val candidate = synchronized(cachedPois) {
            cachedPois.filter { isCategoryEnabled(it.category, config) }
                .map { poi -> Pair(poi, poi.distanceTo(userLat, userLon)) }
                .filter { it.second <= 75.0 }
                .minByOrNull { it.second }
                ?.first
        }

        if (candidate != null) {
            val cleanKey = candidate.name.lowercase().trim()
            val lastAnnounced = recentlyAnnouncedPois[cleanKey] ?: 0L
            if (now - lastAnnounced > 15 * 60 * 1000L) {
                recentlyAnnouncedPois[cleanKey] = now
                return candidate
            }
        }

        return null
    }

    private fun isCategoryEnabled(category: PoiCategory, config: ViaVoxConfig): Boolean {
        return when (category) {
            PoiCategory.HEALTH -> config.enableHealthPoi
            PoiCategory.EDUCATION -> config.enableEducationPoi
            PoiCategory.PARK -> config.enableParksPoi
            PoiCategory.FUEL -> config.enableFuelPoi
            PoiCategory.PUBLIC -> config.enablePublicPoi
        }
    }

    private suspend fun refreshPoiCache(lat: Double, lon: Double) = withContext(Dispatchers.IO) {
        if (isQuerying) return@withContext
        val now = System.currentTimeMillis()
        if (now - lastQueryTimestamp < 25_000L) return@withContext // Minimum cooldown between queries

        isQuerying = true
        try {
            val formattedLat = String.format(Locale.US, "%.6f", lat)
            val formattedLon = String.format(Locale.US, "%.6f", lon)

            // Overpass QL query: search within 300m radius around vehicle
            val overpassQuery = """
                [out:json][timeout:8];
                (
                  node["amenity"~"hospital|clinic|school|university|college|bus_station|townhall|fuel"]["name"](around:300,$formattedLat,$formattedLon);
                  node["leisure"~"park|pitch"]["name"](around:300,$formattedLat,$formattedLon);
                  node["historic"~"monument|memorial"]["name"](around:300,$formattedLat,$formattedLon);
                  node["bridge"]["name"](around:300,$formattedLat,$formattedLon);
                );
                out 15;
            """.trimIndent()

            val body = overpassQuery.toRequestBody("text/plain".toMediaType())
            val request = Request.Builder()
                .url("https://overpass-api.de/api/interpreter")
                .post(body)
                .header("User-Agent", "ViaVox-Android/0.2.0 (viavox.android.app)")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w("PoiDiscoveryService", "Overpass returned code ${response.code}")
                return@withContext
            }

            val responseBody = response.body?.string() ?: return@withContext
            val json = JSONObject(responseBody)
            val elements = json.optJSONArray("elements") ?: return@withContext

            val newPois = mutableListOf<PointOfInterest>()
            for (i in 0 until elements.length()) {
                val element = elements.optJSONObject(i) ?: continue
                val tags = element.optJSONObject("tags") ?: continue
                val name = tags.optString("name").trim()
                if (name.isBlank()) continue

                val poiLat = element.optDouble("lat", 0.0)
                val poiLon = element.optDouble("lon", 0.0)
                if (poiLat == 0.0 || poiLon == 0.0) continue

                val category = determineCategory(tags) ?: continue
                val id = "osm_${element.optLong("id")}"

                newPois.add(
                    PointOfInterest(
                        id = id,
                        name = name,
                        category = category,
                        latitude = poiLat,
                        longitude = poiLon
                    )
                )
            }

            synchronized(cachedPois) {
                cachedPois.clear()
                cachedPois.addAll(newPois)
            }

            lastQueryCenterLat = lat
            lastQueryCenterLon = lon
            lastQueryTimestamp = now
            Log.d("PoiDiscoveryService", "Refreshed ${newPois.size} POIs near $formattedLat,$formattedLon")
        } catch (e: Exception) {
            Log.w("PoiDiscoveryService", "Failed to fetch POIs from Overpass: ${e.message}")
        } finally {
            isQuerying = false
        }
    }

    private fun determineCategory(tags: JSONObject): PoiCategory? {
        val amenity = tags.optString("amenity")
        val leisure = tags.optString("leisure")
        val historic = tags.optString("historic")
        val bridge = tags.optString("bridge")

        return when {
            amenity in listOf("hospital", "clinic") -> PoiCategory.HEALTH
            amenity in listOf("school", "university", "college") -> PoiCategory.EDUCATION
            amenity == "fuel" -> PoiCategory.FUEL
            amenity in listOf("bus_station", "townhall") || historic in listOf("monument", "memorial") || bridge.isNotBlank() -> PoiCategory.PUBLIC
            leisure in listOf("park", "pitch", "garden") -> PoiCategory.PARK
            else -> null
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return 6371000.0 * c
    }
}
