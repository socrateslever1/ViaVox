package com.example.viavox.data

import com.example.viavox.model.MapPlace
import java.text.Normalizer
import java.util.Locale

/**
 * No guessed police-unit coordinates. Aliases only expand text to search real
 * mapped places; known saved locations are returned using their stored GPS fix.
 */
object DestinationSearchPolicy {
    fun normalize(text: String): String = Normalizer.normalize(
        text.lowercase(Locale.forLanguageTag("pt-BR")), Normalizer.Form.NFD
    ).replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim().replace(Regex("\\s+"), " ")

    /** Match CICOM abbreviations and typing prefixes (24 ci, CICOM 24). */
    fun cicomNumber(query: String): String? {
        val normalized = normalize(query).replace(Regex("""\b(\d{1,2})\s+a\s+cicom\b"""), "$1 cicom")
        val first = Regex("""^(\d{1,2})\s+ci(?:c(?:o(?:m)?)?)?(?:\s+.*)?$""")
            .matchEntire(normalized)
        if (first != null) return first.groupValues[1]
        val second = Regex("""^cicom\s+(\d{1,2})(?:\s+.*)?$""").matchEntire(normalized)
        return second?.groupValues?.get(1)
    }

    fun isCicomQuery(query: String): Boolean {
        val normalized = normalize(query)
        return cicomNumber(query) != null ||
            Regex("""(?:^|\s)cicom(?:\s|$)""").containsMatchIn(normalized)
    }

    fun queryVariants(query: String): List<String> {
        val text = query.trim()
        val normalized = normalize(text)
        val options = mutableListOf(text)
        val cicom = Regex("""^(?:cicom\s*(\d{1,2})|(\d{1,2})\s*cicom)(?:\s+(.*))?$""")
            .matchEntire(normalized)
        if (cicom != null) {
            val number = cicom.groupValues[1].ifBlank { cicom.groupValues[2] }
            val qualifier = cicom.groupValues[3].takeIf { it.isNotBlank() }.orEmpty()
            // 24th unit: published address in SSP-AM public list (2018),
            // corroborated by government reporting (2024). Geocode the address;
            // never substitute an unverified hard-coded GPS coordinate.
            if (number == "24") {
                options += "Avenida Lourenço da Silva Braga com Rua Lima Bacuri Centro Manaus"
            }
            // Alternative names help Photon/Nominatim find indexed OSM features.
            options += "${number} Companhia Interativa Comunitária Manaus ${qualifier}".trim()
            options += "CICOM ${number} Polícia Militar Manaus ${qualifier}".trim()
            options += "Companhia Interativa Comunitária ${number} ${qualifier}".trim()
        }
        return options.distinctBy(::normalize).take(4)
    }

    fun localMatches(query: String, saved: List<MapPlace>): List<MapPlace> {
        val cleaned = normalize(query)
        if (cleaned.isBlank()) return emptyList()
        val words = cleaned.split(" ").filter(String::isNotBlank)
        return saved.filter { place ->
            val searchable = normalize("${place.name} ${place.subtitle}")
            searchable.contains(cleaned) || words.all(searchable::contains)
        }.distinctBy { "${it.latitude},${it.longitude}" }.take(10)
    }

    fun merge(local: List<MapPlace>, remote: List<MapPlace>): List<MapPlace> {
        val results = mutableListOf<MapPlace>()
        for (place in local + remote) {
            val duplicate = results.any {
                normalize(it.name) == normalize(place.name) &&
                    com.example.viavox.model.NavigationMath.meters(
                        it.latitude, it.longitude, place.latitude, place.longitude
                    ) <= 90.0
            }
            if (!duplicate && place.latitude in -90.0..90.0 &&
                place.longitude in -180.0..180.0) results += place
        }
        return results.take(16)
    }
}
