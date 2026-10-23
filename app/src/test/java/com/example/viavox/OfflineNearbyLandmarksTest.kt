package com.example.viavox

import com.example.viavox.data.OfflineNearbyLandmarks
import com.example.viavox.model.ScanDirection
import com.example.viavox.model.SurroundingsPolicy
import org.junit.Assert.*
import org.junit.Test

class OfflineNearbyLandmarksTest {

    @Test fun curatedMuseumIsRealAndFindableInside100Meters() {
        val points = OfflineNearbyLandmarks.verifiedSites()
        val matches = SurroundingsPolicy.select(
            points, -3.13087, -60.023431219, 100,
            ScanDirection.AROUND, null, 5f
        )
        assertTrue(matches.any { it.landmark.name == "Teatro Amazonas" })
        assertFalse(matches.any { it.landmark.name == "Palácio Rio Negro" })
    }

    @Test fun emilioMoreiraCorridorHasOfflinePlacesWithinSelectedRadius() {
        // Coordinates are a publicly mapped sample of the street, not the user's GPS.
        val points = OfflineNearbyLandmarks.verifiedSites()
        val near100 = SurroundingsPolicy.select(
            points, -3.1248, -60.0155, 100,
            ScanDirection.AROUND, null, 7f
        )
        val near350 = SurroundingsPolicy.select(
            points, -3.1248, -60.0155, 350,
            ScanDirection.AROUND, null, 7f
        )
        assertTrue(near350.any { it.landmark.name == "Cartório Moreira" })
        assertTrue(near350.size > near100.size)
        assertTrue(near350.all { it.distanceMeters <= 350.0 })
    }

    @Test fun forwardUsesTrueDirectionAndNeverInventsRightForBuildingCentres() {
        val points = OfflineNearbyLandmarks.verifiedSites()
        val matches = SurroundingsPolicy.select(
            points, -3.13087, -60.023431219, 100,
            ScanDirection.FRONT, 0f, 5f
        )
        val theatre = matches.firstOrNull { it.landmark.name == "Teatro Amazonas" }
        assertNotNull(theatre)
        assertEquals("próximo", SurroundingsPolicy.directionText(theatre!!, true))
        assertTrue(SurroundingsPolicy.select(points,
            -3.13087,-60.023431219,100,ScanDirection.FRONT,180f,5f).isEmpty())
    }

    @Test fun realPolicePointCoordinatesAreParsedFromBundledGeojson() {
        val points = OfflineNearbyLandmarks.parsePoliceStations(
            """{"type":"FeatureCollection","features":[
             {"type":"Feature","properties":{"name":"24ª CICOM / 24º DIP"},
              "geometry":{"type":"Point","coordinates":[-60.016345,-3.137910]}},
             {"type":"Feature","properties":{"name":"malformed"},
              "geometry":{"type":"Point","coordinates":[null,null]}}]}"""
        )
        assertEquals(1,points.size)
        assertEquals("24ª CICOM / 24º DIP",points.first().name)
        assertEquals(-3.137910,points.first().latitude,0.000001)
        assertEquals(-60.016345,points.first().longitude,0.000001)
    }
}
