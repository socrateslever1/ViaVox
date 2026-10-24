package com.example.viavox.data

import com.example.viavox.model.MapPlace
import com.example.viavox.model.PoiCategory
import com.example.viavox.model.PointOfInterest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Offline result from a public ArcGIS feature, not an invented coordinate.
 * The metadata explicitly warns that police locations may change.
 */
object VerifiedManausLandmarks {
    private val cicom24 = MapPlace(
        "24ª CICOM", -3.13791017099993, -60.01634535299996,
        "Centro · Manaus · cadastro ArcGIS (confirme o endereço)"
    )
    fun find(query: String): List<MapPlace> =
        if (DestinationSearchPolicy.cicomNumber(query) == "24") listOf(cicom24)
        else emptyList()
}

/** Public ArcGIS feature layer linked in the user's Polícia Civil map. */
object ManausArcGisLayer {
    const val SOURCE = "GeoSegurança / ArcGIS Online — Pontos_de_referência"
    const val ITEM_ID = "efaa5cc2e26f4f30a51ba49108f55d7a"
    const val API = "https://services3.arcgis.com/5yrshRp0yzXAppIi/arcgis/rest/services/" +
        "publica%C3%A7%C3%A3o_de_v%C3%A1rias_camadas/FeatureServer/0/query"
    const val MIN_ZOOM = 15.0

    // Bounding area of the *published feature layer*, not a nationwide geocoder.
    fun inCoverage(latitude: Double, longitude: Double): Boolean =
        latitude in -3.20..-2.88 && longitude in -60.17..-59.79
}

data class GisBounds(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double
) {
    fun isValid(): Boolean = west < east && south < north &&
        west in -180.0..180.0 && east in -180.0..180.0 &&
        south in -90.0..90.0 && north in -90.0..90.0
    fun midLatitude(): Double = (north + south) / 2.0
    fun midLongitude(): Double = (west + east) / 2.0
}

data class GisReference(
    val id: Long,
    val name: String,
    val type: String,
    val subtype: String,
    val address: String,
    val neighborhood: String,
    val latitude: Double,
    val longitude: Double,
    val alias: String = ""
) {
    fun asDestination() = MapPlace(
        name = alias.takeIf { it.contains("CICOM",ignoreCase = true) } ?: name,
        latitude = latitude,
        longitude = longitude,
        subtitle = listOfNotNull(address.takeIf(String::isNotBlank),
            neighborhood.takeIf(String::isNotBlank), "Manaus")
            .joinToString(" · ")
    )

    /**
     * Speech uses only important public landmarks, not every tiny storefront.
     * Generic shops remain visible on the map and selectable as destinations.
     */
    fun asSpokenReference(): PointOfInterest? {
        val tags = DestinationSearchPolicy.normalize("$type $subtype")
        // ArcGIS category labels can be broad (e.g. COMÉRCIO/SERVIÇOS).
        // Narrate only community-significant sites, never random businesses.
        if (listOf("farmacia","drogaria","empresa","comercio","shopping",
                "supermercado","combustivel","abastecimento","clinica",
                "laboratorio","posto de gasolina","loja","restaurante")
                .any(tags::contains)) return null
        val category = when {
            listOf("hospital","upa","unidade basica de saude","ubs")
                .any(tags::contains) -> PoiCategory.HEALTH
            listOf("escola","universidade","educacao","colegio","instituto federal")
                .any(tags::contains) -> PoiCategory.EDUCATION
            listOf("policia","bombeiro","delegacia","forum","tribunal",
                "orgao publico","prefeitura","museu","monumento")
                .any(tags::contains) -> PoiCategory.PUBLIC
            listOf("parque","praca").any(tags::contains) -> PoiCategory.PARK
            else -> null
        } ?: return null
        if (address.isBlank()) return null
        val spokenName = when {
            alias.contains("CICOM",ignoreCase = true) -> alias.trim()
            alias.length >= 3 && name.endsWith(alias,ignoreCase = true) ->
                name.dropLast(alias.length).trim()
            else -> name.trim()
        }
        return PointOfInterest(
            "arcgis_$id", spokenName, category, latitude, longitude,
            streetAddress = address
        )
    }

    val shortLabel: String
        get() {
            if (alias.contains("CICOM",ignoreCase = true)) return alias.take(26)
            // Some published records concatenate the acronym to the name,
            // such as COMANDO GERALCMDOGERAL. Show a clean real-world label.
            val sanitized = if (alias.length >= 3 && name.endsWith(alias,ignoreCase = true))
                name.dropLast(alias.length).trim()
            else name.trim()
            return sanitized.ifBlank { name }.take(36)
        }
}

object ArcGisReferenceParser {
    fun parse(body: String): List<GisReference> {
        val json = JSONObject(body)
        json.optJSONObject("error")?.let { error ->
            throw IllegalStateException("ArcGIS: ${error.optString("message")}")
        }
        val list = json.optJSONArray("features") ?: return emptyList()
        val out = mutableListOf<GisReference>()
        for (i in 0 until list.length()) {
            val point = list.optJSONObject(i) ?: continue
            val attributes = point.optJSONObject("attributes") ?: continue
            val coordinates = point.optJSONObject("geometry") ?: continue
            val latitude = coordinates.optDouble("y", Double.NaN)
            val longitude = coordinates.optDouble("x", Double.NaN)
            val id = attributes.optLong("OBJECTID",-1L)
            val name = attributes.optString("NOME_PONTO").takeUnless { it == "null" }.orEmpty().trim()
            if (id <= 0 || name.isBlank() ||
                !ManausArcGisLayer.inCoverage(latitude,longitude)) continue

            fun text(key: String) = attributes.optString(key).takeUnless { it == "null" }.orEmpty().trim()
            out += GisReference(
                id = id, name = name, type = text("TIPO"),
                subtype = text("SUBTIPO"), address = text("ENDERECO"),
                neighborhood = text("BAIRRO"),latitude = latitude,
                longitude = longitude, alias = text("SIGLA_PONTO")
            )
        }
        return out.distinctBy { it.id }
    }
}

