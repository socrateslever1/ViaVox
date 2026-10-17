package com.example.viavox

import com.example.viavox.model.PoiCategory
import com.example.viavox.model.PointOfInterest
import com.example.viavox.model.RelativeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PointOfInterestTest {

    @Test
    fun testDistanceTo_approximateDistance() {
        // Two points ~111 meters apart in latitude
        val poi = PointOfInterest(
            id = "test_1",
            name = "Hospital 28 de Agosto",
            category = PoiCategory.HEALTH,
            latitude = -3.1000,
            longitude = -60.0200
        )

        val distance = poi.distanceTo(-3.1010, -60.0200)
        // 0.001 deg lat is approximately 111 meters
        assertTrue("Distance should be around 111m, got $distance", distance in 105.0..118.0)
    }

    @Test
    fun testRelativeDirection_lowSpeed_returnsNearby() {
        val poi = PointOfInterest(
            id = "test_2",
            name = "Posto Shell",
            category = PoiCategory.FUEL,
            latitude = -3.1000,
            longitude = -60.0200
        )

        val dir = poi.calculateRelativeDirection(
            userLat = -3.1005,
            userLon = -60.0200,
            vehicleBearing = 0f,
            speedMps = 1.0f // < 2.0 m/s
        )

        assertEquals(RelativeDirection.NEARBY, dir)
    }

    @Test
    fun testRelativeDirection_drivingNorth_poiEast_returnsRight() {
        // Vehicle at (-3.1010, -60.0200) moving North (bearing 0°) at 10 m/s (~36 km/h)
        // POI at same latitude, but East longitude (-60.0190)
        val poi = PointOfInterest(
            id = "test_3",
            name = "Escola Estadual",
            category = PoiCategory.EDUCATION,
            latitude = -3.1010,
            longitude = -60.0190
        )

        val dir = poi.calculateRelativeDirection(
            userLat = -3.1010,
            userLon = -60.0200,
            vehicleBearing = 0f, // North
            speedMps = 10.0f
        )

        assertEquals(RelativeDirection.RIGHT, dir)
    }

    @Test
    fun testRelativeDirection_drivingNorth_poiWest_returnsLeft() {
        // Vehicle at (-3.1010, -60.0200) moving North (bearing 0°) at 10 m/s
        // POI at same latitude, but West longitude (-60.0210)
        val poi = PointOfInterest(
            id = "test_4",
            name = "Parque do Idoso",
            category = PoiCategory.PARK,
            latitude = -3.1010,
            longitude = -60.0210
        )

        val dir = poi.calculateRelativeDirection(
            userLat = -3.1010,
            userLon = -60.0200,
            vehicleBearing = 0f, // North
            speedMps = 10.0f
        )

        assertEquals(RelativeDirection.LEFT, dir)
    }
}
