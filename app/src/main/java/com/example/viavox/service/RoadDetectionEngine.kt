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

    private fun areaTransition(place: Place, fix: GeoFix): GeographicTransition {
        val jurisdiction = if (enablePoliceCircumscription)
            PoliceJurisdictionSpeechResolver.resolve(place.policeDistrict, place.city)
        else null
        return GeographicTransition(
            zone = spokenZone.changedTo(place.zone, fix.accuracyMeters),
            neighbourhood = spokenNeighbourhood.changedTo(
                place.neighbourhood.takeIf { place.hasValidNeighbourhood() },
                fix.accuracyMeters
            ),
            cicom = spokenCicom.changedTo(jurisdiction?.cicom, fix.accuracyMeters),
            cpa = spokenCpa.changedTo(jurisdiction?.cpa, fix.accuracyMeters)
        )
    }

    private fun publish(territory: GeographicTransition, road: String? = null) {
        val announcement = territory.copy(road = road)
        if (!announcement.isEmpty()) onGeographicTransition?.invoke(announcement)
    }

    var enablePoliceCircumscription: Boolean = true

    fun getCurrentConfirmedRoad(): String? = currentConfirmedRoad
    fun getCurrentConfirmedVenue(): String? = currentConfirmedVenue

    fun reset() {
        activeQueryJob?.cancel()
        activeQueryJob = null
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
        val now = fix.timestampMs

        if (accuracy > MAX_ACCEPTABLE_ACCURACY_METERS) {
            onStatusMessage("Sinal GPS impreciso (±${accuracy.toInt()} m)")
            return
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

        if (isFirstLookup || hasUnconfirmedInitialRoad || hasMovedEnough) {
            lastQueryTimestamp = now
            lastQueryLat = lat
            lastQueryLon = lon
            queryReverseGeocode(lat, lon, fix)
        }
    }

    private fun queryReverseGeocode(lat: Double, lon: Double, fix: GeoFix) {
        activeQueryJob?.cancel()

        activeQueryJob = coroutineScope.launch {
            try {
                val result = reverseGeocodeProvider(lat, lon)
                result.onSuccess { rawPlace ->
                    lastQuerySucceeded = true
                    val normalizedRoad = normalizeRoadName(rawPlace.road)
                    val cleanPlace = rawPlace.copy(road = normalizedRoad)

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

    internal fun processRoadDecision(place: Place, fix: GeoFix) {
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
