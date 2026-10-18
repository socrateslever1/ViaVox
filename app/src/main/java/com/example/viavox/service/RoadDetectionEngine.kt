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
    }
) {

    private var activeQueryJob: Job? = null
    private var lastQueryTimestamp = 0L
    private var lastQueryLat = 0.0
    private var lastQueryLon = 0.0
    private var lastQuerySucceeded = false

    private var currentConfirmedRoad: String? = null
    private var pendingCandidateRoad: String? = null
    private var pendingCandidatePlace: Place? = null
    private var pendingCandidateCount = 0
    private var pendingCandidateStartLat = 0.0
    private var pendingCandidateStartLon = 0.0

    fun getCurrentConfirmedRoad(): String? = currentConfirmedRoad

    fun reset() {
        activeQueryJob?.cancel()
        activeQueryJob = null
        currentConfirmedRoad = null
        pendingCandidateRoad = null
        pendingCandidatePlace = null
        pendingCandidateCount = 0
        lastQueryTimestamp = 0L
        lastQueryLat = 0.0
        lastQueryLon = 0.0
        lastQuerySucceeded = false
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
            !lastQuerySucceeded -> 3_000L
            pendingCandidateRoad != null -> 4_000L
            speedMps > 8.3f -> 6_000L
            else -> 8_000L
        }
        val minDistance = when {
            pendingCandidateRoad != null -> 10.0
            speedMps > 8.3f -> 20.0
            else -> 14.0
        }

        val isFirstLookup = lastQueryTimestamp == 0L
        val hasUnconfirmedInitialRoad = currentConfirmedRoad == null && (now - lastQueryTimestamp >= minInterval)
        val hasMovedEnough = (now - lastQueryTimestamp >= minInterval) &&
                (metersMoved >= minDistance || now - lastQueryTimestamp >= 45_000L)

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
        if (!place.copy(road = road).hasValidRoad()) {
            // Do not invent or announce unidentified roads
            return
        }

        val normalizedPlace = place.copy(road = road)
        val normalized = normalizeForComparison(road)
        val currentNormalized = currentConfirmedRoad?.let { normalizeForComparison(it) }

        // 1. First identified road (works both stationary and moving): announce immediately!
        if (currentNormalized == null) {
            currentConfirmedRoad = road
            pendingCandidateRoad = null
            pendingCandidatePlace = null
            pendingCandidateCount = 0

            val message = normalizedPlace.toSpeechMessage(isTransition = false)
            onRoadAnnounceNeeded(message, true)
            return
        }

        // 2. Still on the same confirmed road: reset any transient candidate and do not repeat
        if (normalized == currentNormalized) {
            pendingCandidateRoad = null
            pendingCandidatePlace = null
            pendingCandidateCount = 0
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

            // Confirm street change if:
            // - 2+ consecutive readings with >= 10m displacement along the new street, OR
            // - Moving cleanly (speed >= 5 m/s) with >= 22m displacement on the new street, OR
            // - 3+ consecutive readings on the new street
            val isConfirmed = (pendingCandidateCount >= 2 && distOnCandidate >= 10.0) ||
                    (fix.speedMps >= 5.0f && distOnCandidate >= 22.0) ||
                    (pendingCandidateCount >= 3)

            if (isConfirmed) {
                currentConfirmedRoad = road
                pendingCandidateRoad = null
                pendingCandidatePlace = null
                pendingCandidateCount = 0

                val message = normalizedPlace.toSpeechMessage(isTransition = true)
                onRoadAnnounceNeeded(message, false)
            }
        } else {
            // First sighting of a candidate road (protects against single-point GPS bounce at intersections)
            pendingCandidateRoad = road
            pendingCandidatePlace = normalizedPlace
            pendingCandidateCount = 1
            pendingCandidateStartLat = fix.latitude
            pendingCandidateStartLon = fix.longitude
        }
    }

    companion object {
        const val MAX_ACCEPTABLE_ACCURACY_METERS = 70f

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
