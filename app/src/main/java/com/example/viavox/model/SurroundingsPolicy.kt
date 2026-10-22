package com.example.viavox.model

import kotlin.math.abs

/**
 * Voice presentation profiles. They do not change GPS accuracy or the source map.
 * Detailed automatic POI narration is opt-in and confined to ACCESSIBILITY.
 */
enum class AssistanceMode(val storedValue: String, val title: String) {
    EXPLORER("explorador", "Explorador"),
    TERRITORIAL("territorial", "Territorial"),
    ACCESSIBILITY("acessivel", "Acessibilidade");

    companion object {
        fun from(value: String?): AssistanceMode =
            entries.firstOrNull { it.storedValue == value } ?: EXPLORER
    }
}

enum class ScanDirection { AROUND, FRONT }

data class NearbyLandmark(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val kind: String,
    // Polygon center is NOT an entrance or precise position.
    val approximatePoint: Boolean = false
) {
    fun distanceTo(lat: Double, lon: Double): Double =
        PointOfInterest(id, name, PoiCategory.PUBLIC, latitude, longitude).distanceTo(lat, lon)
}

data class LandmarkCandidate(
    val landmark: NearbyLandmark,
    val distanceMeters: Double,
    // Relative to forward orientation. Null when compass/bearing is unavailable.
    val angle: Double?
)

/** Pure geometry/selection logic, independently testable without Android. */
object SurroundingsPolicy {
    fun select(
        points: List<NearbyLandmark>,
        lat: Double,
        lon: Double,
        radiusMeters: Int,
        direction: ScanDirection,
        headingDegrees: Float?,
        gpsAccuracyMeters: Float,
        limit: Int = 5
    ): List<LandmarkCandidate> {
        if (!lat.isFinite() || !lon.isFinite() || gpsAccuracyMeters > 80f ||
            gpsAccuracyMeters <= 0f || !gpsAccuracyMeters.isFinite()) return emptyList()
        if (direction == ScanDirection.FRONT && headingDegrees == null) return emptyList()
        val radius = radiusMeters.coerceIn(50, 500).toDouble()
        val heading = headingDegrees?.takeIf { it.isFinite() }?.toDouble()
        return points.asSequence()
            .filter { it.name.isNotBlank() && it.latitude.isFinite() && it.longitude.isFinite() }
            .map { landmark ->
                val distance = landmark.distanceTo(lat, lon)
                val angle = heading?.let {
                    com.example.viavox.data.PoiSelectionPolicy.angularDifference(
                        com.example.viavox.data.PoiSelectionPolicy.absoluteBearing(
                            lat, lon, landmark.latitude, landmark.longitude
                        ), it
                    )
                }
                LandmarkCandidate(landmark, distance, angle)
            }
            .filter { it.distanceMeters > gpsAccuracyMeters + 8 &&
                it.distanceMeters + gpsAccuracyMeters <= radius }
            .filter { direction == ScanDirection.AROUND || (it.angle != null && abs(it.angle) <= 65.0) }
            .distinctBy { it.landmark.name.lowercase().trim() }
            .sortedWith(compareBy<LandmarkCandidate> { it.distanceMeters }.thenBy { it.landmark.name })
            .take(limit.coerceIn(1, 8))
            .toList()
    }

    fun directionText(candidate: LandmarkCandidate, preciseHeading: Boolean): String {
        if (!preciseHeading || candidate.landmark.approximatePoint ||
            candidate.distanceMeters < 35.0) return "próximo"
        val angle = candidate.angle ?: return "próximo"
        return when {
            angle in 35.0..140.0 -> "à direita"
            angle in -140.0..-35.0 -> "à esquerda"
            abs(angle) <= 35.0 -> "à frente"
            else -> "nas proximidades"
        }
    }

    fun metersText(meters: Double): String =
        if (meters < 1000) "a aproximadamente ${(meters / 10).toInt() * 10} metros"
        else "a aproximadamente ${"%.1f".format(java.util.Locale.forLanguageTag("pt-BR"), meters / 1000)} quilômetros"

    fun scanSpeech(
        items: List<LandmarkCandidate>,
        direction: ScanDirection,
        preciseHeading: Boolean
    ): String {
        if (items.isEmpty()) return if (direction == ScanDirection.AROUND)
            "Não encontrei referências mapeadas no alcance selecionado."
        else "Não encontrei referências mapeadas à frente dentro do alcance selecionado."
        val opener = if (direction == ScanDirection.AROUND) "Ao seu redor:" else "À sua frente:"
        return opener + " " + items.joinToString(". ") { item ->
            "${item.landmark.name}, ${directionText(item, preciseHeading)}, ${metersText(item.distanceMeters)}"
        } + "."
    }
}
