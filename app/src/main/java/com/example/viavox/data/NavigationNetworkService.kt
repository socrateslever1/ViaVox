package com.example.viavox.data

import com.example.viavox.model.MapPlace
import com.example.viavox.model.NavigationRoute
import com.example.viavox.model.RouteStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OpenStreetMap-based search and routing.
 * Public Photon and OSRM endpoints are DEMO providers, not a production SLA.
 * Run own instances / configure a contracted provider before public launch.
 */
class NavigationNetworkService(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(14, TimeUnit.SECONDS)
        .build(),
    private val photonBase: String = "https://photon.komoot.io",
    private val osrmBase: String = "https://router.project-osrm.org"
) {
    suspend fun search(query: String, userLat: Double?, userLon: Double?): Result<List<MapPlace>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cleaned = query.trim()
                require(cleaned.length >= 3) { "Digite pelo menos três letras para pesquisar." }
                val coordinateMatch = Regex("""^\s*(-?\d{1,2}\.\d+)\s*[,;]\s*(-?\d{1,3}\.\d+)\s*$""")
                    .matchEntire(cleaned)
                if (coordinateMatch != null) {
                    val lat = coordinateMatch.groupValues[1].toDouble()
                    val lon = coordinateMatch.groupValues[2].toDouble()
                    require(lat in -90.0..90.0 && lon in -180.0..180.0)
                    return@runCatching listOf(MapPlace("Coordenadas informadas", lat, lon, cleaned))
                }
                val base = photonBase.trimEnd('/').toHttpUrl().newBuilder()
                    .addPathSegment("api")
                    .addQueryParameter("q", cleaned)
                    .addQueryParameter("limit", "8")
                    .addQueryParameter("lang", "pt")
                if (userLat != null && userLon != null &&
                    userLat in -90.0..90.0 && userLon in -180.0..180.0
                ) {
                    base.addQueryParameter("lat", userLat.toString())
                    base.addQueryParameter("lon", userLon.toString())
                }
                http.newCall(
                    Request.Builder().url(base.build()).header("User-Agent", "ViaVox-Android/0.6 (geocoding)").build()
                ).execute().use { response ->
                    require(response.isSuccessful) { "Busca temporariamente indisponível (${response.code})." }
                    parsePhoton(response.body?.string().orEmpty())
                }
            }
        }

    suspend fun route(startLat: Double, startLon: Double, destination: MapPlace): Result<NavigationRoute> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(startLat in -90.0..90.0 && startLon in -180.0..180.0)
                val coords = "${startLon},${startLat};${destination.longitude},${destination.latitude}"
                val endpoint = osrmBase.trimEnd('/').toHttpUrl().newBuilder()
                    .addPathSegments("route/v1/driving")
                    .addPathSegment(coords)
                    .addQueryParameter("overview", "full")
                    .addQueryParameter("geometries", "geojson")
                    .addQueryParameter("steps", "true")
                    .addQueryParameter("alternatives", "false")
                    .build()
                http.newCall(
                    Request.Builder().url(endpoint)
                        .header("User-Agent", "ViaVox-Android/0.6 (routing)").build()
                ).execute().use { response ->
                    require(response.isSuccessful) { "Serviço de rotas indisponível (${response.code})." }
                    parseOsrm(response.body?.string().orEmpty(), destination)
                }
            }
        }

    companion object {
        fun parsePhoton(body: String): List<MapPlace> {
            val array = JSONObject(body).optJSONArray("features") ?: return emptyList()
            val places = mutableListOf<MapPlace>()
            for (i in 0 until array.length()) {
                val feature = array.optJSONObject(i) ?: continue
                val properties = feature.optJSONObject("properties") ?: continue
                val coordinates = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
                if (coordinates.length() < 2) continue
                val lat = coordinates.optDouble(1, Double.NaN)
                val lon = coordinates.optDouble(0, Double.NaN)
                if (!lat.isFinite() || !lon.isFinite()) continue
                val name = properties.optString("name").ifBlank {
                    properties.optString("street").ifBlank { "Local sem nome" }
                }
                val detail = listOf(
                    properties.optString("street"),
                    properties.optString("district"),
                    properties.optString("city"),
                    properties.optString("state")
                ).filter { it.isNotBlank() && it != name }.distinct().joinToString(" · ")
                val point = MapPlace(name, lat, lon, detail)
                if (places.none { it.name == name && it.latitude == lat && it.longitude == lon }) places.add(point)
            }
            return places
        }

        fun parseOsrm(body: String, destination: MapPlace): NavigationRoute {
            val json = JSONObject(body)
            require(json.optString("code") == "Ok") { "Rota não encontrada para o destino informado." }
            val route = json.optJSONArray("routes")?.optJSONObject(0)
                ?: error("O serviço não retornou uma rota.")
            val geometry = route.getJSONObject("geometry").getJSONArray("coordinates")
            val points = mutableListOf<Pair<Double, Double>>()
            for (i in 0 until geometry.length()) {
                val item = geometry.getJSONArray(i)
                points += item.getDouble(1) to item.getDouble(0)
            }
            require(points.size >= 2) { "Rota sem geometria válida." }
            val steps = mutableListOf<RouteStep>()
            val legs = route.optJSONArray("legs")
            if (legs != null) for (i in 0 until legs.length()) {
                val entries = legs.getJSONObject(i).optJSONArray("steps") ?: continue
                for (j in 0 until entries.length()) {
                    val current = entries.getJSONObject(j)
                    val maneuver = current.optJSONObject("maneuver") ?: continue
                    val loc = maneuver.optJSONArray("location") ?: continue
                    val type = maneuver.optString("type")
                    val modifier = maneuver.optString("modifier")
                    val road = current.optString("name")
                    val message = maneuverText(type, modifier, road)
                    if (message.isNotBlank()) {
                        steps += RouteStep(loc.getDouble(1), loc.getDouble(0),
                            message, current.optDouble("distance", 0.0))
                    }
                }
            }
            return NavigationRoute(
                destination, points,
                route.getDouble("distance"),
                route.getDouble("duration"),
                steps
            )
        }

        fun maneuverText(type: String, modifier: String, road: String): String {
            val to = road.takeIf { it.isNotBlank() }?.let { " na $it" }.orEmpty()
            return when (type) {
                "depart" -> "Siga em frente${to}"
                "arrive" -> "Você chegou ao destino"
                "turn", "end of road" -> when (modifier) {
                    "left", "slight left", "sharp left" -> "Vire à esquerda${to}"
                    "right", "slight right", "sharp right" -> "Vire à direita${to}"
                    "uturn" -> "Faça o retorno${to}"
                    else -> "Continue${to}"
                }
                "roundabout", "rotary", "roundabout turn" -> "Entre na rotatória${to}"
                "merge", "on ramp", "off ramp" -> "Acesse${to}"
                "continue", "new name" -> "Continue${to}"
                else -> ""
            }
        }
    }
}
