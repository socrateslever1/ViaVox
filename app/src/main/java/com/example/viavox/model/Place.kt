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

    fun toSpeechMessage(isTransition: Boolean = false, includeNeighbourhood: Boolean = true): String {
        // If user is inside a notable landmark/building/complex (hospital, mall, college, public building):
        if (hasVenue()) {
            val bairroPart = if (includeNeighbourhood && hasValidNeighbourhood()) ", bairro $neighbourhood" else ""
            return if (isTransition) "Agora você está no $venueName$bairroPart."
            else "Você está no $venueName$bairroPart."
        }

        val prefix = if (isTransition) "Agora você está na $road" else "Você está na $road"
        val bairroPart = if (includeNeighbourhood && hasValidNeighbourhood()) {
            ", bairro $neighbourhood"
        } else {
            ""
        }
        val refPart = if (!isTransition && reference.isNotBlank() && reference != road) {
            ", próximo a $reference"
        } else {
            ""
        }
        return "$prefix$bairroPart$refPart."
    }

    fun toNeighbourhoodTransitionMessage(includePolice: Boolean = true): String {
        val zonePart = if (zone.isNotBlank()) ", $zone" else ""
        val policePart = if (includePolice && policeDistrict.isNotBlank()) ". Circunscrição do $policeDistrict" else ""
        return "Entrando no bairro $neighbourhood$zonePart$policePart."
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
