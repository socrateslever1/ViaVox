package com.example.viavox.model

import kotlinx.serialization.Serializable

/**
 * Represents a reverse-geocoded place structure from OpenStreetMap Nominatim.
 */
@Serializable
data class Place(
    val road: String,
    val neighbourhood: String = "",
    val city: String = "",
    val reference: String = "",
    val zone: String = "",
    val venueName: String = "",
    val policeDistrict: String = ""
) {
    fun hasValidRoad(): Boolean {
        return road.isNotBlank() && road != UNKNOWN_ROAD
    }

    fun hasValidNeighbourhood(): Boolean {
        return neighbourhood.isNotBlank() && neighbourhood != UNKNOWN_NEIGHBOURHOOD
    }

    fun hasVenue(): Boolean {
        return venueName.isNotBlank() && venueName != road && venueName != neighbourhood
    }

    fun extractCicom(): String {
        val regex = Regex("""\b\d{1,2}\s*[ªa]\s*CICOM\b""", RegexOption.IGNORE_CASE)
        return regex.find(policeDistrict)?.value ?: ""
    }

    fun toSpeechMessage(
        isTransition: Boolean = false,
        includeNeighbourhood: Boolean = true,
        includePolice: Boolean = false
    ): String {
        // If user is inside a notable landmark/building/complex (hospital, mall, college, public building):
        if (hasVenue()) {
            val bairroPart = if (includeNeighbourhood && hasValidNeighbourhood()) ", bairro $neighbourhood" else ""
            return if (isTransition) "Agora você está no $venueName$bairroPart."
            else "Você está no $venueName$bairroPart."
        }

        if (isTransition) {
            val prefix = "Agora você está na $road"
            val bairroPart = if (includeNeighbourhood && hasValidNeighbourhood()) ", bairro $neighbourhood" else ""
            return "$prefix$bairroPart."
        }

        // Complete scenario when zone or police is present:
        if (zone.isNotBlank() || (includePolice && policeDistrict.isNotBlank())) {
            val contextParts = mutableListOf<String>()
            if (zone.isNotBlank()) {
                val cleanZone = if (zone.startsWith("Zona", ignoreCase = true)) "na $zone" else "na Zona $zone"
                contextParts.add(cleanZone)
            }
            if (includeNeighbourhood && hasValidNeighbourhood()) {
                contextParts.add("no bairro $neighbourhood")
            }
            if (includePolice && policeDistrict.isNotBlank()) {
                val cicom = extractCicom()
                val label = if (cicom.isNotBlank()) cicom else policeDistrict
                contextParts.add("na área da $label")
            }
            val rd = if (hasValidRoad()) ", $road" else ""
            return "Você está agora ${contextParts.joinToString(", ")}$rd."
        }

        val prefix = "Você está na $road"
        val bairroPart = if (includeNeighbourhood && hasValidNeighbourhood()) {
            ", bairro $neighbourhood"
        } else {
            ""
        }
        val refPart = if (reference.isNotBlank() && reference != road) {
            ", próximo a $reference"
        } else {
            ""
        }
        return "$prefix$bairroPart$refPart."
    }

    fun toNeighbourhoodTransitionMessage(includePolice: Boolean = true): String {
        val zonePart = if (zone.isNotBlank()) {
            if (zone.startsWith("Zona", ignoreCase = true)) "na $zone, " else "na Zona $zone, "
        } else ""
        val cicom = if (includePolice && policeDistrict.isNotBlank()) {
            val extracted = extractCicom()
            val label = if (extracted.isNotBlank()) extracted else policeDistrict
            ", na área da $label"
        } else ""
        return if (zonePart.isNotBlank() || cicom.isNotBlank()) {
            "Entrando ${zonePart}no bairro $neighbourhood$cicom."
        } else {
            val policePart = if (includePolice && policeDistrict.isNotBlank()) ". Circunscrição do $policeDistrict" else ""
            "Entrando no bairro $neighbourhood$policePart."
        }
    }

    fun toDisplaySubtitle(): String {
        val cleanNeighbourhood = if (hasValidNeighbourhood()) neighbourhood else ""
        return buildString {
            if (hasVenue()) {
                append(venueName)
                append(" · ")
            }
            if (cleanNeighbourhood.isNotBlank()) {
                append(cleanNeighbourhood)
            }
            if (zone.isNotBlank()) {
                append(" (")
                append(zone)
                append(")")
            }
            if (policeDistrict.isNotBlank()) {
                append(" · 🛡️ ")
                append(policeDistrict)
            }
            if (city.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(city)
            }
        }
    }

    companion object {
        const val UNKNOWN_ROAD = "Via não identificada"
        const val UNKNOWN_NEIGHBOURHOOD = "Bairro não identificado"
    }
}