/**
 * Public ArcGIS layer is a useful secondary dataset, not certified current
 * police dispatch/navigation data. All requests are read-only and bbox scoped.
 * Do not fetch entire municipal database or refresh on every GPS tick.
 */
class ArcGisManausPlacesService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6,TimeUnit.SECONDS)
        .readTimeout(10,TimeUnit.SECONDS)
        .build()
) {
    private var lastViewportBounds: GisBounds? = null
    private var lastViewportZoom = 0.0
    private var viewportLastFetchedAt = 0L
    private var lastViewport: List<GisReference> = emptyList()

    suspend fun loadVisible(bounds: GisBounds, zoom: Double): List<GisReference> =
        withContext(Dispatchers.IO) {
            if (zoom < ManausArcGisLayer.MIN_ZOOM || !bounds.isValid() ||
                !ManausArcGisLayer.inCoverage(bounds.midLatitude(),bounds.midLongitude())
            ) return@withContext emptyList()
            val now = System.currentTimeMillis()
            val previous = lastViewportBounds
            val moved = if (previous == null) Double.POSITIVE_INFINITY else
                com.example.viavox.model.NavigationMath.meters(
                    previous.midLatitude(),previous.midLongitude(),
                    bounds.midLatitude(),bounds.midLongitude()
                )
            // Previously keyed to ~550m grid cells: this kept stale landmarks
            // while a motorcycle crossed several blocks. Reload after ~60m,
            // while retaining the 90-second idle cache to protect the endpoint.
            if (previous != null && moved < 60.0 &&
                kotlin.math.abs(zoom - lastViewportZoom) < 0.7 &&
                now - viewportLastFetchedAt < 90_000L
            ) return@withContext lastViewport

            val geometry = String.format(Locale.US,"%.6f,%.6f,%.6f,%.6f",
                bounds.west,bounds.south,bounds.east,bounds.north)
            try {
                val data = fetch(
                    mapOf(
                        "where" to "1=1",
                        "geometry" to geometry,
                        "geometryType" to "esriGeometryEnvelope",
                        "spatialRel" to "esriSpatialRelIntersects",
                        "inSR" to "4326",
                        "outSR" to "4326",
                        "outFields" to "OBJECTID,NOME_PONTO,SIGLA_PONTO,TIPO,SUBTIPO,ENDERECO,BAIRRO",
                        "resultRecordCount" to "220",
                        "orderByFields" to "OBJECTID",
                        "returnGeometry" to "true"
                    )
                )
                val places = ArcGisReferenceParser.parse(data)
                lastViewport = places
                lastViewportBounds = bounds
                lastViewportZoom = zoom
                viewportLastFetchedAt = now
                places
            } catch (error: Exception) {
                android.util.Log.w("ViaVoxArcGIS","Reference layer unreachable",error)
                if (previous != null && moved < 60.0) lastViewport else emptyList()
            }
        }

    suspend fun search(query: String): List<MapPlace> = withContext(Dispatchers.IO) {
        val normalized = DestinationSearchPolicy.normalize(query)
        val number = DestinationSearchPolicy.cicomNumber(query)
        val clean = if (number != null || DestinationSearchPolicy.isCicomQuery(query))
            "CICOM" else query.trim().take(70)
        if (clean.length < 3) return@withContext emptyList()
        val safe = clean.replace("'","''").replace("%","")
        val where = if (number != null)
            "SIGLA_PONTO LIKE '${number}%CICOM%'"
        else "NOME_PONTO LIKE '%${safe}%' OR SIGLA_PONTO LIKE '%${safe}%'"
        try {
            ArcGisReferenceParser.parse(fetch(mapOf(
                "where" to where,
                "outFields" to "OBJECTID,NOME_PONTO,SIGLA_PONTO,TIPO,SUBTIPO,ENDERECO,BAIRRO",
                "returnGeometry" to "true",
                "outSR" to "4326",
                "resultRecordCount" to "35"
            ))).filter { reference ->
                number == null || DestinationSearchPolicy.cicomNumber(reference.alias) == number
            }.map { it.asDestination() }
        } catch (error: Exception) {
            android.util.Log.w("ViaVoxArcGIS","ArcGIS destination search unavailable",error)
            emptyList()
        }
    }

    private suspend fun fetch(parameters: Map<String,String>): String {
        val url = ManausArcGisLayer.API.toHttpUrl().newBuilder().apply {
            parameters.forEach { (key,value) -> addQueryParameter(key,value) }
            addQueryParameter("f","json")
        }.build()
        val request = Request.Builder().url(url)
            .header("User-Agent","ViaVox/0.11 Android (https://github.com/socrateslever1/ViaVox)")
            .build()
        val response = kotlinx.coroutines.suspendCancellableCoroutine<okhttp3.Response> {
            continuation ->
            val call=client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    if(continuation.isActive) continuation.resumeWith(Result.failure(e))
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    if(continuation.isActive) continuation.resume(response)
                    else response.close()
                }
            })
        }
        response.use {
            if (!it.isSuccessful)
                throw IllegalStateException("ArcGIS HTTP ${it.code}")
            return it.body?.string() ?: error("ArcGIS respondeu sem dados")
        }
    }
}
