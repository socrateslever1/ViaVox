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
import kotlinx.coroutines.CancellationException

data class SurroundingsLookup(
    val landmarks: List<NearbyLandmark>,
    val source: String,
    val error: String? = null
)

/**
 * Explicit 360/forward lookup and the optional Tour share ONE throttled cache.
 * Never query Overpass at GPS fix cadence. Queries may work worldwide wherever
 * OpenStreetMap has relevant named features; lack of data is not a location claim.
 */
class SurroundingsRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(9, TimeUnit.SECONDS)
        .build()
) {
    private val mutex = Mutex()
    private var cache: List<NearbyLandmark> = emptyList()
    private var fetchedLat = Double.NaN
    private var fetchedLon = Double.NaN
    private var fetchedRadius = 0
    private var lastAutomaticAttemptMs = 0L
    private var lastManualAttemptMs = 0L
    private var lastSuccessMs = 0L
    private var lastSource = "OpenStreetMap"
    var lastError: String? = null
        private set

    /** Explicit UI scan gets a terminal outcome; never interpret source failure as empty streets. */
    suspend fun lookup(
        latitude: Double, longitude: Double, radiusMeters: Int,
        automatic: Boolean = false
    ): SurroundingsLookup = mutex.withLock {
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            return@withLock SurroundingsLookup(emptyList(), "GPS",
                "Localização inválida. Aguarde uma nova leitura do GPS.")
        }
        val radius = radiusMeters.coerceIn(50, 500)
        val now = System.currentTimeMillis()
        val moved = if (fetchedLat.isFinite()) {
            com.example.viavox.model.PointOfInterest(
                "origin", "", com.example.viavox.model.PoiCategory.PUBLIC, fetchedLat, fetchedLon
            ).distanceTo(latitude, longitude)
        } else Double.POSITIVE_INFINITY
        val coversCircle = moved + radius <= fetchedRadius.toDouble()
        val recent = now - lastSuccessMs < if (automatic) 180_000L else 90_000L
        if (coversCircle && recent) {
            return@withLock SurroundingsLookup(cache, lastSource)
        }
        val lastAttempt = if (automatic) lastAutomaticAttemptMs else lastManualAttemptMs
        val throttleMs = if (automatic) 120_000L else 5_000L
        if (lastAttempt != 0L && now - lastAttempt < throttleMs) {
            return@withLock if (coversCircle && lastSuccessMs != 0L)
                SurroundingsLookup(cache, lastSource)
            else SurroundingsLookup(emptyList(), "cache",
                "Consulta recente indisponível. Tente novamente.")
        }
        if (automatic) lastAutomaticAttemptMs = now else lastManualAttemptMs = now
        // Enlarge the network circle to allow movement without immediately
        // invalidating the cache used by 360° and Tour.
        val queryRadius = (radius + 150).coerceIn(200, 650)
        val results = try {
            withContext(Dispatchers.IO) { query(latitude, longitude, queryRadius) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("ViaVoxSurroundings", "Lookup failed", error)
            null
        }
        if (results != null) {
            cache = results
            fetchedLat = latitude
            fetchedLon = longitude
            fetchedRadius = queryRadius
            lastSuccessMs = now
            lastError = null
            lastSource = "OpenStreetMap"
            SurroundingsLookup(results, "OpenStreetMap")
        } else {
            lastError = "O serviço de referências está indisponível."
            if (coversCircle && lastSuccessMs != 0L)
                SurroundingsLookup(cache, "OpenStreetMap (cache)", lastError)
            else SurroundingsLookup(emptyList(), "OpenStreetMap", lastError)
        }
    }

    suspend fun nearby(
        latitude: Double, longitude: Double, radiusMeters: Int,
        automatic: Boolean = false
    ): List<NearbyLandmark> = lookup(latitude, longitude, radiusMeters, automatic).landmarks

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
              nwr["name"]["shop"](around:$radius,$latText,$lonText);
            );
            out center 220;
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
            "https://overpass.private.coffee/api/interpreter",
            "https://overpass-api.de/api/interpreter"
        )
    }
}
