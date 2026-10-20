package com.example.viavox.data

import com.example.viavox.model.PointOfInterest
import com.example.viavox.model.PoiCategory
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Deliberately conservative voice-only selection. The map can show every
 * mapped POI; narration only includes verified, relevant street-side sites.
 */
object PoiSelectionPolicy {
    const val MAX_POI_DISTANCE_METERS = 30.0
    const val MAX_GPS_ACCURACY_METERS = 18.0f
    private const val HEADING_MIN_SPEED_MPS = 2.5f
    private val STREET_PREFIX = Regex("^(?:rua|r|avenida|av|travessa|tv|alameda|al|praca|pca|rodovia|rod)\\s+")

    fun streetMatches(currentRoad: String, sourceAddress: String): Boolean {
        if (currentRoad.isBlank() || sourceAddress.isBlank()) return false
        val road = normalize(currentRoad).replace(STREET_PREFIX, "").trim()
        // Street names shorter than 5 characters produce too many false matches.
        if (road.length < 5) return false
        // Compare a canonical street field (before building number or locality)
        // instead of substring search: "São José" != "São José Operário".
        val sourceStreet = sourceAddress
            .substringBefore(',').substringBefore(';')
            .trim()
            .let(::normalize)
            .replace(Regex("^(?:endereco\\s+)?"), "")
            .replace(STREET_PREFIX, "")
            .replace(Regex("\\s+(?:numero|n)\\s+\\d+.*$"), "")
            .replace(Regex("\\s+\\d+[a-z]?\\s*$"), "")
            .replace(Regex("\\s+s\\s+n$"), "")
            .trim()
        return road == sourceStreet
    }

    private fun normalize(text: String): String = Normalizer
        .normalize(text.lowercase(Locale.forLanguageTag("pt-BR")), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    fun choose(
        pois: List<PointOfInterest>,
        latitude: Double,
        longitude: Double,
        gpsAccuracyMeters: Float,
        speedMps: Float,
        bearingDegrees: Float?,
        currentRoad: String,
        excludedIds: Set<String> = emptySet()
    ): PointOfInterest? {
        if (!gpsAccuracyMeters.isFinite() ||
            gpsAccuracyMeters > MAX_GPS_ACCURACY_METERS ||
            gpsAccuracyMeters <= 0f ||
            currentRoad.isBlank()
        ) return null

        val trustworthyHeading = bearingDegrees?.takeIf {
            speedMps >= HEADING_MIN_SPEED_MPS && it.isFinite()
        }
        return pois.asSequence()
            .filter { it.id !in excludedIds }
            .filter { it.category != PoiCategory.FUEL }
            .filter { streetMatches(currentRoad, it.streetAddress) }
            .map { it to it.distanceTo(latitude, longitude) }
            .filter { (_, distance) -> distance <= MAX_POI_DISTANCE_METERS }
            .filter { (poi, _) ->
                if (trustworthyHeading == null) true
                else {
                    val bearingToPoi = absoluteBearing(latitude, longitude, poi.latitude, poi.longitude)
                    // No voice alert for POIs already behind the vehicle.
                    val delta = angularDifference(bearingToPoi, trustworthyHeading.toDouble())
                    abs(delta) <= 100.0
                }
            }
            .minByOrNull { (_, distance) -> distance }
            ?.first
    }

    fun angularDifference(target: Double, heading: Double): Double =
        ((target - heading + 540.0) % 360.0) - 180.0

    fun absoluteBearing(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val a = Math.toRadians(aLat)
        val b = Math.toRadians(bLat)
        val longitudeDifference = Math.toRadians(bLon - aLon)
        val y = sin(longitudeDifference) * cos(b)
        val x = cos(a) * sin(b) - sin(a) * cos(b) * cos(longitudeDifference)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}
