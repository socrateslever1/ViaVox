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
    val reference: String = ""
) {
    fun hasValidRoad(): Boolean {
        return road.isNotBlank() && road != UNKNOWN_ROAD
    }

    fun hasValidNeighbourhood(): Boolean {
        return neighbourhood.isNotBlank() && neighbourhood != UNKNOWN_NEIGHBOURHOOD
    }

    fun toSpeechMessage(isTransition: Boolean = false): String {
        val prefix = if (isTransition) "Agora você está na $road" else "Você está na $road"
        val bairroPart = if (hasValidNeighbourhood()) {
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

    fun toDisplaySubtitle(): String {
        val cleanNeighbourhood = if (hasValidNeighbourhood()) neighbourhood else ""
        return buildString {
            if (cleanNeighbourhood.isNotBlank()) {
                append(cleanNeighbourhood)
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
