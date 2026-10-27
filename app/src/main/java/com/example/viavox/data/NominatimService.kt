package com.example.viavox.data

import com.example.viavox.model.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

class NominatimService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build(),
    private val googlePlacesEnricher: GooglePlacesEnricher = GooglePlacesEnricher()
) {
    var googleApiKey: String = ""

    // OkHttp synchronous execute() does not stop when a GPS coroutine is
    // cancelled. Async callbacks release the connection immediately instead.
    private suspend fun awaitResponse(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response)
                    else response.close()
                }
            })
        }

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

            awaitResponse(request).use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IOException("Serviço de endereços indisponível (${response.code})")
                    )
                }
                val body = response.body?.string().orEmpty()
                if (body.length > 1_000_000) {
                    return@withContext Result.failure(IOException("Resposta excessiva do serviço de endereços"))
                }
                val circ = GeneralCircumscriptionService.resolve(body, lat, lon)
                val police = PoliceCircumscriptionService.resolve(
                    circ.neighbourhood, circ.city, circ.zoneOrDistrict
                )?.label.orEmpty()
                // Never block primary road narration on Google Places enrichment.
                // Venue/POI scans run independently from street identification.
                Result.success(
                    Place(
                        road = circ.road,
                        neighbourhood = circ.neighbourhood,
                        city = circ.city,
                        reference = circ.reference,
                        zone = circ.zoneOrDistrict,
                        venueName = circ.venueName,
                        policeDistrict = police
                    )
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
