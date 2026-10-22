package com.example.viavox

import com.example.viavox.model.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceTest {

    @Test
    fun testSpeechMessageFormatting_withReference() {
        val place = Place(
            road = "Avenida Djalma Batista",
            neighbourhood = "Chapada",
            city = "Manaus",
            reference = "Amazonas Shopping"
        )

        val speech = place.toSpeechMessage()
        assertEquals(
            "Você está na Avenida Djalma Batista, bairro Chapada, próximo a Amazonas Shopping.",
            speech
        )
    }

    @Test
    fun testSpeechMessageFormatting_withoutReference() {
        val place = Place(
            road = "Rua Paraíba",
            neighbourhood = "Adrianópolis",
            city = "Manaus",
            reference = ""
        )

        val speech = place.toSpeechMessage()
        assertEquals(
            "Você está na Rua Paraíba, bairro Adrianópolis.",
            speech
        )
    }

    @Test
    fun testCompleteScenario_withZoneAndPolice() {
        val place = Place(
            road = "Avenida Torquato Tapajós",
            neighbourhood = "Novo Israel",
            zone = "Zona Norte",
            policeDistrict = "18º DIP · 18ª CICOM"
        )

        val speech = place.toSpeechMessage(includePolice = true)
        assertEquals(
            "Você está agora na Zona Norte, no bairro Novo Israel, na área da 18ª CICOM, Avenida Torquato Tapajós.",
            speech
        )
    }

    @Test
    fun testTransitionRespects1xCadence() {
        val place = Place(
            road = "Rua das Flores",
            neighbourhood = "Novo Israel",
            zone = "Zona Norte"
        )

        val speech = place.toSpeechMessage(isTransition = true, includeNeighbourhood = false)
        assertEquals("Agora você está na Rua das Flores.", speech)
    }

    @Test
    fun testDisplaySubtitle() {
        val place = Place(
            road = "Avenida Constantino Nery",
            neighbourhood = "São Geraldo",
            city = "Manaus"
        )

        assertEquals("São Geraldo · Manaus", place.toDisplaySubtitle())
    }
}
