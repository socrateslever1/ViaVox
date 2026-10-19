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
 * Real-time Speed Camera and Toll Booth discovery and voice alert engine.
 * Discovers fixed speed cameras and tolls from OpenStreetMap, alerts the driver
 * in advance by voice, and provides points to render on the map.
 */
class SafetyAlertEngine(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build(),
    private val onAlert: (message: String) -> Unit = {}
) {
    data class SafetyFeature(
        val id: Long,
        val type: Type,
        val latitude: Double,
        val longitude: Double,
        val speedLimitKmh: Int? = null,
        val name: String = ""
    ) {
        enum class Type { SPEED_CAMERA, TOLL_BOOTH }
    }

    private val cachedFeatures = mutableListOf<SafetyFeature>()
    private val alertedFeatureIds = mutableSetOf<Long>()
    private var lastQueryLat = Double.NaN
    private var lastQueryLon = Double.NaN
    private var lastQueryTime = 0L

    fun getFeatures(): List<SafetyFeature> = synchronized(cachedFeatures) { cachedFeatures.toList() }

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

        // 2. Check for upcoming cameras or tolls in the path of travel
        val upcoming = synchronized(cachedFeatures) {
            cachedFeatures.filter { feat ->
                val dist = NavigationMath.meters(userLat, userLon, feat.latitude, feat.longitude)
                // Warning window: 150m to 480m ahead
                dist in 80.0..480.0
            }
        }

        for (feat in upcoming) {
            if (alertedFeatureIds.contains(feat.id)) continue

            // Directional check: is the camera in front of the vehicle?
            val dLat = feat.latitude - userLat
            val dLon = feat.longitude - userLon
            val angleToFeature = Math.toDegrees(Math.atan2(dLon, dLat)).toFloat()
            val normalizedAngle = ((angleToFeature % 360f) + 360f) % 360f
            val normalizedBearing = ((bearing % 360f) + 360f) % 360f
            val angleDiff = abs(((normalizedAngle - normalizedBearing + 540f) % 360f) - 180f)

            // Vehicle moving in direction of feature (within 55 degrees cone)
            if (speedKmh >= 15f && angleDiff <= 55f) {
                alertedFeatureIds.add(feat.id)
                val dist = NavigationMath.meters(userLat, userLon, feat.latitude, feat.longitude).toInt()
                val msg = when (feat.type) {
                    SafetyFeature.Type.SPEED_CAMERA -> {
                        val limitPart = feat.speedLimitKmh?.let { ", limite $it por hora" } ?: ""
                        "Atenção: radar à frente em $dist metros$limitPart."
                    }
                    SafetyFeature.Type.TOLL_BOOTH -> {
                        "Atenção: praça de pedágio à frente em $dist metros."
                    }
                }
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
                val json = JSONObject(body)
                val elements = json.optJSONArray("elements") ?: return

                val features = mutableListOf<SafetyFeature>()
                for (i in 0 until elements.length()) {
                    val el = elements.optJSONObject(i) ?: continue
                    val id = el.optLong("id")
                    val featLat = el.optDouble("lat", Double.NaN)
                    val featLon = el.optDouble("lon", Double.NaN)
                    if (!featLat.isFinite() || !featLon.isFinite()) continue

                    val tags = el.optJSONObject("tags") ?: JSONObject()
                    val highway = tags.optString("highway")
                    val barrier = tags.optString("barrier")
                    val maxspeed = tags.optString("maxspeed").filter { it.isDigit() }.toIntOrNull()

                    val type = when {
                        highway == "speed_camera" -> SafetyFeature.Type.SPEED_CAMERA
                        barrier == "toll_booth" -> SafetyFeature.Type.TOLL_BOOTH
                        else -> continue
                    }

                    features.add(
                        SafetyFeature(
                            id = id,
                            type = type,
                            latitude = featLat,
                            longitude = featLon,
                            speedLimitKmh = maxspeed,
                            name = tags.optString("name")
                        )
                    )
                }

                synchronized(cachedFeatures) {
                    cachedFeatures.clear()
                    cachedFeatures.addAll(features)
                }
            }
        } catch (_: Exception) {
            // Network fallback: offline safety preserves current cache
        }
    }
}
