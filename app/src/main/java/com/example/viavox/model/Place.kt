package com.example.viavox.model

import kotlinx.serialization.Serializable

/**
 * Represents a reverse-geocoded place structure from OpenStreetMap Nominatim.
 */
@Serializable
data class Place(
    val road: String,
    val neighbourhood: String,
    val city: String = "",
    val reference: String = ""
) {
    fun toSpeechMessage(): String {
        val refPart = if (reference.isNotBlank() && reference != road) {
            ", próximo a $reference"
        } else {
            ""
        }
        return "Você está na $road, bairro $neighbourhood$refPart."
    }

    fun toDisplaySubtitle(): String {
        return buildString {
            append(neighbourhood)
            if (city.isNotBlank()) {
                append(" · ")
                append(city)
            }
        }
    }
}
