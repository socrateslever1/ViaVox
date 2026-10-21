package com.example.viavox.data

import com.example.viavox.BuildConfig
import com.example.viavox.model.MapPlace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class MapsGroundingSource(
    val title: String,
    val uri: String,
    val placeId: String = ""
)

data class MapsGroundingResult(
    val query: String,
    val summary: String,
    val sources: List<MapsGroundingSource> = emptyList(),
    val places: List<MapPlace> = emptyList()
)

/**
 * Queries Gemini (gemini-3.8-flash) with the Google Maps Grounding tool ("googleMaps")
 * to retrieve up-to-date, accurate location, business, and street information anchored
 * to the user's current GPS coordinates.
 */
class GeminiMapsGroundingService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        const val MODEL_NAME = "gemini-3.8-flash"
        private const val BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_NAME:generateContent"

        private val PLACE_LINE_REGEX = Regex(
            """\[LOCAL:\s*([^|\]]+)\|\s*LAT:\s*(-?\d+(?:\.\d+)?)\s*\|\s*LON:\s*(-?\d+(?:\.\d+)?)\s*\|\s*END:\s*([^\]]+)]""",
            RegexOption.IGNORE_CASE
        )

        fun resolveApiKey(fallbackKey: String = ""): String {
            val buildConfigKey = runCatching { BuildConfig.GEMINI_API_KEY }.getOrDefault("").trim()
            if (buildConfigKey.isNotEmpty() &&
                buildConfigKey != "GEMINI_API_KEY_DEFAULT_VALUE" &&
                buildConfigKey != "null"
            ) {
                return buildConfigKey
            }
            return fallbackKey.trim()
        }

        fun buildRequestJson(
            query: String,
            latitude: Double?,
            longitude: Double?
        ): JSONObject {
            val locationContext = if (latitude != null && longitude != null &&
                latitude.isFinite() && longitude.isFinite()
            ) {
                "Minha posição GPS atual é latitude $latitude, longitude $longitude."
            } else {
                "Localização principal de referência: Manaus, AM, Brasil."
            }

            val prompt = """
                $locationContext
                Consulte dados atualizados do Google Maps para: "$query".
                Responda em português do Brasil de forma objetiva e útil para navegação urbana (endereço exato, bairro, pontos de referência próximos, horário de funcionamento se disponível e dicas de acesso).
                Além do resumo, para cada local específico encontrado com coordenadas conhecidas, inclua ao final uma linha exatamente neste formato:
                [LOCAL: Nome do Local | LAT: -3.12345 | LON: -60.12345 | END: Rua/Avenida e Bairro]
            """.trimIndent()

            return JSONObject().apply {
                put(
                    "contents",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put(
                                "parts",
                                JSONArray().put(
                                    JSONObject().put("text", prompt)
                                )
                            )
                        }
                    )
                )
                put(
                    "tools",
                    JSONArray().put(
                        JSONObject().put("googleMaps", JSONObject())
                    )
                )
                if (latitude != null && longitude != null &&
                    latitude.isFinite() && longitude.isFinite()
                ) {
                    put(
                        "toolConfig",
                        JSONObject().put(
                            "retrievalConfig",
                            JSONObject().put(
                                "latLng",
                                JSONObject()
                                    .put("latitude", latitude)
                                    .put("longitude", longitude)
                            )
                        )
                    )
                }
                put(
                    "generationConfig",
                    JSONObject().apply {
                        put("temperature", 0.2)
                    }
                )
            }
        }

        fun parseResponseJson(query: String, rawJson: String): MapsGroundingResult {
            val root = JSONObject(rawJson)
            val candidates = root.optJSONArray("candidates") ?: JSONArray()
            val firstCandidate = candidates.optJSONObject(0) ?: JSONObject()

            // 1. Extract text parts
            val content = firstCandidate.optJSONObject("content") ?: JSONObject()
            val parts = content.optJSONArray("parts") ?: JSONArray()
            val rawTextBuilder = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                val text = part.optString("text")
                if (text.isNotBlank()) {
                    if (rawTextBuilder.isNotEmpty()) rawTextBuilder.append("\n")
                    rawTextBuilder.append(text)
                }
            }
            val fullText = rawTextBuilder.toString().trim()

            // 2. Extract structured [LOCAL: ...] lines into MapPlace objects
            val extractedPlaces = mutableListOf<MapPlace>()
            PLACE_LINE_REGEX.findAll(fullText).forEach { match ->
                val name = match.groupValues[1].trim()
                val lat = match.groupValues[2].toDoubleOrNull()
                val lon = match.groupValues[3].toDoubleOrNull()
                val address = match.groupValues[4].trim()
                if (name.isNotBlank() && lat != null && lon != null &&
                    lat in -90.0..90.0 && lon in -180.0..180.0
                ) {
                    extractedPlaces.add(
                        MapPlace(
                            name = name,
                            latitude = lat,
                            longitude = lon,
                            subtitle = "$address · Google Maps"
                        )
                    )
                }
            }

            // Clean [LOCAL: ...] tags from user-facing summary text
            val cleanSummary = fullText
                .replace(PLACE_LINE_REGEX, "")
                .lines()
                .map { it.trimEnd() }
                .filterNot { it.isBlank() && it.isEmpty() }
                .joinToString("\n")
                .trim()
                .ifBlank { "Informações obtidas com Google Maps Grounding." }

            // 3. Extract groundingMetadata.groundingChunks (Google Maps URIs and titles)
            val sources = mutableListOf<MapsGroundingSource>()
            val seenUris = mutableSetOf<String>()
            val groundingMetadata = firstCandidate.optJSONObject("groundingMetadata")
            val groundingChunks = groundingMetadata?.optJSONArray("groundingChunks") ?: JSONArray()
            for (i in 0 until groundingChunks.length()) {
                val chunk = groundingChunks.optJSONObject(i) ?: continue
                val mapsObj = chunk.optJSONObject("maps") ?: chunk.optJSONObject("web") ?: continue
                val uri = mapsObj.optString("uri").trim()
                val title = mapsObj.optString("title").trim().ifBlank { "Ver no Google Maps" }
                val placeId = mapsObj.optString("placeId").trim()
                if (uri.isNotBlank() && seenUris.add(uri)) {
                    sources.add(
                        MapsGroundingSource(
                            title = title,
                            uri = uri,
                            placeId = placeId
                        )
                    )
                }
            }

            return MapsGroundingResult(
                query = query,
                summary = cleanSummary,
                sources = sources,
                places = extractedPlaces
            )
        }
    }

    suspend fun searchWithMapsGrounding(
        query: String,
        latitude: Double?,
        longitude: Double?,
        fallbackApiKey: String = ""
    ): Result<MapsGroundingResult> = withContext(Dispatchers.IO) {
        val cleanedQuery = query.trim()
        if (cleanedQuery.length < 2) {
            return@withContext Result.failure(
                IllegalArgumentException("Digite um local ou endereço para consultar no Google Maps.")
            )
        }

        val apiKey = resolveApiKey(fallbackApiKey)
        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                IOException("Configure a chave GEMINI_API_KEY no painel Secrets do AI Studio para usar o Google Maps Grounding.")
            )
        }

        try {
            val payload = buildRequestJson(cleanedQuery, latitude, longitude)
            val requestBody = payload.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val url = "$BASE_URL?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val errDetail = runCatching {
                        JSONObject(bodyStr).optJSONObject("error")?.optString("message")
                    }.getOrNull().orEmpty()
                    return@withContext Result.failure(
                        IOException(
                            if (errDetail.isNotBlank()) "Erro na consulta Google Maps ($errDetail)"
                            else "Serviço Google Maps Grounding indisponível (HTTP ${response.code})."
                        )
                    )
                }

                val parsed = parseResponseJson(cleanedQuery, bodyStr)
                Result.success(parsed)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
