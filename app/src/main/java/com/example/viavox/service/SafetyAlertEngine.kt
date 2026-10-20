package com.example.viavox.service

import android.content.Context
import com.example.viavox.model.NavigationMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Real-time Road Alert Engine:
 * - Fixed Speed Cameras (Radares de velocidade)
 * - Speed Humps / Speed Limit Cameras (Lombadas eletrônicas)
 * - Red Light / Traffic Signal Cameras (Fiscalização semafórica)
 * - Toll Booths (Praças de pedágio)
 *
 * Integrates authoritative local official datasets (e.g. IMMU Manaus) from assets
 * and dynamically merges with OpenStreetMap Overpass alerts with spatial deduplication (45m).
 * Evaluates upcoming alerts in direction of travel and triggers timely voice announcements.
 */
class SafetyAlertEngine(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build(),
    private val onAlert: (message: String) -> Unit = {}
) {
    data class SafetyFeature(
        val id: String,
        val type: Type,
        val latitude: Double,
        val longitude: Double,
        val speedLimitKmh: Int? = null,
        val location: String = "",
        val direction: String? = null,
        val source: String = "osm", // "official" or "osm"
        val sourceName: String = "",
        val sourceUrl: String = ""
    ) {
        enum class Type {
            SPEED_CAMERA,
            SPEED_HUMP_CAMERA,
            RED_LIGHT_CAMERA,
            TOLL_BOOTH;

            val isCamera: Boolean get() = this != TOLL_BOOTH
        }
    }

    private val officialFeatures = mutableListOf<SafetyFeature>()
    private val osmFeatures = mutableListOf<SafetyFeature>()
    private val cachedMergedFeatures = mutableListOf<SafetyFeature>()
    private val alertedFeatureIds = mutableSetOf<String>()
    private var lastQueryLat = Double.NaN
    private var lastQueryLon = Double.NaN
    private var lastQueryTime = 0L

    companion object {
        const val DUPLICATE_DISTANCE_METERS = 45.0
        const val WARNING_MIN_METERS = 80.0
        const val WARNING_MAX_METERS = 480.0
        const val WARNING_MIN_SPEED_KMH = 15f
        const val WARNING_CONE_DEGREES = 55f

        fun parseOfficialAlerts(jsonString: String): List<SafetyFeature> {
            val result = mutableListOf<SafetyFeature>()
            try {
                val root = JSONObject(jsonString)
                if (root.optString("type") != "FeatureCollection") return emptyList()
                val features = root.optJSONArray("features") ?: return emptyList()
                val seenIds = mutableSetOf<String>()

                for (i in 0 until features.length()) {
                    val feat = features.optJSONObject(i) ?: continue
                    if (feat.optString("type") != "Feature") continue
                    val geom = feat.optJSONObject("geometry") ?: continue
                    if (geom.optString("type") != "Point") continue
                    val coords = geom.optJSONArray("coordinates") ?: continue
                    if (coords.length() < 2) continue

                    val lon = coords.optDouble(0, Double.NaN)
                    val lat = coords.optDouble(1, Double.NaN)
                    if (!lat.isFinite() || !lon.isFinite() || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) continue

                    val props = feat.optJSONObject("properties") ?: continue
                    val id = props.optString("id").trim()
                    if (id.isEmpty() || seenIds.contains(id)) continue

                    val typeStr = props.optString("type").trim()
                    val type = when (typeStr) {
                        "speed_camera" -> SafetyFeature.Type.SPEED_CAMERA
                        "speed_hump_camera" -> SafetyFeature.Type.SPEED_HUMP_CAMERA
                        "red_light_camera" -> SafetyFeature.Type.RED_LIGHT_CAMERA
                        else -> continue
                    }

                    val location = props.optString("location").trim()
                    val sourceName = props.optString("sourceName").trim()
                    val sourceUrl = props.optString("sourceUrl").trim()
                    val sourceDate = props.optString("sourceDate").trim()
                    val reviewedAt = props.optString("reviewedAt").trim()

                    if (location.isEmpty() || sourceName.isEmpty() || sourceUrl.isEmpty() ||
                        sourceDate.isEmpty() || reviewedAt.isEmpty()) continue

                    val rawSpeed = props.optInt("maxspeedKmh", -1)
                    val speedLimit = if (rawSpeed in 1..300) rawSpeed else null
                    val direction = props.optString("direction").trim().takeIf { it.isNotEmpty() }

                    seenIds.add(id)
                    result.add(
                        SafetyFeature(
                            id = id,
                            type = type,
                            latitude = lat,
                            longitude = lon,
                            speedLimitKmh = speedLimit,
                            location = location,
                            direction = direction,
                            source = "official",
                            sourceName = sourceName,
                            sourceUrl = sourceUrl
                        )
                    )
                }
            } catch (_: Exception) {
                // Return gracefully on malformed JSON
            }
            return result
        }

        fun parseOsmAlerts(jsonString: String): List<SafetyFeature> {
            val result = mutableListOf<SafetyFeature>()
            try {
                val root = JSONObject(jsonString)
                val elements = root.optJSONArray("elements") ?: return emptyList()
                val seenIds = mutableSetOf<String>()

                for (i in 0 until elements.length()) {
                    val el = elements.optJSONObject(i) ?: continue
                    val idRaw = el.opt("id") ?: continue
                    val id = "osm:$idRaw"
                    if (seenIds.contains(id)) continue

                    val lat = el.optDouble("lat", Double.NaN)
                    val lon = el.optDouble("lon", Double.NaN)
                    if (!lat.isFinite() || !lon.isFinite() || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) continue

                    val tags = el.optJSONObject("tags") ?: JSONObject()
                    val highway = tags.optString("highway")
                    val barrier = tags.optString("barrier")

                    val type = when {
                        highway == "speed_camera" -> SafetyFeature.Type.SPEED_CAMERA
                        barrier == "toll_booth" -> SafetyFeature.Type.TOLL_BOOTH
                        else -> continue
                    }

                    val rawSpeedStr = tags.optString("maxspeed")
                    val speedDigits = rawSpeedStr.filter { it.isDigit() }.toIntOrNull()
                    val speedLimit = if (speedDigits != null && speedDigits in 1..300) speedDigits else null
                    val name = tags.optString("name").trim()

                    seenIds.add(id)
                    result.add(
                        SafetyFeature(
                            id = id,
                            type = type,
                            latitude = lat,
                            longitude = lon,
                            speedLimitKmh = speedLimit,
                            location = name,
                            source = "osm"
                        )
                    )
                }
            } catch (_: Exception) {
                // Return gracefully
            }
            return result
        }

        fun mergeAlerts(official: List<SafetyFeature>, osm: List<SafetyFeature>): List<SafetyFeature> {
            val merged = official.toMutableList()
            for (alert in osm) {
                val isDuplicate = merged.any { existing ->
                    (existing.type.isCamera == alert.type.isCamera) &&
                        NavigationMath.meters(existing.latitude, existing.longitude, alert.latitude, alert.longitude) < DUPLICATE_DISTANCE_METERS
                }
                if (!isDuplicate) {
                    merged.add(alert)
                }
            }
            return merged
        }

        fun buildAlertMessage(feature: SafetyFeature, distanceMeters: Int): String {
            val limitPart = feature.speedLimitKmh?.let { ", limite $it por hora" } ?: ""
            return when (feature.type) {
                SafetyFeature.Type.SPEED_CAMERA -> "Atenção: radar à frente em $distanceMeters metros$limitPart."
                SafetyFeature.Type.SPEED_HUMP_CAMERA -> "Atenção: lombada eletrônica à frente em $distanceMeters metros$limitPart."
                SafetyFeature.Type.RED_LIGHT_CAMERA -> "Atenção: fiscalização semafórica à frente em $distanceMeters metros$limitPart."
                SafetyFeature.Type.TOLL_BOOTH -> "Atenção: praça de pedágio à frente em $distanceMeters metros."
            }
        }
    }

    fun loadOfficialAlertsFromAssets(context: Context, assetFileName: String = "radares-manaus.geojson") {
        try {
            val json = context.assets.open(assetFileName).bufferedReader().use { it.readText() }
            setOfficialAlerts(parseOfficialAlerts(json))
        } catch (_: Exception) {
            // Assets file not present or unreadable; continues safely
        }
    }

    fun setOfficialAlerts(alerts: List<SafetyFeature>) {
        synchronized(officialFeatures) {
            officialFeatures.clear()
            officialFeatures.addAll(alerts)
            rebuildMergedFeatures()
        }
    }

    fun setOsmAlerts(alerts: List<SafetyFeature>) {
        synchronized(osmFeatures) {
            osmFeatures.clear()
            osmFeatures.addAll(alerts)
            rebuildMergedFeatures()
        }
    }

    private fun rebuildMergedFeatures() {
        val merged = mergeAlerts(officialFeatures, osmFeatures)
        synchronized(cachedMergedFeatures) {
            cachedMergedFeatures.clear()
            cachedMergedFeatures.addAll(merged)
        }
    }

    fun getFeatures(): List<SafetyFeature> = synchronized(cachedMergedFeatures) { cachedMergedFeatures.toList() }

    suspend fun evaluate(
        userLat: Double,
        userLon: Double,
        bearing: Float,
        speedKmh: Float,
        enabled: Boolean = true
    ): List<SafetyFeature> = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext emptyList()
        val now = System.currentTimeMillis()

        // 1. Refresh cache if vehicle moved > 400m or every 3 minutes
        val movedMeters = if (lastQueryLat.isFinite() && lastQueryLon.isFinite()) {
            NavigationMath.meters(lastQueryLat, lastQueryLon, userLat, userLon)
        } else Double.POSITIVE_INFINITY

        if (movedMeters > 400.0 || now - lastQueryTime >= 180_000L) {
            querySafetyFeatures(userLat, userLon)
            lastQueryLat = userLat
            lastQueryLon = userLon
            lastQueryTime = now
        }

        // 2. Check for upcoming alerts in the path of travel
        val upcoming = synchronized(cachedMergedFeatures) {
            cachedMergedFeatures.filter { feat ->
                val dist = NavigationMath.meters(userLat, userLon, feat.latitude, feat.longitude)
                dist in WARNING_MIN_METERS..WARNING_MAX_METERS
            }
        }

        for (feat in upcoming) {
            if (alertedFeatureIds.contains(feat.id)) continue

            // Directional check: is the alert in front of the vehicle?
            val dLat = feat.latitude - userLat
            val dLon = feat.longitude - userLon
            val angleToFeature = Math.toDegrees(Math.atan2(dLon, dLat)).toFloat()
            val normalizedAngle = ((angleToFeature % 360f) + 360f) % 360f
            val normalizedBearing = ((bearing % 360f) + 360f) % 360f
            val angleDiff = abs(((normalizedAngle - normalizedBearing + 540f) % 360f) - 180f)

            // Vehicle moving in direction of feature (within cone)
            if (speedKmh >= WARNING_MIN_SPEED_KMH && angleDiff <= WARNING_CONE_DEGREES) {
                alertedFeatureIds.add(feat.id)
                val dist = NavigationMath.meters(userLat, userLon, feat.latitude, feat.longitude).toInt()
                val msg = buildAlertMessage(feat, dist)
                onAlert(msg)
            }
        }

        getFeatures()
    }

    private fun querySafetyFeatures(lat: Double, lon: Double) {
        val query = String.format(
            Locale.US,
            """
            [out:json][timeout:6];
            (
              node["highway"="speed_camera"](around:2500,%.5f,%.5f);
              node["barrier"="toll_booth"](around:3500,%.5f,%.5f);
            );
            out body qt;
            """.trimIndent(),
            lat, lon, lat, lon
        )

        val request = Request.Builder()
            .url("https://overpass-api.de/api/interpreter")
            .header("User-Agent", "ViaVox-Android/0.6 (safety-alerts)")
            .post(query.trim().toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType()))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return
                val body = response.body?.string().orEmpty()
                val osmParsed = parseOsmAlerts(body)
                setOsmAlerts(osmParsed)
            }
        } catch (_: Exception) {
            // Network fallback: offline safety preserves current cache
        }
    }
}
