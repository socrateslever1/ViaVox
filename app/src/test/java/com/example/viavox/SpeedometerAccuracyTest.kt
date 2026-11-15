package com.example.viavox

import com.example.viavox.model.GpsSpeed
import com.example.viavox.service.SafetyAlertEngine.SafetyFeature
import com.example.viavox.service.SafetyAlertEngine.SafetyFeature.Type
import com.example.viavox.ui.components.ESTIMATED_LIMIT_MARGIN_KMH
import com.example.viavox.ui.components.isOverSpeedLimit
import com.example.viavox.ui.components.resolveRoadSpeedLimit
import com.example.viavox.ui.components.resolveRoadSpeedLimitInfo
import org.junit.Assert.*
import org.junit.Test

class SpeedometerAccuracyTest {
    private val lat = -3.1000
    private val lon = -60.0200
    private fun north(meters: Double) = lat + meters / 111_195.0

    private fun feature(id: String, type: Type, latitude: Double, limit: Int?, longitude: Double = lon) =
        SafetyFeature(id = id, type = type, latitude = latitude, longitude = longitude, speedLimitKmh = limit)

    // ---- speed limit

    @Test fun onlyCamerasSetTheLimit_notLombadasOrCrossings() {
        val features = listOf(
            feature("bump", Type.SPEED_BUMP, north(40.0), 20),
            feature("crossing", Type.LEVEL_CROSSING, north(60.0), 30),
            feature("toll", Type.TOLL_BOOTH, north(80.0), 40))
        val info = resolveRoadSpeedLimitInfo("Avenida Brasil", features, lat, lon, 0f)
        assertEquals("falls back to the avenue guess", 60, info.kmh)
        assertTrue(info.estimated)
    }

    @Test fun cameraAheadOnTheSameRoadWins() {
        val camera = feature("radar", Type.SPEED_CAMERA, north(300.0), 50)
        val info = resolveRoadSpeedLimitInfo("Avenida Brasil", listOf(camera), lat, lon, 0f)
        assertEquals(50, info.kmh)
        assertFalse("a radar limit is not a guess", info.estimated)
    }

    @Test fun cameraBehindOrOnAnotherStreetDoesNotSetTheLimit() {
        // 300 m behind: the vehicle is moving away from it, beyond "just passed".
        val behind = feature("behind", Type.SPEED_CAMERA, north(-300.0), 30)
        assertEquals(60, resolveRoadSpeedLimit("Avenida Brasil", listOf(behind), lat, lon, 0f))
        // 300 m ahead but off to the side (a parallel avenue): outside the cone.
        val parallel = feature("parallel", Type.SPEED_CAMERA, north(100.0), 30, longitude = lon + 0.0028)
        assertEquals(60, resolveRoadSpeedLimit("Avenida Brasil", listOf(parallel), lat, lon, 0f))
        // 800 m is no longer "nearby".
        val far = feature("far", Type.SPEED_CAMERA, north(800.0), 30)
        assertEquals(60, resolveRoadSpeedLimit("Avenida Brasil", listOf(far), lat, lon, 0f))
    }

    @Test fun justPassedCameraStillAppliesForAShortDistance() {
        val passed = feature("passed", Type.SPEED_CAMERA, north(-100.0), 40)
        assertEquals(40, resolveRoadSpeedLimit("Avenida Brasil", listOf(passed), lat, lon, 0f))
    }

    @Test fun withoutBearingTheNearbyCameraStillCounts() {
        val camera = feature("radar", Type.SPEED_CAMERA, north(110.0), 50)
        assertEquals(50, resolveRoadSpeedLimit("Avenida Qualquer", listOf(camera), lat, lon))
    }

    @Test fun implausibleCameraLimitsAreIgnored() {
        val strange = feature("odd", Type.SPEED_CAMERA, north(100.0), 5)
        assertEquals(60, resolveRoadSpeedLimit("Avenida Brasil", listOf(strange), lat, lon, 0f))
    }

    // ---- alarm

    @Test fun anEstimatedLimitGetsAMarginBeforeTheRedAlarm() {
        assertTrue(isOverSpeedLimit(61, 60, estimated = false))
        assertFalse(isOverSpeedLimit(65, 60, estimated = true))
        assertTrue(isOverSpeedLimit(60 + ESTIMATED_LIMIT_MARGIN_KMH + 1, 60, estimated = true))
        assertFalse(isOverSpeedLimit(200, null, estimated = false))
    }

    // ---- speed itself

    private fun fix(seconds: Int, metersNorth: Double, accuracy: Float = 6f) =
        GpsSpeed.Fix(lat + metersNorth / 111_195.0, lon, seconds * 1000L, accuracy)

    @Test fun reportedSpeedIsUsedWhenTheProviderHasIt() {
        val kmh = GpsSpeed.kmh(true, 10f, 0.5f, fix(10, 100.0), fix(5, 0.0))
        assertEquals(36f, kmh, 0.01f)
    }

    @Test fun missingSpeedIsDerivedFromTheTwoFixes() {
        // 100 m in 5 s = 20 m/s = 72 km/h: the old code showed 0.
        val kmh = GpsSpeed.kmh(false, 0f, null, fix(10, 100.0), fix(5, 0.0))
        assertEquals(72f, kmh, 1.0f)
    }

    @Test fun uncertainReportedSpeedYieldsToTheBetterEstimate() {
        val kmh = GpsSpeed.kmh(true, 2f, 9f, fix(10, 100.0), fix(5, 0.0))
        assertEquals(72f, kmh, 1.0f)
    }

    @Test fun jitterWhileStoppedIsNotSpeed() {
        // 4 m of wander in 3 s with 8 m accuracy.
        val kmh = GpsSpeed.kmh(false, 0f, null, fix(13, 4.0, 8f), fix(10, 0.0, 8f))
        assertEquals(0f, kmh, 0.001f)
    }

    @Test fun noPreviousFixOrAHugeGapGivesNoInventedSpeed() {
        assertEquals(0f, GpsSpeed.kmh(false, 0f, null, fix(10, 100.0), null), 0f)
        assertEquals(0f, GpsSpeed.kmh(false, 0f, null, fix(100, 5000.0), fix(5, 0.0)), 0f)
    }

    @Test fun teleportingFixesAreNotAbsurdSpeeds() {
        // 3 km in 2 s would be 5400 km/h: a provider glitch, not driving.
        val kmh = GpsSpeed.kmh(false, 0f, null, fix(12, 3000.0), fix(10, 0.0))
        assertEquals(0f, kmh, 0f)
    }
}
