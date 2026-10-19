package com.example.viavox.data

import com.example.viavox.model.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

class NominatimService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
    private val googlePlacesEnricher: GooglePlacesEnricher = GooglePlacesEnricher()
) {
    var googleApiKey: String = ""

    private fun clean(value: String?): String {
        return value?.trim() ?: ""
    }

    suspend fun reverseGeocode(lat: Double, lon: Double): Result<Place> = withContext(Dispatchers.IO) {
        try {
            val formattedLat = String.format(Locale.US, "%.6f", lat)
            val formattedLon = String.format(Locale.US, "%.6f", lon)
            val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=$formattedLat&lon=$formattedLon&zoom=18&addressdetails=1"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "ViaVox-Android/0.2.0 (viavox.android.app)")
                .header("Accept-Language", "pt-BR")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    IOException("Serviço de endereços indisponível (${response.code})")
                )
            }

            val body = response.body?.string().orEmpty()
            val circ = GeneralCircumscriptionService.resolve(body, lat, lon)
            val police = PoliceCircumscriptionService.resolve(circ.neighbourhood, circ.city, circ.zoneOrDistrict)?.label.orEmpty()

            var venue = circ.venueName
            if (venue.isBlank() && googleApiKey.isNotBlank()) {
                val enriched = googlePlacesEnricher.enrichLocation(lat, lon, googleApiKey)
                if (enriched != null && enriched.name.isNotBlank()) {
                    venue = enriched.name
                }
            }

            Result.success(
                Place(
                    road = circ.road,
                    neighbourhood = circ.neighbourhood,
                    city = circ.city,
                    reference = circ.reference,
                    zone = circ.zoneOrDistrict,
                    venueName = venue,
                    policeDistrict = police
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
