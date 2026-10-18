package com.example.viavox

import com.example.viavox.data.PoiSelectionPolicy
import com.example.viavox.model.PoiCategory
import com.example.viavox.model.PointOfInterest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PoiSelectionPolicyTest {
    private fun poi(id: String, lat: Double, lon: Double = 0.0) =
        PointOfInterest(id, "Posto $id", PoiCategory.FUEL, lat, lon)

    @Test
    fun choosesLandmarksAheadAndExcludesThoseAlreadyPassed() {
        val behind = poi("behind", -0.00015)
        val ahead = poi("ahead", 0.00055)
        val selected = PoiSelectionPolicy.choose(
            listOf(behind, ahead), 0.0, 0.0, 6f, 9f, 0f
        )
        assertEquals("ahead", selected?.id)
    }

    @Test
    fun whenStoppedConsidersNearbyLandmarksWithoutClaimingVehicleDirection() {
        val selected = PoiSelectionPolicy.choose(
            listOf(poi("close", -0.0003)), 0.0, 0.0, 5f, 0f, null
        )
        assertEquals("close", selected?.id)
    }

    @Test
    fun rejectsLowConfidenceGpsAndPlacesOutsideReach() {
        val close = poi("near", 0.0005)
        assertNull(PoiSelectionPolicy.choose(listOf(close), 0.0, 0.0, 58f, 10f, 0f))
        assertNull(PoiSelectionPolicy.choose(listOf(poi("far", 0.002)), 0.0, 0.0, 4f, 10f, 0f))
    }

    @Test
    fun excludesAlreadyAnnouncedPoi() {
        assertNull(PoiSelectionPolicy.choose(
            listOf(poi("repeat",0.0003)), 0.0,0.0,7f,8f,0f, setOf("repeat")
        ))
    }
}
