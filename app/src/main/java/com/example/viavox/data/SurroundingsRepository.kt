package com.example.viavox.data

import android.util.Log
import com.example.viavox.model.NearbyLandmark
import com.example.viavox.service.SpeechTextNormalizer
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
 * Explicit 360/forward lookup and the optional Tour share ONE throttled cache.
 * Never query Overpass at GPS fix cadence. Queries may work worldwide wherever
 * OpenStreetMap has relevant named features; lack of data is not a location claim.
 */
class SurroundingsRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
) {
    private val mutex = Mutex()
    private var cache: List<NearbyLandmark> = emptyList()
    private var fetchedLat = Double.NaN
    private var fetchedLon = Double.NaN
    private var fetchedRadius = 0
    private var lastAttemptMs = 0L
    private var lastSuccessMs = 0L
    var lastError: String? = null
        private set

    suspend fun nearby(
        latitude: Double, longitude: Double, radiusMeters: Int,
        automatic: Boolean = false
    ): List<NearbyLandmark> = mutex.withLock {
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return@withLock emptyList()
        val radius = radiusMeters.coerceIn(50, 500)
        val now = System.currentTimeMillis()
        val moved = if (fetchedLat.isFinite()) {
            com.example.viavox.model.PointOfInterest(
                "origin", "", com.example.viavox.model.PoiCategory.PUBLIC, fetchedLat, fetchedLon
            ).distanceTo(latitude, longitude)
        } else Double.POSITIVE_INFINITY
        // Reuse cache only when the requested circle fits inside fetched area.
        val coversCircle = moved + radius <= fetchedRadius.toDouble()
        val recent = now - lastSuccessMs < if (automatic) 180_000L else 90_000L
        if (coversCircle && recent) return@withLock cache
        val throttleMs = if (automatic) 150_000L else 35_000L
        if (lastAttemptMs != 0L && now - lastAttemptMs < throttleMs) {
            return@withLock if (coversCircle) cache else emptyList()
        }
        lastAttemptMs = now
        val queryRadius = radius.coerceAtLeast(200)
        val results = withContext(Dispatchers.IO) { query(latitude, longitude, queryRadius) }
        if (results != null) {
            cache = results
            fetchedLat = latitude
            fetchedLon = longitude
            fetchedRadius = queryRadius
            lastSuccessMs = now
            lastError = null
        } else lastError = "Não foi possível consultar as referências agora."
        if (results != null) results else if (coversCircle) cache else emptyList()
    }

    private fun query(lat: Double, lon: Double, radius: Int): List<NearbyLandmark>? {
        val latText = String.format(Locale.US, "%.6f", lat)
        val lonText = String.format(Locale.US, "%.6f", lon)
        val q = """
            [out:json][timeout:12];
            (
              nwr["name"]["amenity"~"^(hospital|clinic|school|university|college|library|theatre|police|townhall|marketplace|restaurant|cafe|pharmacy)$"](around:$radius,$latText,$lonText);
              nwr["name"]["tourism"~"^(museum|attraction|viewpoint|artwork|gallery)$"](around:$radius,$latText,$lonText);
              nwr["name"]["historic"](around:$radius,$latText,$lonText);
              nwr["name"]["leisure"~"^(park|garden|playground)$"](around:$radius,$latText,$lonText);
              nwr["name"]["shop"~"^(department_store|supermarket|mall|books|convenience)$"](around:$radius,$latText,$lonText);
            );
            out center 120;
        """.trimIndent()
        for (endpoint in ENDPOINTS) {
            try {
                val req = Request.Builder().url(endpoint)
                    .post(q.toRequestBody("text/plain; charset=utf-8".toMediaType()))
                    .header("User-Agent", "ViaVox/1.0 geographic voice assistant")
                    .build()
                client.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val raw = response.body?.string().orEmpty()
                    if (raw.length > 1_500_000) return@use
                    return parse(raw)
                }
            } catch (e: Exception) {
                Log.w("ViaVoxSurroundings", "Nearby service unavailable", e)
            }
        }
        return null
    }

    private fun parse(raw: String): List<NearbyLandmark> {
        val arr = JSONObject(raw).optJSONArray("elements") ?: return emptyList()
        val candidates = mutableListOf<NearbyLandmark>()
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            val tags = entry.optJSONObject("tags") ?: continue
            val name = tags.optString("name").trim()
            // Unlike strict automatic road speech, shops may appear on request.
            if (name.isBlank() || name.length > 75 || name.contains("http", true)) continue
            val clean = SpeechTextNormalizer.normalize(name)
            if (clean.isBlank()) continue
            val kind = listOf("historic", "tourism", "amenity", "leisure", "shop")
                .firstOrNull { tags.has(it) } ?: continue
            val point = if (entry.optString("type") == "node") entry
                else entry.optJSONObject("center") ?: continue
            val latitude = point.optDouble("lat", Double.NaN)
            val longitude = point.optDouble("lon", Double.NaN)
            val id = entry.optLong("id")
            if (id <= 0 || !latitude.isFinite() || !longitude.isFinite()) continue
            val candidate = NearbyLandmark(
                "${entry.optString("type")}:$id", clean, latitude, longitude, kind,
                approximatePoint = entry.optString("type") != "node"
            )
            if (candidates.none {
                it.name.equals(candidate.name, ignoreCase = true) &&
                    it.distanceTo(latitude, longitude) < 45.0
            }) candidates.add(candidate)
        }
        return candidates
    }

    private companion object {
        val ENDPOINTS = listOf(
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter"
        )
    }
}
