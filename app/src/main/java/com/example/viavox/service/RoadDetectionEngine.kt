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

class RoadDetectionEngine(
    private val nominatimService: NominatimService,
    private val coroutineScope: CoroutineScope,
    private val onPlaceUpdated: (Place) -> Unit,
    private val onRoadAnnounceNeeded: (message: String, isFirstRoad: Boolean) -> Unit,
    private val onStatusMessage: (String) -> Unit
) {

    private var activeQueryJob: Job? = null
    private var lastQueryTimestamp = 0L
    private var lastQueryLat = 0.0
    private var lastQueryLon = 0.0

    private var currentConfirmedRoad: String? = null
    private var pendingCandidateRoad: String? = null
    private var pendingCandidatePlace: Place? = null
    private var pendingCandidateCount = 0
    private var pendingCandidateStartLat = 0.0
    private var pendingCandidateStartLon = 0.0

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
    }

    /**
     * Evaluates whether an address lookup is warranted given current location, speed, and time.
     */
    fun onLocationUpdate(location: Location) {
        val lat = location.latitude
        val lon = location.longitude
        val accuracy = if (location.hasAccuracy()) location.accuracy else 30f
        val now = System.currentTimeMillis()

        if (accuracy > 70f) {
            onStatusMessage("Sinal GPS impreciso (±${accuracy.toInt()} m)")
            return
        }

        val metersMoved = if (lastQueryLat != 0.0) {
            val dLat = (lat - lastQueryLat) * 111000.0
            val dLon = (lon - lastQueryLon) * 111000.0 * cos(Math.toRadians(lat))
            hypot(dLat, dLon)
        } else {
            Double.POSITIVE_INFINITY
        }

        // Throttle lookup:
        // - First lookup: immediate
        // - Moving fast (> 30 km/h / 8.3 m/s): lookup every 8s if moved >= 25m
        // - Standard driving: lookup every 10s if moved >= 18m
        // - Stationary / Slow: lookup at least every 60s
        val speedMps = if (location.hasSpeed()) location.speed else 0f
        val minInterval = if (speedMps > 8.3f) 8_000L else 10_000L
        val minDistance = if (speedMps > 8.3f) 25.0 else 18.0

        val shouldQuery = lastQueryTimestamp == 0L ||
                ((now - lastQueryTimestamp >= minInterval) && (metersMoved >= minDistance || now - lastQueryTimestamp >= 60_000L))

        if (shouldQuery) {
            lastQueryTimestamp = now
            lastQueryLat = lat
            lastQueryLon = lon
            queryReverseGeocode(lat, lon, location)
        }
    }

    private fun queryReverseGeocode(lat: Double, lon: Double, location: Location) {
        // Cancel any in-flight outdated query job
        activeQueryJob?.cancel()

        activeQueryJob = coroutineScope.launch {
            try {
                val result = nominatimService.reverseGeocode(lat, lon)
                result.onSuccess { rawPlace ->
                    val normalizedRoad = normalizeRoadName(rawPlace.road)
                    val cleanPlace = rawPlace.copy(road = normalizedRoad)

                    onPlaceUpdated(cleanPlace)
                    onStatusMessage("Localização atualizada")

                    processRoadDecision(cleanPlace, location)
                }.onFailure { e ->
                    if (e !is CancellationException) {
                        onStatusMessage(e.message ?: "Erro ao identificar endereço")
                    }
                }
            } catch (_: CancellationException) {
                // Outdated query cancelled in favor of fresher coordinates
            }
        }
    }

    private fun processRoadDecision(place: Place, location: Location) {
        val road = place.road
        if (road == "Via não identificada" || road.isBlank()) return

        val normalized = normalizeForComparison(road)
        val currentNormalized = currentConfirmedRoad?.let { normalizeForComparison(it) }

        // FIRST ROAD: announce immediately without waiting for confirmation!
        if (currentNormalized == null) {
            currentConfirmedRoad = road
            pendingCandidateRoad = null
            pendingCandidateCount = 0

            val message = "Você está na $road, bairro ${place.neighbourhood}."
            onRoadAnnounceNeeded(message, true)
            return
        }

        // Same road as confirmed
        if (normalized == currentNormalized) {
            pendingCandidateRoad = null
            pendingCandidatePlace = null
            pendingCandidateCount = 0
            return
        }

        // Potential road change
        val pendingNorm = pendingCandidateRoad?.let { normalizeForComparison(it) }

        if (pendingNorm == normalized) {
            pendingCandidateCount++
            val distOnCandidate = hypot(
                (location.latitude - pendingCandidateStartLat) * 111000.0,
                (location.longitude - pendingCandidateStartLon) * 111000.0 * cos(Math.toRadians(location.latitude))
            )

            val speedMps = if (location.hasSpeed()) location.speed else 0f

            // Confirmation rules:
            // 1. If speed > 18 km/h (5 m/s) and moved >= 25m along new candidate, confirm!
            // 2. Or standard count >= 2 and moved >= 12m
            val isConfirmed = (speedMps > 5.0f && distOnCandidate >= 25.0) ||
                    (pendingCandidateCount >= 2 && distOnCandidate >= 12.0)

            if (isConfirmed) {
                currentConfirmedRoad = road
                pendingCandidateRoad = null
                pendingCandidatePlace = null
                pendingCandidateCount = 0

                val message = "Agora você está na $road, bairro ${place.neighbourhood}."
                onRoadAnnounceNeeded(message, false)
            }
        } else {
            // New candidate street started
            pendingCandidateRoad = road
            pendingCandidatePlace = place
            pendingCandidateCount = 1
            pendingCandidateStartLat = location.latitude
            pendingCandidateStartLon = location.longitude
        }
    }

    companion object {
        fun normalizeRoadName(name: String): String {
            var s = name.trim()
            val replacements = listOf(
                Pair(Regex("(?i)^R\\.\\s*"), "Rua "),
                Pair(Regex("(?i)^Av\\.\\s*"), "Avenida "),
                Pair(Regex("(?i)^Trav\\.\\s*"), "Travessa "),
                Pair(Regex("(?i)^Al\\.\\s*"), "Alameda "),
                Pair(Regex("(?i)^Rod\\.\\s*"), "Rodovia ")
            )
            for ((regex, replacement) in replacements) {
                if (regex.containsMatchIn(s)) {
                    s = s.replace(regex, replacement)
                    break
                }
            }
            return s
        }

        fun normalizeForComparison(name: String): String {
            val normalized = Normalizer.normalize(name.lowercase().trim(), Normalizer.Form.NFD)
            return normalized.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
                .replace(Regex("[^a-z0-9]"), "")
        }
    }
}
