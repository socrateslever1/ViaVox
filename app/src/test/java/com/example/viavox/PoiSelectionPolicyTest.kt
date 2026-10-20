package com.example.viavox

import com.example.viavox.data.PoiSelectionPolicy
import com.example.viavox.model.PoiCategory
import com.example.viavox.model.PointOfInterest
import org.junit.Assert.*
import org.junit.Test

class PoiSelectionPolicyTest {
    private val road = "Rua Benjamin Constant"
    private fun poi(id: String, lat: Double, address: String = road,
                    category: PoiCategory = PoiCategory.PUBLIC) =
        PointOfInterest(id, "Delegacia $id", category, lat, 0.0, streetAddress = address)

    @Test fun onlyLandmarksWithinThirtyMetersOnSameStreet() {
        val close = poi("close",0.00018, "R. Benjamin Constant, 12")
        val far = poi("far",0.00031)
        val otherStreet = poi("other",0.00010,"Rua Aristides Rocha")
        assertEquals("close",PoiSelectionPolicy.choose(
            listOf(otherStreet, far, close),0.0,0.0,8f,0f,null,road
        )?.id)
        assertNull(PoiSelectionPolicy.choose(
            listOf(far,otherStreet),0.0,0.0,8f,0f,null,road
        ))
    }

    @Test fun exactThirtyMeterLimitAndNoCrossStreetGuess() {
        val p = poi("far",0.0005)
        assertNull(PoiSelectionPolicy.choose(listOf(p),0.0,0.0,4f,0f,null,road))
        assertNull(PoiSelectionPolicy.choose(
            listOf(poi("unknown",0.0001, "")),0.0,0.0,4f,0f,null,road
        ))
    }

    @Test fun roadAddressMatchingHandlesAbbreviationsAndDiacritics() {
        assertTrue(PoiSelectionPolicy.streetMatches(
            "Avenida Manaus Moderna", "Av. Manaus Moderna, Centro - Manaus"
        ))
        assertTrue(PoiSelectionPolicy.streetMatches(
            "Rua São José", "R. Sao Jose, 100"
        ))
        assertFalse(PoiSelectionPolicy.streetMatches(
            "Rua São José", "Rua São José Operário, 10"
        ))
        assertFalse(PoiSelectionPolicy.streetMatches(
            "Rua Benjamin Constant", "Avenida Benjamin Azevedo, 30"
        ))
        assertFalse(PoiSelectionPolicy.streetMatches(
            "Rua A", "Rua A, 12"
        ))
    }

    @Test fun rejectsPoorGpsAndIrrelevantCommerce() {
        val close = poi("hospital",0.0001)
        assertNull(PoiSelectionPolicy.choose(listOf(close),0.0,0.0,24f,0f,null,road))
        assertNull(PoiSelectionPolicy.choose(
            listOf(poi("gas",0.0001,category=PoiCategory.FUEL)),
            0.0,0.0,4f,0f,null,road
        ))
    }

    @Test fun strictVoiceFilterExcludesBusinessesAndSimilarStreetNames() {
        val data = listOf(
            PointOfInterest("a","Farmácia Boa Saúde",PoiCategory.HEALTH,0.0001,0.0,road),
            PointOfInterest("b","Supermercado Parintins",PoiCategory.PUBLIC,0.0001,0.0,road),
            PointOfInterest("c","Delegacia de Polícia",PoiCategory.PUBLIC,0.0002,0.0,road)
        )
        assertEquals("c", PoiSelectionPolicy.choose(
            data,0.0,0.0,8f,0f,null,road
        )?.id)
        assertFalse(PoiSelectionPolicy.streetMatches("Rua Benjamin Constant",
            "Avenida Benjamin Constant, 10"))
        assertNull(PoiSelectionPolicy.choose(
            listOf(data.last()),0.0,0.0,15f,0f,null,road
        ))
        assertNull(PoiSelectionPolicy.choose(
            listOf(data.last()),0.0,0.0,8f,0f,null,"Rua Aristides Rocha"
        ))
    }

    @Test fun movingForwardDoesNotAnnouncePassedLandmarks() {
        val behind=poi("behind",-0.0001)
        val ahead=poi("ahead",0.0002)
        assertEquals("ahead",PoiSelectionPolicy.choose(
            listOf(behind,ahead),0.0,0.0,5f,8f,0f,road
        )?.id)
        assertNull(PoiSelectionPolicy.choose(
            listOf(behind),0.0,0.0,5f,8f,0f,road
        ))
    }

    @Test fun excludedAndRepeatedLandmarksAreNotReannounced() {
        assertNull(PoiSelectionPolicy.choose(
            listOf(poi("repeat",0.0002)),0.0,0.0,6f,8f,0f,road,
            setOf("repeat")
        ))
    }
}
