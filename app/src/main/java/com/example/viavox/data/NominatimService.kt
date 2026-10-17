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
        .build()
) {

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

            val body = response.body?.string() ?: ""
            val json = JSONObject(body)
            val address = json.optJSONObject("address") ?: JSONObject()

            // Extract road matching original applet logic
            val road = clean(address.optString("road")).ifEmpty {
                clean(address.optString("pedestrian")).ifEmpty {
                    clean(address.optString("residential")).ifEmpty {
                        clean(address.optString("path")).ifEmpty {
                            "Via não identificada"
                        }
                    }
                }
            }

            // Extract neighbourhood matching original logic
            val neighbourhood = clean(address.optString("suburb")).ifEmpty {
                clean(address.optString("neighbourhood")).ifEmpty {
                    clean(address.optString("quarter")).ifEmpty {
                        clean(address.optString("city_district")).ifEmpty {
                            "Bairro não identificado"
                        }
                    }
                }
            }

            // Extract city
            val city = clean(address.optString("city")).ifEmpty {
                clean(address.optString("town")).ifEmpty {
                    clean(address.optString("municipality")).ifEmpty {
                        clean(address.optString("village"))
                    }
                }
            }

            // Extract reference point
            val rawName = clean(json.optString("name"))
            val reference = if (rawName.isNotBlank() && rawName != road) rawName else ""

            Result.success(
                Place(
                    road = road,
                    neighbourhood = neighbourhood,
                    city = city,
                    reference = reference
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
