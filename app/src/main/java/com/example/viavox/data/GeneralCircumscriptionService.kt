package com.example.viavox.data

import com.example.viavox.model.Place
import org.json.JSONObject

/**
 * Universal Circumscription and Boundary Resolver for any Brazilian and international city.
 * Analyzes OpenStreetMap Nominatim responses, extracts genuine neighborhood/district
 * boundaries, prevents city-name leakage, and handles boundary hysteresis so that
 * traveling along a boundary road doesn't produce repeated flip-flop announcements.
 */
object GeneralCircumscriptionService {

    data class Circumscription(
        val road: String,
        val neighbourhood: String,
        val city: String,
        val zoneOrDistrict: String = "",
        val reference: String = "",
        val venueName: String = ""
    )

    private val GENERIC_ADMIN_TERMS = listOf(
        "região geográfica", "região metropolitana", "microrregião",
        "mesorregião", "distrito federal", "zona eleitoral"
    )

    fun clean(value: String?): String = value?.trim().orEmpty()

    /**
     * Resolves genuine circumscription from Nominatim JSON body for any city worldwide.
     */
    fun resolve(jsonBody: String, lat: Double, lon: Double): Circumscription {
        if (jsonBody.isBlank()) return fallback(lat, lon)
        return try {
            val root = JSONObject(jsonBody)
            val address = root.optJSONObject("address") ?: JSONObject()
            val displayName = clean(root.optString("display_name"))

            // 1. Identify City / Municipality
            val city = clean(address.optString("city")).ifEmpty {
                clean(address.optString("town")).ifEmpty {
                    clean(address.optString("municipality")).ifEmpty {
                        clean(address.optString("village")).ifEmpty {
                            clean(address.optString("county"))
                        }
                    }
                }
            }

            // 2. Identify Road
            val road = clean(address.optString("road")).ifEmpty {
                clean(address.optString("pedestrian")).ifEmpty {
                    clean(address.optString("residential")).ifEmpty {
                        clean(address.optString("path")).ifEmpty {
                            clean(address.optString("suburb_road")).ifEmpty {
                                Place.UNKNOWN_ROAD
                            }
                        }
                    }
                }
            }

            // 3. Identify Candidate Neighbourhoods in priority order
            val rawCandidates = listOf(
                clean(address.optString("suburb")),
                clean(address.optString("neighbourhood")),
                clean(address.optString("quarter")),
                clean(address.optString("borough")),
                clean(address.optString("city_district")),
                clean(address.optString("district")),
                clean(address.optString("residential"))
            )

            var neighbourhood = ""
            for (candidate in rawCandidates) {
                if (isValidNeighbourhood(candidate, city)) {
                    neighbourhood = candidate
                    break
                }
            }

            // 4. If address tags omitted the neighbourhood, extract from display_name hierarchy
            if (neighbourhood.isBlank() && displayName.isNotBlank()) {
                neighbourhood = extractNeighbourhoodFromDisplayName(displayName, road, city)
            }

            // 5. Special local resolvers (e.g. Manaus fallback for high-precision local bounding)
            var zone = clean(address.optString("borough"))
            if (ManausDistrictResolver.isManausCoordinates(lat, lon)) {
                val manausDistrict = ManausDistrictResolver.resolveDistrict(lat, lon, displayName)
                if (manausDistrict != null) {
                    // Prefer the bundled polygon over reverse-geocoder labels inside Manaus.
                    // Reverse geocoders frequently return the opposite side of
                    // the Coroado/Aleixo boundary near the roundabout.
                    neighbourhood = manausDistrict.name
                    zone = manausDistrict.zone
                }
            }

            // 6. Extract Reference Point and Notable Venue/Building
            val rawName = clean(root.optString("name"))
            val building = clean(address.optString("building")).ifEmpty {
                clean(address.optString("amenity")).ifEmpty {
                    clean(address.optString("shop")).ifEmpty {
                        clean(address.optString("office")).ifEmpty {
                            clean(address.optString("leisure"))
                        }
                    }
                }
            }
            val venueCandidate = if (rawName.isNotBlank() && rawName != road) rawName
                else if (building.isNotBlank() && building != road) building else ""

            Circumscription(
                road = road,
                neighbourhood = neighbourhood.ifBlank { Place.UNKNOWN_NEIGHBOURHOOD },
                city = city,
                zoneOrDistrict = zone,
                reference = if (rawName.isNotBlank() && rawName != road) rawName else "",
                venueName = venueCandidate
            )
        } catch (_: Exception) {
            fallback(lat, lon)
        }
    }

    private fun fallback(lat: Double, lon: Double): Circumscription {
        val manaus = ManausDistrictResolver.resolveDistrict(lat, lon)
        return Circumscription(
            road = Place.UNKNOWN_ROAD,
            neighbourhood = manaus?.name ?: Place.UNKNOWN_NEIGHBOURHOOD,
            city = if (manaus != null) "Manaus" else "",
            zoneOrDistrict = manaus?.zone.orEmpty()
        )
    }

    /**
     * Rejects invalid neighborhood strings such as repeating the city name,
     * administrative region buzzwords, or postal code relics.
     */
    fun isValidNeighbourhood(candidate: String, city: String): Boolean {
        if (candidate.isBlank() || candidate.length < 2) return false
        val lower = candidate.lowercase()
        val cityLower = city.lowercase()

        // Must not be the city name
        if (cityLower.isNotBlank() && lower == cityLower) return false
        if (lower == "manaus" || lower == "são paulo" || lower == "rio de janeiro" || lower == "brasil") return false

        // Must not be a generic administrative label
        if (GENERIC_ADMIN_TERMS.any { lower.contains(it) }) return false
        if (lower.startsWith("cep") || lower.matches(Regex("""^\d{5}-?\d{3}$"""))) return false

        return true
    }

    /**
     * Parses the hierarchical components of Nominatim display_name.
     * Brazilian format is usually: "<Street>, <Neighbourhood>, <City>, <Region>, <State>, <Country>".
     */
    fun extractNeighbourhoodFromDisplayName(displayName: String, road: String, city: String): String {
        val parts = displayName.split(",").map { it.trim() }.filter { it.isNotBlank() }
        if (parts.size < 3) return ""

        val roadLower = road.lowercase()
        val cityLower = city.lowercase()

        for (part in parts) {
            val partLower = part.lowercase()
            if (partLower == roadLower) continue
            if (cityLower.isNotBlank() && partLower.contains(cityLower)) continue
            if (isValidNeighbourhood(part, city)) {
                return part
            }
        }
        return ""
    }
}
