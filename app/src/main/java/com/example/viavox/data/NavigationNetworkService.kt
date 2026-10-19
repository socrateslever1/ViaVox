package com.example.viavox.data

import com.example.viavox.model.MapPlace
import com.example.viavox.model.NavigationRoute
import com.example.viavox.model.RouteStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
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
    private val osrmBase: String = "https://router.project-osrm.org",
    private val nominatimBase: String = "https://nominatim.openstreetmap.org",
    private val arcGis: ArcGisManausPlacesService = ArcGisManausPlacesService(http)
) {
    // Public Nominatim usage is limited to one request per second. Only perform
    // explicit user-initiated searches, never autocomplete on every keystroke.
    private val nominatimLock = Any()
    private var lastNominatimRequestAt = 0L
    private val cacheLock = Any()
    private var lastAutocompleteRequestAt = 0L
    private val searchCache = mutableMapOf<String, Pair<Long, List<MapPlace>>>()

    suspend fun search(
        query: String,
        userLat: Double?,
        userLon: Double?,
        savedPlaces: List<MapPlace> = emptyList()
    ): Result<List<MapPlace>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleaned = query.trim()
            require(cleaned.length >= 3) { "Digite pelo menos três letras para pesquisar." }
            val coordinates = Regex("""^\s*(-?\d{1,2}\.\d+)\s*[,;]\s*(-?\d{1,3}\.\d+)\s*$""")
                .matchEntire(cleaned)
            if (coordinates != null) {
                val lat = coordinates.groupValues[1].toDouble()
                val lon = coordinates.groupValues[2].toDouble()
                require(lat in -90.0..90.0 && lon in -180.0..180.0) {
                    "Coordenadas inválidas."
                }
                return@runCatching listOf(MapPlace("Coordenadas informadas",lat,lon,cleaned))
            }

            val local = DestinationSearchPolicy.merge(
                DestinationSearchPolicy.localMatches(cleaned, savedPlaces),
                VerifiedManausLandmarks.find(cleaned)
            )
            val searchKey = DestinationSearchPolicy.normalize(cleaned)
            val isCicom = DestinationSearchPolicy.isCicomQuery(cleaned)
            // Police-unit abbreviations are local identifiers, not world-wide
            // business names. Never route "24 CICOM" to Cicomar in Romania.
            if (isCicom) {
                val fresh = arcGis.search(cleaned)
                return@runCatching DestinationSearchPolicy.merge(fresh,local)
            }
            val isManaus = userLat != null && userLon != null &&
                ManausArcGisLayer.inCoverage(userLat,userLon)
            val cityMatches = if (isManaus) arcGis.search(cleaned) else emptyList()
            val cached = synchronized(cacheLock) { searchCache[searchKey] }
            if (cached != null && System.currentTimeMillis() - cached.first < 600_000L) {
                return@runCatching DestinationSearchPolicy.merge(local + cityMatches,cached.second)
            }
            val variants = DestinationSearchPolicy.queryVariants(cleaned)
            var online = emptyList<MapPlace>()
            var lastError: Exception? = null
            var unsupportedPhoton = false

            // Photon is the primary provider. Do not force lang=pt: demo
            // deployments may only index en,de,fr,it and reject pt with HTTP 400.
            for (variant in variants.take(3)) {
                if (unsupportedPhoton) break
                try {
                    online = searchPhoton(variant, userLat, userLon)
                    if (online.isNotEmpty()) break
                } catch (e: Exception) {
                    lastError = e
                    unsupportedPhoton = true
                }
            }

            // One bounded Nominatim lookup is the fallback, not an autocomplete
            // backend. Respect its one-request-per-second public usage policy.
            if (online.isEmpty()) {
                try {
                    online = searchNominatim(
                        if (variants.size > 1) variants[1] else cleaned
                    )
                    if (online.isEmpty() && variants.size > 1) {
                        // Do not issue a second public Nominatim request.
                        android.util.Log.d("ViaVoxSearch", "No indexed OSM location for alias")
                    }
                } catch (e: Exception) { lastError = e }
            }

            if (online.isNotEmpty()) synchronized(cacheLock) {
                if (searchCache.size >= 80) searchCache.clear()
                searchCache[searchKey] = System.currentTimeMillis() to online
            }
            val unfiltered = if (online.isNotEmpty()) online else cached?.second.orEmpty()
            // Restrict ambiguous unit names to actual Manaus locations.
            // Never return similarly spelled businesses on another continent.
            val trusted = if (isCicom) {
                unfiltered.filter {
                    ManausArcGisLayer.inCoverage(it.latitude,it.longitude) &&
                        (DestinationSearchPolicy.normalize(it.name).contains("cicom") ||
                            DestinationSearchPolicy.normalize(it.name).contains("companhia interativa"))
                }
            } else if (userLat != null && userLon != null &&
                ManausArcGisLayer.inCoverage(userLat,userLon)
            ) {
                // Rank genuinely nearby results first, retaining global matches
                // for explicitly named distant destinations.
                unfiltered.sortedBy {
                    com.example.viavox.model.NavigationMath.meters(
                        userLat,userLon,it.latitude,it.longitude
                    )
                }
            } else unfiltered
            val combined = DestinationSearchPolicy.merge(local + cityMatches,trusted)
            if (combined.isNotEmpty()) return@runCatching combined
            if (lastError != null) {
                throw IllegalStateException(
                    "A busca online está indisponível. Tente novamente ou segure um ponto no mapa.",
                    lastError
                )
            }
            emptyList()
        }
    }

    /**
     * Explicit, debounced autocomplete. The public Nominatim endpoint is NEVER
     * used for autocomplete. Local ArcGIS records and user bookmarks come first.
     */
    suspend fun suggest(
        query: String, userLat: Double?, userLon: Double?,
        savedPlaces: List<MapPlace> = emptyList()
    ): List<MapPlace> = withContext(Dispatchers.IO) {
        val text = query.trim()
        if (text.length < 3) return@withContext emptyList()
        val local = DestinationSearchPolicy.merge(
            DestinationSearchPolicy.localMatches(text,savedPlaces),
            VerifiedManausLandmarks.find(text)
        )
        val isCicom = DestinationSearchPolicy.isCicomQuery(text)
        val isManaus = userLat != null && userLon != null &&
            ManausArcGisLayer.inCoverage(userLat,userLon)
        val gis = if (isCicom || isManaus) {
            runCatching { arcGis.search(text) }.getOrDefault(emptyList())
        } else emptyList()
        if (isCicom) return@withContext DestinationSearchPolicy.merge(gis,local).take(6)
        val immediate = DestinationSearchPolicy.merge(local,gis)
        if (immediate.size >= 5) return@withContext immediate.take(6)

        val key = DestinationSearchPolicy.normalize(text)
        val cached = synchronized(cacheLock) { searchCache[key] }
        if (cached != null && System.currentTimeMillis() - cached.first < 600_000L)
            return@withContext DestinationSearchPolicy.merge(immediate,cached.second).take(6)

        // Throttle the public Photon demo endpoint to protect its resources.
        val now = System.currentTimeMillis()
        val shouldQuery = synchronized(cacheLock) {
            if (now - lastAutocompleteRequestAt < 1_200L) false
            else { lastAutocompleteRequestAt = now; true }
        }
        if (!shouldQuery) return@withContext immediate.take(6)
        val candidates = runCatching {
            searchPhoton(text,userLat,userLon)
        }.getOrDefault(emptyList())
        val ranked = if (isManaus) candidates.filter {
            com.example.viavox.model.NavigationMath.meters(
                userLat!!,userLon!!,it.latitude,it.longitude
            ) < 120_000.0
        } else candidates
        if (ranked.isNotEmpty()) synchronized(cacheLock) {
            if (searchCache.size >= 80) searchCache.clear()
            searchCache[key] = System.currentTimeMillis() to ranked
        }
        DestinationSearchPolicy.merge(immediate,ranked).take(6)
    }

    private fun searchPhoton(
        query: String, userLat: Double?, userLon: Double?
    ): List<MapPlace> {
        val builder = photonBase.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegment("api")
            .addQueryParameter("q",query)
            .addQueryParameter("limit","8")
        if (userLat != null && userLon != null &&
            userLat in -90.0..90.0 && userLon in -180.0..180.0
        ) {
            builder.addQueryParameter("lat",userLat.toString())
            builder.addQueryParameter("lon",userLon.toString())
        }
        val request = Request.Builder().url(builder.build())
            .header("User-Agent","ViaVox/0.7 (https://github.com/socrateslever1/ViaVox)")
            // Avoid forcing unsupported Photon languages on demo instances.
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Photon HTTP ${response.code}")
            return parsePhoton(response.body?.string().orEmpty())
        }
    }

    private fun searchNominatim(query: String): List<MapPlace> {
        val uri = nominatimBase.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addQueryParameter("q",query)
            .addQueryParameter("format","jsonv2")
            .addQueryParameter("limit","6")
            .addQueryParameter("addressdetails","1")
            .build()
        synchronized(nominatimLock) {
            val remaining = 1_100L - (System.currentTimeMillis() - lastNominatimRequestAt)
            if (remaining > 0) Thread.sleep(remaining)
            lastNominatimRequestAt = System.currentTimeMillis()
        }
        val request = Request.Builder().url(uri)
            .header("User-Agent","ViaVox/0.7 (https://github.com/socrateslever1/ViaVox)")
            .header("Accept-Language","pt-BR,pt;q=0.9")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Nominatim HTTP ${response.code}")
            return parseNominatim(response.body?.string().orEmpty())
        }
    }

    suspend fun route(startLat: Double, startLon: Double, destination: MapPlace): Result<NavigationRoute> =
        routeAlternatives(startLat,startLon,destination).mapCatching { routes ->
            routes.firstOrNull() ?: error("No route returned by routing service.")
        }

    suspend fun routeAlternatives(
        startLat: Double,
        startLon: Double,
        destination: MapPlace
    ): Result<List<NavigationRoute>> =
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
                    .addQueryParameter("alternatives", "true")
                    .build()
                http.newCall(
                    Request.Builder().url(endpoint)
                        .header("User-Agent", "ViaVox-Android/0.6 (routing)").build()
                ).execute().use { response ->
                    require(response.isSuccessful) { "Serviço de rotas indisponível (${response.code})." }
                    parseOsrmAlternatives(response.body?.string().orEmpty(), destination)
                }
            }
        }

    companion object {
        fun parseNominatim(body: String): List<MapPlace> {
            val items = org.json.JSONArray(body)
            val out = mutableListOf<MapPlace>()
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val lat = item.optString("lat").toDoubleOrNull() ?: continue
                val lon = item.optString("lon").toDoubleOrNull() ?: continue
                val addr = item.optJSONObject("address")
                val name = listOfNotNull(
                    item.optString("name").takeIf(String::isNotBlank),
                    addr?.optString("road")?.takeIf(String::isNotBlank),
                    item.optString("display_name").substringBefore(",").takeIf(String::isNotBlank)
                ).firstOrNull() ?: continue
                val subtitle = item.optString("display_name")
                out += MapPlace(name,lat,lon,subtitle)
            }
            return out
        }

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
                    val exit = maneuver.optInt("exit", 0)
                    val road = current.optString("name")
                    val message = maneuverText(type, modifier, road, exit)
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

        fun parseOsrmAlternatives(body: String, destination: MapPlace): List<NavigationRoute> {
            val json = JSONObject(body)
            require(json.optString("code") == "Ok") { "Route not found for the selected destination." }
            val entries = json.optJSONArray("routes") ?: return emptyList()
            val routes = mutableListOf<NavigationRoute>()
            for (index in 0 until entries.length()) {
                val route = entries.optJSONObject(index) ?: continue
                val singleRouteResponse = JSONObject()
                    .put("code", "Ok")
                    .put("routes", JSONArray().put(route))
                runCatching { parseOsrm(singleRouteResponse.toString(), destination) }
                    .getOrNull()
                    ?.let(routes::add)
            }
            return routes
                .distinctBy { route -> route.coordinates.joinToString(";") { "${it.first},${it.second}" } }
                .sortedWith(compareBy<NavigationRoute> { it.durationSeconds }.thenBy { it.distanceMeters })
        }

        fun maneuverText(type: String, modifier: String, road: String, exit: Int = 0): String {
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
                "fork" -> when (modifier) {
                    "left", "slight left" -> "Mantenha-se à esquerda${to}"
                    "right", "slight right" -> "Mantenha-se à direita${to}"
                    else -> "Mantenha-se na pista${to}"
                }
                "roundabout", "rotary", "roundabout turn" -> if (exit > 0) {
                    "Na rotatória, pegue a ${exit}ª saída${to}"
                } else {
                    "Entre na rotatória${to}"
                }
                "merge", "on ramp", "off ramp" -> "Acesse a via${to}"
                "continue", "new name" -> "Continue${to}"
                else -> ""
            }
        }
    }
}
