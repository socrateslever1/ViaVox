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
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Finds only named, real OpenStreetMap landmarks. Cache and rate limits protect
 * battery and public Overpass infrastructure. Never fabricate a POI.
 */
class PoiDiscoveryService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(9, TimeUnit.SECONDS)
        .build()
) {
    private val cachedPois = mutableListOf<PointOfInterest>()
    private val municipalReferences = ArcGisManausPlacesService(client)
    private val announced = mutableMapOf<String, Long>()
    private var lastQueryLat = Double.NaN
    private var lastQueryLon = Double.NaN
    private var lastSuccessAt = 0L
    private var lastAttemptAt = 0L
    private var querying = false

    var lastFailure: String? = null
        private set

    /** Number of real named landmarks returned by the latest successful query. */
    val cachedCount: Int get() = synchronized(cachedPois) { cachedPois.size }

    suspend fun findNearbyPoi(
        userLat: Double,
        userLon: Double,
        config: ViaVoxConfig,
        gpsAccuracyMeters: Float = 30f,
        speedMps: Float = 0f,
        bearingDegrees: Float? = null,
        currentRoad: String = ""
    ): PointOfInterest? {
        // Do not make an expensive remote request without a confirmed
        // current street and accurate GPS; guessing landmarks is worse
        // than a quiet trip.
        if (!config.narratePoiEnabled || currentRoad.isBlank() ||
            !gpsAccuracyMeters.isFinite() ||
            gpsAccuracyMeters <= 0f ||
            gpsAccuracyMeters > PoiSelectionPolicy.MAX_GPS_ACCURACY_METERS
        ) return null
        val now = System.currentTimeMillis()
        synchronized(announced) {
            announced.entries.removeAll { now - it.value >= ANNOUNCE_COOLDOWN_MS }
        }
        val moved = if (lastQueryLat.isFinite() && lastQueryLon.isFinite()) {
            PointOfInterest("centre", "", PoiCategory.PUBLIC, lastQueryLat, lastQueryLon)
                .distanceTo(userLat, userLon)
        } else Double.POSITIVE_INFINITY

        // Requery after moving into a new area; never hammer Overpass on each GPS fix.
        if ((lastSuccessAt == 0L || moved >= 175.0 || now - lastSuccessAt >= 120_000L) &&
            (lastAttemptAt == 0L || now - lastAttemptAt >= 35_000L)
        ) refreshCache(userLat, userLon, now)

        val candidates = synchronized(cachedPois) {
            cachedPois.filter { isCategoryEnabled(it.category, config) }
        }
        val seen = synchronized(announced) {
            candidates.filter { poi -> (announced[dedupeKey(poi)] ?: 0L) > now - ANNOUNCE_COOLDOWN_MS }
                .map { it.id }.toSet()
        }
        return PoiSelectionPolicy.choose(
            candidates, userLat, userLon, gpsAccuracyMeters, speedMps, bearingDegrees,
            currentRoad, seen
        )
    }

    /**
     * Spoken landmarks must still be on the user's current street and within
     * 30m at the *start* of speech. A queued landmark expires when the rider
     * turns, drives past it, or loses precise GPS.
     */
    fun isStillRelevant(
        id: String, latitude: Double, longitude: Double,
        accuracyMeters: Float, speedMps: Float, bearingDegrees: Float?,
        currentRoad: String
    ): Boolean {
        val poi = synchronized(cachedPois) { cachedPois.firstOrNull { it.id == id } }
            ?: return false
        return PoiSelectionPolicy.choose(
            listOf(poi), latitude, longitude, accuracyMeters,
            speedMps, bearingDegrees, currentRoad
        ) != null
    }

    /**
     * Mark only when the service actually sends the message to the TTS manager,
     * never while querying. A postponed POI must remain eligible.
     */
    fun markAnnounced(poi: PointOfInterest) {
        synchronized(announced) {
            announced[dedupeKey(poi)] = System.currentTimeMillis()
        }
    }

    private fun dedupeKey(poi: PointOfInterest): String =
        "${poi.category}:${poi.name.trim().lowercase(Locale.ROOT)}"

    private fun isCategoryEnabled(category: PoiCategory, config: ViaVoxConfig): Boolean =
        when (category) {
            PoiCategory.HEALTH -> config.enableHealthPoi
            PoiCategory.EDUCATION -> config.enableEducationPoi
            PoiCategory.PARK -> config.enableParksPoi
            PoiCategory.FUEL -> config.enableFuelPoi
            PoiCategory.PUBLIC -> config.enablePublicPoi
        }

    private suspend fun refreshCache(lat: Double, lon: Double, now: Long) =
        withContext(Dispatchers.IO) {
            if (querying) return@withContext
            querying = true
            lastAttemptAt = now
            try {
                val latText = String.format(Locale.US, "%.6f", lat)
                val lonText = String.format(Locale.US, "%.6f", lon)
                val ql = """
                    [out:json][timeout:9];
                    (
                      node["amenity"~"^(hospital|school|university|college|townhall|police|fire_station|courthouse|library)$"]["name"](around:220,$latText,$lonText);
                      node["leisure"~"^(park)$"]["name"](around:220,$latText,$lonText);
                      node["historic"~"^(monument|memorial)$"]["name"](around:220,$latText,$lonText);
                      node["tourism"~"^(museum)$"]["name"](around:220,$latText,$lonText);
                    );
                    out 80;
                """.trimIndent()
                // Use the published Manaus ArcGIS points as the first source
                // for important landmarks; this also works when Overpass returns 502.
                val municipalPois = if (ManausArcGisLayer.inCoverage(lat,lon)) {
                    municipalReferences.loadVisible(
                        GisBounds(lon-0.0034,lat-0.0034,lon+0.0034,lat+0.0034),
                        16.0
                    ).mapNotNull { it.asSpokenReference() }
                        .filter(PoiSelectionPolicy::isImportantForSpeech)
                        .filter { it.distanceTo(lat,lon) <= 220.0 }
                } else emptyList()
                // Do not query both public GIS services if enough landmarks
                // are already available in the local dataset.
                var newPois: List<PointOfInterest>? =
                    municipalPois.takeIf { it.size >= 4 }
                if (newPois == null) for (server in OVERPASS_ENDPOINTS) {
                    try {
                        val request = Request.Builder()
                            .url(server)
                            .post(ql.toRequestBody("text/plain; charset=utf-8".toMediaType()))
                            .header("User-Agent", "ViaVox/0.7 Android (https://github.com/socrateslever1/ViaVox)")
                            .build()
                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val raw = response.body?.string()
                                if (!raw.isNullOrBlank()) newPois = OverpassPoiParser.parse(raw)
                            } else {
                                Log.w(TAG, "Overpass HTTP ${response.code} at $server")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Overpass instance unavailable: $server",e)
                    }
                    if (newPois != null) break
                }
                if (newPois == null && municipalPois.isEmpty()) {
                    lastFailure = "Referências indisponíveis no momento"
                    return@withContext
                }
                val found = (municipalPois + newPois.orEmpty())
                    .distinctBy { "${it.category}:${it.name.trim().lowercase(Locale.ROOT)}" }
                synchronized(cachedPois) {
                    cachedPois.clear()
                    cachedPois.addAll(found)
                }
                lastQueryLat = lat
                lastQueryLon = lon
                lastSuccessAt = System.currentTimeMillis()
                lastFailure = null
                Log.d(TAG, "${found.size} named landmarks cached around current GPS area")
            } catch (e: Exception) {
                lastFailure = "Sem conexão com a base de pontos de referência"
                Log.w(TAG, "POI query failed; retaining previous cache", e)
            } finally {
                querying = false
            }
        }

    private companion object {
        const val TAG = "ViaVoxPOI"
        val OVERPASS_ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter"
        )
        const val ANNOUNCE_COOLDOWN_MS = 15 * 60_000L
    }
}
