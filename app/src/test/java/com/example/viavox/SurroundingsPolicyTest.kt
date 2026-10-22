package com.example.viavox

import com.example.viavox.model.NearbyLandmark
import com.example.viavox.model.ScanDirection
import com.example.viavox.model.SurroundingsPolicy
import org.junit.Assert.*
import org.junit.Test

class SurroundingsPolicyTest {
    private val centreLat = -3.1100
    private val centreLon = -60.0100

    @Test fun aroundUsesRadiusAndExcludesUncertainDistances() {
        val near = NearbyLandmark("1", "Biblioteca", centreLat + 0.00055, centreLon, "amenity")
        val far = NearbyLandmark("2", "Museu", centreLat + 0.004, centreLon, "tourism")
        val chosen = SurroundingsPolicy.select(
            listOf(near, far), centreLat, centreLon, 100,
            ScanDirection.AROUND, null, 5f
        )
        assertEquals(listOf("1"), chosen.map { it.landmark.id })
    }

    @Test fun forwardFiltersLandmarksBehind() {
        val north = NearbyLandmark("N", "Museu ao norte", centreLat + 0.0005, centreLon, "tourism")
        val south = NearbyLandmark("S", "Parque ao sul", centreLat - 0.0005, centreLon, "leisure")
        val selected = SurroundingsPolicy.select(
            listOf(north, south), centreLat, centreLon, 100,
            ScanDirection.FRONT, 0f, 5f
        )
        assertEquals(listOf("N"), selected.map { it.landmark.id })
        assertTrue(SurroundingsPolicy.select(
            listOf(north), centreLat, centreLon, 100,
            ScanDirection.FRONT, null, 5f
        ).isEmpty())
    }

    @Test fun refusesUncertainGps() {
        val nearby = NearbyLandmark("1", "Escola", centreLat + 0.0005, centreLon, "amenity")
        assertTrue(SurroundingsPolicy.select(
            listOf(nearby), centreLat, centreLon, 100,
            ScanDirection.AROUND, null, 90f
        ).isEmpty())
    }

    @Test fun approximatePolygonNeverClaimsRightOrLeft() {
        val polygon = NearbyLandmark("p", "Palacete", centreLat, centreLon + 0.0005,
            "historic", approximatePoint = true)
        val picked = SurroundingsPolicy.select(
            listOf(polygon), centreLat, centreLon, 150,
            ScanDirection.AROUND, 0f, 5f
        ).first()
        assertEquals("próximo", SurroundingsPolicy.directionText(picked, true))
    }
}
