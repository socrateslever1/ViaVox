package com.example.viavox

import com.example.viavox.model.NavigationCameraMath
import com.example.viavox.model.SupportedRoutingProfile
import com.example.viavox.model.VehicleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleAndCompassTest {
    @Test fun threeModesAreUniqueAndPersistByValue() {
        assertEquals(VehicleMode.CAR,VehicleMode.from("car"))
        assertEquals(VehicleMode.MOTORCYCLE,VehicleMode.from("MOTORCYCLE"))
        assertEquals(VehicleMode.WALKING,VehicleMode.from("walking"))
        assertEquals(VehicleMode.CAR,VehicleMode.from("unexpected"))
        assertEquals(3,VehicleMode.entries.size)
        assertTrue(VehicleMode.entries.map { it.storedValue }.distinct().size == 3)
    }

    @Test fun compassRotationWinsOverStaleGpsHeadingEvenWhenStationary() {
        val direction = NavigationCameraMath.resolveHeading(
            phoneHeading = 245.0,
            gpsCourse = null,
            lastCourse = 70.0,
            followCompass = true
        )
        assertEquals(245.0,direction!!,0.00001)
    }

    @Test fun compassModeFollowsActualPhoneTurnNotTravelCourse() {
        val first = NavigationCameraMath.resolveHeading(
            phoneHeading = 90.0,gpsCourse = 4.0,lastCourse = 10.0,followCompass = true)
        val second = NavigationCameraMath.resolveHeading(
            phoneHeading = 180.0,gpsCourse = 4.0,lastCourse = 10.0,followCompass = true)
        assertEquals(90.0,first!!,0.00001)
        assertEquals(180.0,second!!,0.00001)
    }

    @Test fun gpsCourseModeIgnoresPhoneRotationWhileMoving() {
        assertEquals(25.0,NavigationCameraMath.resolveHeading(
            phoneHeading = 230.0,gpsCourse = 25.0,lastCourse = 20.0,
            followCompass = false)!!,0.001)
        assertEquals(20.0,NavigationCameraMath.resolveHeading(
            phoneHeading = 230.0,gpsCourse = null,lastCourse = 20.0,
            followCompass = false)!!,0.001)
    }

    @Test fun headingFallbackDoesNotInventDirection() {
        assertEquals(null, NavigationCameraMath.resolveHeading(
            phoneHeading = null,gpsCourse = null,lastCourse = null,followCompass = true))
        assertEquals(350.0, NavigationCameraMath.resolveHeading(
            phoneHeading = -10.0,gpsCourse = null,lastCourse = null,followCompass = true)!!,0.0001)
    }

    @Test fun shortestArcCameraRotationAcrossNorth() {
        val rotated = NavigationCameraMath.smoothBearing(359.0,1.0,0.75)
        assertEquals(0.5,rotated,0.0001)
    }

    @Test fun noFakeWalkingDirectionsFromCarBackend() {
        assertTrue(SupportedRoutingProfile.isSupported(VehicleMode.CAR))
        assertTrue(SupportedRoutingProfile.isSupported(VehicleMode.MOTORCYCLE))
        assertFalse(SupportedRoutingProfile.isSupported(VehicleMode.WALKING))
        assertTrue(SupportedRoutingProfile.disclosure(VehicleMode.WALKING).contains("não disponível"))
    }
}
