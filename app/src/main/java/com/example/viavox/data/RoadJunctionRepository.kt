package com.example.viavox.data

import android.util.Log
import com.example.viavox.model.NamedRoadSegment
import com.example.viavox.model.RoadGraphNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Only mapped OSM roads and genuine shared OSM nodes qualify as junctions.
 * Uses one shared, throttled network cache for on-demand and accessibility modes.
 */
class RoadJunctionRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(13, TimeUnit.SECONDS).build()
) {
    private val mutex = Mutex()
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var lastUpdated = 0L
    private var lastAttempt = 0L
    private var cachedRoads: List<NamedRoadSegment> = emptyList()
    var lastError: String? = null
        private set

    suspend fun roads(lat: Double, lon: Double): List<NamedRoadSegment> = mutex.withLock {
        if (!lat.isFinite() || !lon.isFinite()) return@withLock emptyList()
        val now = System.currentTimeMillis()
        val moved = if (lastLat.isFinite()) {
            com.example.viavox.model.PointOfInterest("origin", "",
                com.example.viavox.model.PoiCategory.PUBLIC,
                lastLat, lastLon).distanceTo(lat, lon)
        } else Double.POSITIVE_INFINITY
        if (moved <= 75 && now - lastUpdated < 110_000L) return@withLock cachedRoads
        if (now - lastAttempt < 40_000L) {
            return@withLock if (moved < 125) cachedRoads else emptyList()
        }
        lastAttempt = now
        val received = withContext(Dispatchers.IO) { fetchRoads(lat, lon) }
        if (received != null) {
            lastLat = lat
            lastLon = lon
            lastUpdated = now
            cachedRoads = received
            lastError = null
            return@withLock received
        }
        lastError = "Não foi possível consultar a malha viária neste momento."
        return@withLock if (moved < 125 && now - lastUpdated < 180_000L) cachedRoads
            else emptyList()
    }

    private fun fetchRoads(lat: Double, lon: Double): List<NamedRoadSegment>? {
        val latitude = String.format(Locale.US, "%.6f", lat)
        val longitude = String.format(Locale.US, "%.6f", lon)
        val q = """
            [out:json][timeout:12];
            way["highway"~"^(residential|living_street|primary|secondary|tertiary|unclassified|pedestrian|service|footway|path|steps|cycleway)$"]["name"](around:260,$latitude,$longitude);
            (._;>;);
            out body;
        """.trimIndent()
        for (server in ENDPOINTS) {
            try {
                val request = Request.Builder().url(server)
                    .post(q.toRequestBody("text/plain; charset=utf-8".toMediaType()))
                    .header("User-Agent", "ViaVox/1.0 (public OSM junction assistance)")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val raw = response.body?.string().orEmpty()
                        if (raw.length in 2..2_500_000) return parse(raw)
                    }
                }
            } catch (ex: Exception) {
                Log.w("ViaVoxJunctions", "Map topology unavailable: $server", ex)
            }
        }
        return null
    }

    internal fun parse(raw: String): List<NamedRoadSegment> {
        val objects = JSONObject(raw).optJSONArray("elements") ?: return emptyList()
        val nodes = mutableMapOf<Long, RoadGraphNode>()
        val roads = mutableListOf<JSONObject>()
        for (i in 0 until objects.length()) {
            val obj = objects.optJSONObject(i) ?: continue
            when (obj.optString("type")) {
                "node" -> {
                    val id = obj.optLong("id")
                    val lat = obj.optDouble("lat", Double.NaN)
                    val lon = obj.optDouble("lon", Double.NaN)
                    if (id > 0 && lat.isFinite() && lon.isFinite()) nodes[id] =
                        RoadGraphNode(id, lat, lon)
                }
                "way" -> if (obj.optJSONObject("tags")?.optString("name")?.isNotBlank() == true) {
                    roads.add(obj)
                }
            }
        }
        return roads.mapNotNull { way ->
            val name = way.optJSONObject("tags")?.optString("name")?.trim().orEmpty()
            val array = way.optJSONArray("nodes") ?: return@mapNotNull null
            val chain = (0 until array.length()).mapNotNull { nodes[array.optLong(it)] }
            if (chain.size < 2 || name.isBlank()) null
            else NamedRoadSegment(way.optLong("id"), name, chain)
        }
    }

    private companion object {
        val ENDPOINTS = listOf(
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter"
        )
    }
}
