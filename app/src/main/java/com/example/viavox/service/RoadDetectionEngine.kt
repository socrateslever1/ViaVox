package com.example.viavox.service

import android.location.Location
import com.example.viavox.data.NominatimService
import com.example.viavox.model.Place
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.Normalizer
import kotlin.math.cos
import kotlin.math.hypot

data class GeoFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float = 10f,
    val speedMps: Float = 0f,
    val bearing: Float = 0f,
    val hasBearing: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis()
)

class RoadDetectionEngine(
    private val nominatimService: NominatimService = NominatimService(),
    private val coroutineScope: CoroutineScope,
    private val onPlaceUpdated: (Place) -> Unit,
    private val onRoadAnnounceNeeded: (message: String, isFirstRoad: Boolean) -> Unit,
    private val onStatusMessage: (String) -> Unit,
    private val reverseGeocodeProvider: suspend (Double, Double) -> Result<Place> = { lat, lon ->
        nominatimService.reverseGeocode(lat, lon)
    },
    private val onGeographicTransition: ((GeographicTransition) -> Unit)? = null
) {

    private var activeQueryJob: Job? = null
    private var localRoadJob: Job? = null
    private var pendingLocalStreet: String? = null
    private var pendingLocalStreetFix: GeoFix? = null
    private var latestLocalConfirmedTimeMs = 0L
    /** Optional, network-free, indexed OSM road polyline lookup. */
    var localRoadLookup: suspend (GeoFix) -> ConfidentStreet? = { null }
    var cityNameForCode: (Int) -> String = { "" }
    private var newestFix: GeoFix? = null
    private var lastQueryTimestamp = 0L
    private var lastQueryLat = 0.0
    private var lastQueryLon = 0.0
    private var lastQuerySucceeded = false

    private var currentConfirmedRoad: String? = null
    private var currentConfirmedVenue: String? = null
    private var lastAnnouncedNeighbourhood: String? = null
    private var lastAnnouncedZone: String? = null
    private var pendingZone: String? = null
    private var pendingZoneCount = 0
    private var pendingNeighbourhood: String? = null
    private var pendingNeighbourhoodCount = 0
    private var pendingCandidateRoad: String? = null
    private var pendingCandidatePlace: Place? = null
    private var pendingCandidateCount = 0
    private var pendingCandidateStartLat = 0.0
    private var pendingCandidateStartLon = 0.0

    /** Territorial labels are tracked independently of road changes. */
    private class EntryState {
        private var current: String? = null
        private var pending: String? = null
        private var pendingCount = 0

        fun clear() { current = null; pending = null; pendingCount = 0 }

        fun changedTo(value: String?, accuracy: Float): String? {
            val candidate = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
            val key = normalizeForComparison(candidate)
            if (current != null && normalizeForComparison(current!!) == key) {
                pending = null
                pendingCount = 0
                return null
            }
            // Accurate mapped addresses are announced at their first confirmed
            // lookup. Less accurate fixes need two consistent map responses.
            if (accuracy > 25f) {
                if (pending != null && normalizeForComparison(pending!!) == key) pendingCount++
                else { pending = candidate; pendingCount = 1 }
                if (pendingCount < 2) return null
            }
            current = candidate
            pending = null
            pendingCount = 0
            return candidate
        }
    }

    private val spokenZone = EntryState()
    private val spokenNeighbourhood = EntryState()
    private val spokenCicom = EntryState()
    private val spokenCpa = EntryState()

    private fun polygonTransition(match: TerritoryMatch, accuracy: Float): GeographicTransition =
        GeographicTransition(
            zone = spokenZone.changedTo(match.zone, accuracy),
            neighbourhood = spokenNeighbourhood.changedTo(match.neighbourhood, accuracy),
            cicom = if (enablePoliceCircumscription)
                spokenCicom.changedTo(match.cicom, accuracy) else null,
            cpa = if (enablePoliceCircumscription)
                spokenCpa.changedTo(match.cpa, accuracy) else null
        )

    private fun areaTransition(place: Place, fix: GeoFix): GeographicTransition {
        val polygon = territoryPolygonLookup(fix)
        val jurisdiction = if (enablePoliceCircumscription)
            PoliceJurisdictionSpeechResolver.resolve(
                polygon?.cicom ?: place.policeDistrict,
                if (polygon != null) "Manaus" else place.city
            ) else null
        return polygonTransition(
            TerritoryMatch(
                zone = polygon?.zone ?: place.zone,
                neighbourhood = polygon?.neighbourhood ?:
                    place.neighbourhood.takeIf { place.hasValidNeighbourhood() },
                cicom = polygon?.cicom ?: jurisdiction?.cicom,
                cpa = polygon?.cpa ?: jurisdiction?.cpa
            ),
            fix.accuracyMeters
        )
    }

    private fun publish(territory: GeographicTransition, road: String? = null) {
        val announcement = territory.copy(road = road)
        if (!announcement.isEmpty()) onGeographicTransition?.invoke(announcement)
    }

    /** Optional GPS polygons loaded from bundled assets by the tracking service. */
    var territoryPolygonLookup: (GeoFix) -> TerritoryMatch? = { null }

    var enablePoliceCircumscription: Boolean = true

    fun getCurrentConfirmedRoad(): String? = currentConfirmedRoad
    fun getCurrentConfirmedVenue(): String? = currentConfirmedVenue

    fun reset() {
        activeQueryJob?.cancel()
        localRoadJob?.cancel()
        activeQueryJob = null
        localRoadJob = null
        pendingLocalStreet = null
        pendingLocalStreetFix = null
        latestLocalConfirmedTimeMs = 0L
        currentConfirmedRoad = null
        currentConfirmedVenue = null
        lastAnnouncedNeighbourhood = null
        lastAnnouncedZone = null
        pendingZone = null
        pendingZoneCount = 0
        pendingNeighbourhood = null
        pendingNeighbourhoodCount = 0
        pendingCandidateRoad = null
        pendingCandidatePlace = null
        pendingCandidateCount = 0
        lastQueryTimestamp = 0L
        lastQueryLat = 0.0
        lastQueryLon = 0.0
        lastQuerySucceeded = false
        newestFix = null
        spokenZone.clear()
        spokenNeighbourhood.clear()
        spokenCicom.clear()
        spokenCpa.clear()
    }

    fun onLocationUpdate(location: Location) {
        onGeoFixUpdate(
            GeoFix(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = if (location.hasAccuracy()) location.accuracy else 20f,
                speedMps = if (location.hasSpeed()) location.speed else 0f,
                bearing = if (location.hasBearing()) location.bearing else 0f,
                hasBearing = location.hasBearing(),
                timestampMs = if (location.time > 0L) location.time else System.currentTimeMillis()
            )
        )
    }

    /**
     * Evaluates whether an address lookup is warranted given current location, speed, and time.
     * Works both when the user is stationary (initial street identification) and while moving.
     */
    fun onGeoFixUpdate(fix: GeoFix) {
        val lat = fix.latitude
        val lon = fix.longitude
        val accuracy = fix.accuracyMeters
        newestFix = fix
        val now = fix.timestampMs

        if (accuracy > MAX_ACCEPTABLE_ACCURACY_METERS) {
            onStatusMessage("Sinal GPS impreciso (±${accuracy.toInt()} m)")
            return
        }

        // Cached offline boundaries take priority. Entering a zone, bairro,
        // CICOM or CPA must not wait for Nominatim's street name.
        territoryPolygonLookup(fix)?.let { match ->
            publish(polygonTransition(match, accuracy))
        }
        if (accuracy in 0.1f..12f && localRoadJob?.isActive != true) {
            localRoadJob = coroutineScope.launch {
                try {
                    val match = localRoadLookup(fix)
                    if (match == null) {
                        // An uncertain crossing interrupts candidate confirmation.
                        pendingLocalStreet = null
                        pendingLocalStreetFix = null
                        return@launch
                    }
                    val newest = newestFix ?: return@launch
                    if (newest.timestampMs < fix.timestampMs ||
                        newest.timestampMs - fix.timestampMs > 3_000L ||
                        calculateMeters(newest.latitude,newest.longitude,
                            fix.latitude,fix.longitude) > maxOf(15.0,
                            fix.accuracyMeters.toDouble()*2.0+5.0)) return@launch
                    processLocalGeometryMatch(match,fix)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    onStatusMessage("Consulta local de vias indisponível; buscando online")
                }
            }
        }

        val metersMoved = if (lastQueryLat != 0.0 || lastQueryLon != 0.0) {
            calculateMeters(lastQueryLat, lastQueryLon, lat, lon)
        } else {
            Double.POSITIVE_INFINITY
        }

        val speedMps = fix.speedMps
        val minInterval = when {
            !lastQuerySucceeded -> 2_500L
            pendingCandidateRoad != null || pendingNeighbourhood != null -> 2_500L
            speedMps > 8.3f -> 3_500L // > 30 km/h: fast response on avenues
            speedMps > 3.0f -> 3_000L // 11-30 km/h: urban neighborhood streets
            else -> 6_000L           // Stationary / parking speed
        }
        val minDistance = when {
            pendingCandidateRoad != null || pendingNeighbourhood != null -> 8.0
            speedMps > 8.3f -> 14.0
            speedMps > 3.0f -> 9.0
            else -> 12.0
        }

        val isFirstLookup = lastQueryTimestamp == 0L
        val hasUnconfirmedInitialRoad = currentConfirmedRoad == null && (now - lastQueryTimestamp >= minInterval)
        val hasMovedEnough = (now - lastQueryTimestamp >= minInterval) &&
                (metersMoved >= minDistance || now - lastQueryTimestamp >= 40_000L)

        // Do not continually cancel a slow reverse-geocoding request while
        // walking or driving: otherwise the street name may never resolve.
        if ((isFirstLookup || hasUnconfirmedInitialRoad || hasMovedEnough) &&
            activeQueryJob?.isActive != true) {
            lastQueryTimestamp = now
            lastQueryLat = lat
            lastQueryLon = lon
            queryReverseGeocode(lat, lon, fix)
        }
    }

    /**
     * First street: one unambiguous accurate polyline fix is sufficient.
     * Street changes: require the same confidently matched name on two
     * distinct GPS fixes with >=5 metres displacement. No second HTTP call.
     */
    private fun processLocalGeometryMatch(match: ConfidentStreet, fix: GeoFix) {
        val normalizedName = normalizeForComparison(match.name)
        val currentName = currentConfirmedRoad?.let(::normalizeForComparison)
        if (normalizedName == currentName) {
            pendingLocalStreet = null
            pendingLocalStreetFix = null
            return
        }
        if (currentName != null) {
            if (fix.speedMps < 0.85f) return
            val previous = pendingLocalStreetFix
            if (pendingLocalStreet != normalizedName || previous == null ||
                fix.timestampMs <= previous.timestampMs ||
                fix.timestampMs - previous.timestampMs > 6_000L) {
                pendingLocalStreet = normalizedName
                pendingLocalStreetFix = fix
                return
            }
            val progressed = calculateMeters(previous.latitude,previous.longitude,
                fix.latitude,fix.longitude)
            if (progressed < 5.0) return
        }
        pendingLocalStreet = null
        pendingLocalStreetFix = null
        val polygon = territoryPolygonLookup(fix)
        val place = Place(
            road = match.name,
            neighbourhood = polygon?.neighbourhood.orEmpty(),
            zone = polygon?.zone.orEmpty(),
            policeDistrict = polygon?.cicom.orEmpty(),
            city = cityNameForCode(match.cityCode)
        )
        latestLocalConfirmedTimeMs = fix.timestampMs
        lastQuerySucceeded = true
        onPlaceUpdated(place)
        processRoadDecision(place, fix, geometricallyVerified = true)
    }

    private fun queryReverseGeocode(lat: Double, lon: Double, fix: GeoFix) {
        activeQueryJob = coroutineScope.launch {
            try {
                val result = reverseGeocodeProvider(lat, lon)
                result.onSuccess { rawPlace ->
                    // Do not let an old network address overwrite a newer
                    // GPS-confirmed road from local street geometry.
                    if (latestLocalConfirmedTimeMs != 0L && latestLocalConfirmedTimeMs >= fix.timestampMs) return@onSuccess
                    // A slow response to an old coordinate cannot reannounce
                    // a road the user has already passed.
                    val latest = newestFix
                    if (latest != null && calculateMeters(
                            lat, lon, latest.latitude, latest.longitude
                        ) > maxOf(35.0, fix.accuracyMeters * 2.0 + 12.0)) {
                        lastQuerySucceeded = false
                        lastQueryTimestamp = 0L
                        return@onSuccess
                    }
                    lastQuerySucceeded = true
                    val normalizedRoad = normalizeRoadName(rawPlace.road)
                    val local = territoryPolygonLookup(fix)
                    val cleanPlace = rawPlace.copy(
                        road = normalizedRoad,
                        neighbourhood = local?.neighbourhood ?: rawPlace.neighbourhood,
                        zone = local?.zone ?: rawPlace.zone,
                        policeDistrict = local?.cicom ?: rawPlace.policeDistrict,
                        city = if (local != null) "Manaus" else rawPlace.city
                    )

                    onPlaceUpdated(cleanPlace)
                    onStatusMessage("Localização ativa")

                    processRoadDecision(cleanPlace, fix)
                }.onFailure { e ->
                    lastQuerySucceeded = false
                    if (e !is CancellationException) {
                        onStatusMessage("Sem conexão para consultar endereço")
                    }
                }
            } catch (_: CancellationException) {
                // Outdated query cancelled in favor of fresher coordinates
            }
        }
    }

    internal fun processRoadDecision(
        place: Place, fix: GeoFix, geometricallyVerified: Boolean = false
    ) {
        val road = normalizeRoadName(place.road)
        val normalizedPlace = place.copy(road = road)
        val territory = areaTransition(normalizedPlace, fix)
        if (!normalizedPlace.hasValidRoad()) {
            // Zone, CICOM and bairro may be confirmed even if the street is unknown.
            publish(territory)
            return
        }
        val normalized = normalizeForComparison(road)
        val currentNormalized = currentConfirmedRoad?.let { normalizeForComparison(it) }

        // 1. First identified road (works both stationary and moving): announce immediately!
        if (currentNormalized == null) {
            currentConfirmedRoad = road
            lastAnnouncedNeighbourhood = normalizedPlace.neighbourhood.takeIf { normalizedPlace.hasValidNeighbourhood() }
            lastAnnouncedZone = normalizedPlace.zone.takeIf { it.isNotBlank() }
            pendingNeighbourhood = null
            pendingNeighbourhoodCount = 0
            pendingCandidateRoad = null
            pendingCandidatePlace = null
            pendingCandidateCount = 0

            val message = normalizedPlace.toSpeechMessage(isTransition = false)
            onRoadAnnounceNeeded(message, true)
            publish(territory, road)
            return
        }

        // 2. Still on the same confirmed road: reset any transient candidate and do not repeat
        if (normalized == currentNormalized) {
            pendingCandidateRoad = null
            pendingCandidatePlace = null
            pendingCandidateCount = 0

            val newZone = confirmedZoneTransition(normalizedPlace.zone)
            var newNeighbourhood: String? = null
            // A road may cross neighbourhood boundaries without changing its name.
            // Require two consecutive address results before announcing the new area.
            if (normalizedPlace.hasValidNeighbourhood() &&
                !sameNeighbourhood(lastAnnouncedNeighbourhood, normalizedPlace.neighbourhood)
            ) {
                if (sameNeighbourhood(pendingNeighbourhood, normalizedPlace.neighbourhood)) {
                    pendingNeighbourhoodCount++
                } else {
                    pendingNeighbourhood = normalizedPlace.neighbourhood
                    pendingNeighbourhoodCount = 1
                }
                if (pendingNeighbourhoodCount >= 2) {
                    lastAnnouncedNeighbourhood = normalizedPlace.neighbourhood
                    pendingNeighbourhood = null
                    pendingNeighbourhoodCount = 0
                    newNeighbourhood = normalizedPlace.neighbourhood
                    onRoadAnnounceNeeded(
                        normalizedPlace.toNeighbourhoodTransitionMessage(includePolice = enablePoliceCircumscription),
                        false
                    )
                }
            } else {
                pendingNeighbourhood = null
                pendingNeighbourhoodCount = 0
            }
            publish(territory)
            return
        }

        if (geometricallyVerified && fix.accuracyMeters <= 12f &&
            fix.speedMps >= 0.85f) {
            // The two GPS samples have already been compared against distinct
            // OSM road polylines; do not wait for another reverse-geocoding trip.
            currentConfirmedRoad = road
            currentConfirmedVenue = null
            pendingCandidateRoad = null
            pendingCandidatePlace = null
            pendingCandidateCount = 0
            pendingNeighbourhood = null
            pendingNeighbourhoodCount = 0
            val bairroChanged = normalizedPlace.hasValidNeighbourhood() &&
                !sameNeighbourhood(lastAnnouncedNeighbourhood,normalizedPlace.neighbourhood)
            if (normalizedPlace.hasValidNeighbourhood())
                lastAnnouncedNeighbourhood = normalizedPlace.neighbourhood
            if (normalizedPlace.zone.isNotBlank())
                lastAnnouncedZone = normalizedPlace.zone
            onRoadAnnounceNeeded(normalizedPlace.toSpeechMessage(
                isTransition = true, includeNeighbourhood = bairroChanged),false)
            publish(territory,road)
            return
        }

        // VENUE / NOTABLE BUILDING RECOGNITION & SHIELD:
        // If the user arrived at or is inside a notable landmark/building/complex (hospital, college, mall, public building):
        if (normalizedPlace.hasVenue()) {
            if (currentConfirmedVenue != normalizedPlace.venueName) {
                currentConfirmedVenue = normalizedPlace.venueName
                onRoadAnnounceNeeded(normalizedPlace.toSpeechMessage(isTransition = false), false)
            }
            pendingCandidateRoad = null
            pendingCandidatePlace = null
            pendingCandidateCount = 0
            publish(territory)
            return
        }

        // If the user entered from a confirmed road and is now stationary or crawling (< 2.2 m/s, e.g. parking lot,
        // deep inside a store or condo), DO NOT switch to a parallel/back street. A car does not hop fences.
        if (fix.speedMps < 0.35f) {
            pendingCandidateRoad = null
            pendingCandidatePlace = null
            pendingCandidateCount = 0
            publish(territory)
            return
        }

        // 3. Candidate new road detected
        val pendingNorm = pendingCandidateRoad?.let { normalizeForComparison(it) }

        if (pendingNorm == normalized) {
            pendingCandidateCount++
            val distOnCandidate = calculateMeters(
                pendingCandidateStartLat,
                pendingCandidateStartLon,
                fix.latitude,
                fix.longitude
            )

            // Fast transition:
            // - 2+ readings with >= 10m displacement along the new street, OR
            // - Clean vehicle driving (speed >= 3.5 m/s or ~12 km/h) with >= 14m displacement, OR
            // - 3+ consecutive readings
            val isConfirmed = (pendingCandidateCount >= 2 && distOnCandidate >= 10.0) ||
                    (fix.speedMps >= 3.5f && distOnCandidate >= 14.0) ||
                    (pendingCandidateCount >= 3)

            if (isConfirmed) {
                currentConfirmedRoad = road
                pendingCandidateRoad = null
                pendingCandidatePlace = null
                pendingCandidateCount = 0
                pendingNeighbourhood = null
                pendingNeighbourhoodCount = 0

                // Mention the area only on first identification or when it changes.
                val zoneChanged = normalizedPlace.zone.isNotBlank() &&
                    !sameNeighbourhood(lastAnnouncedZone, normalizedPlace.zone)
                if (normalizedPlace.zone.isNotBlank()) lastAnnouncedZone = normalizedPlace.zone
                pendingZone = null
                pendingZoneCount = 0
                val neighbourhoodChanged = normalizedPlace.hasValidNeighbourhood() &&
                    !sameNeighbourhood(lastAnnouncedNeighbourhood, normalizedPlace.neighbourhood)
                if (normalizedPlace.hasValidNeighbourhood()) {
                    lastAnnouncedNeighbourhood = normalizedPlace.neighbourhood
                }
                val message = normalizedPlace.toSpeechMessage(
                    isTransition = true,
                    includeNeighbourhood = neighbourhoodChanged
                )
                onRoadAnnounceNeeded(message, false)
                publish(territory, road)
            }
        } else {
            // First sighting of a candidate road (protects against single-point GPS bounce at intersections)
            pendingCandidateRoad = road
            pendingCandidatePlace = normalizedPlace
            pendingCandidateCount = 1
            pendingCandidateStartLat = fix.latitude
            pendingCandidateStartLon = fix.longitude
        }
        if (normalized != normalizeForComparison(currentConfirmedRoad.orEmpty())) publish(territory)
    }

    /** Require two consistent geocoder readings for zone-only transitions. */
    private fun confirmedZoneTransition(zone: String): String? {
        if (zone.isBlank() || sameNeighbourhood(lastAnnouncedZone,zone)) {
            pendingZone = null
            pendingZoneCount = 0
            return null
        }
        if (sameNeighbourhood(pendingZone,zone)) pendingZoneCount++
        else {
            pendingZone = zone
            pendingZoneCount = 1
        }
        if (pendingZoneCount < 2) return null
        lastAnnouncedZone = zone
        pendingZone = null
        pendingZoneCount = 0
        return zone
    }

    private fun sameNeighbourhood(previous: String?, current: String): Boolean =
        previous != null && normalizeForComparison(previous) == normalizeForComparison(current)

    companion object {
        const val MAX_ACCEPTABLE_ACCURACY_METERS = 45f

        fun calculateMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = (lat2 - lat1) * 111000.0
            val dLon = (lon2 - lon1) * 111000.0 * cos(Math.toRadians((lat1 + lat2) / 2.0))
            return hypot(dLat, dLon)
        }

        fun normalizeRoadName(name: String): String {
            var s = name.trim().replace(Regex("\\s+"), " ")
            if (s.isBlank()) return Place.UNKNOWN_ROAD
            val replacements = listOf(
                Pair(Regex("(?i)^R\\.\\s*"), "Rua "),
                Pair(Regex("(?i)^Rua\\s+"), "Rua "),
                Pair(Regex("(?i)^Av\\.\\s*"), "Avenida "),
                Pair(Regex("(?i)^Avenida\\s+"), "Avenida "),
                Pair(Regex("(?i)^Trav\\.\\s*"), "Travessa "),
                Pair(Regex("(?i)^Tv\\.\\s*"), "Travessa "),
                Pair(Regex("(?i)^Al\\.\\s*"), "Alameda "),
                Pair(Regex("(?i)^Rod\\.\\s*"), "Rodovia "),
                Pair(Regex("(?i)^Est\\.\\s*"), "Estrada "),
                Pair(Regex("(?i)^Pç\\.\\s*"), "Praça "),
                Pair(Regex("(?i)^Pc\\.\\s*"), "Praça ")
            )
            for ((regex, replacement) in replacements) {
                if (regex.containsMatchIn(s)) {
                    s = s.replaceFirst(regex, replacement)
                    break
                }
            }
            return s
        }

        fun normalizeForComparison(name: String): String {
            val expanded = normalizeRoadName(name)
            val normalized = Normalizer.normalize(expanded.lowercase().trim(), Normalizer.Form.NFD)
            return normalized.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
                .replace(Regex("[^a-z0-9]"), "")
        }
    }
}
