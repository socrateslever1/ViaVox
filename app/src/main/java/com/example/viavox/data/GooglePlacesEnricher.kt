package com.example.viavox.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Optional Hybrid Enricher powered by Google Places & Geocoding API.
 * Never called if apiKey is blank (zero cost by default).
 * When active, enriches interior buildings, commercial centers, clinics,
 * and locations in smaller towns where OpenStreetMap coverage is sparse.
 */
class GooglePlacesEnricher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()
) {
    data class EnrichedPlace(
        val name: String,
        val formattedAddress: String,
        val types: List<String> = emptyList()
    )

    suspend fun enrichLocation(
        lat: Double,
        lon: Double,
        apiKey: String
    ): EnrichedPlace? = withContext(Dispatchers.IO) {
        val key = apiKey.trim()
        if (key.isBlank()) return@withContext null

        runCatching {
            // Google Places NearbySearch API endpoint
            val url = "https://maps.googleapis.com/maps/api/place/nearbysearch/json" +
                    "?location=$lat,$lon&radius=45&language=pt-BR&key=$key"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "ViaVox-Android/0.6 (hybrid-enricher)")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val status = json.optString("status")
                if (status != "OK") return@runCatching null

                val results = json.optJSONArray("results") ?: return@runCatching null
                if (results.length() == 0) return@runCatching null

                // Pick the most specific establishment (ignore generic road/route results)
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val name = item.optString("name").trim()
                    val typesArray = item.optJSONArray("types")
                    val types = mutableListOf<String>()
                    if (typesArray != null) {
                        for (t in 0 until typesArray.length()) types += typesArray.optString(t)
                    }

                    // Skip generic street segments
                    if (types.contains("route") || types.contains("street_address")) continue

                    if (name.isNotBlank()) {
                        val vicinity = item.optString("vicinity", "")
                        return@runCatching EnrichedPlace(
                            name = name,
                            formattedAddress = vicinity,
                            types = types
                        )
                    }
                }
                null
            }
        }.getOrNull()
    }
}
