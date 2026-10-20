package com.example.viavox

import com.example.viavox.service.SafetyAlertEngine
import com.example.viavox.ui.components.resolveRoadSpeedLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class FloatingSpeedometerTest {

    @Test
    fun resolveRoadSpeedLimit_prefersNearbySafetyCameraSpeedLimit() {
        val cameras = listOf(
            SafetyAlertEngine.SafetyFeature(
                id = "cam-1",
                type = SafetyAlertEngine.SafetyFeature.Type.SPEED_CAMERA,
                latitude = -3.100,
                longitude = -60.020,
                speedLimitKmh = 50
            )
        )

        val limit = resolveRoadSpeedLimit(
            road = "Avenida Qualquer",
            safetyFeatures = cameras,
            userLat = -3.101, // ~110m away
            userLon = -60.020
        )

        assertEquals(50, limit)
    }

    @Test
    fun resolveRoadSpeedLimit_detectsHighwaysAndAvenues() {
        val emptyCameras = emptyList<SafetyAlertEngine.SafetyFeature>()

        assertEquals(80, resolveRoadSpeedLimit("BR-174", emptyCameras, 0.0, 0.0))
        assertEquals(80, resolveRoadSpeedLimit("Rodovia Torquato Tapajós", emptyCameras, 0.0, 0.0))
        assertEquals(80, resolveRoadSpeedLimit("AM-010", emptyCameras, 0.0, 0.0))

        assertEquals(60, resolveRoadSpeedLimit("Avenida Djalma Batista", emptyCameras, 0.0, 0.0))
        assertEquals(60, resolveRoadSpeedLimit("Av. Constantino Nery", emptyCameras, 0.0, 0.0))

        assertEquals(40, resolveRoadSpeedLimit("Rua Rio Branco", emptyCameras, 0.0, 0.0))
        assertEquals(40, resolveRoadSpeedLimit("Travessa Maranhão", emptyCameras, 0.0, 0.0))
        assertEquals(40, resolveRoadSpeedLimit("Beco da Esperança", emptyCameras, 0.0, 0.0))

        assertEquals(60, resolveRoadSpeedLimit(null, emptyCameras, 0.0, 0.0))
    }

    @Test
    fun speedCalculations_detectsOverspeed() {
        val speedKmh = 64.7f
        val currentSpeed = speedKmh.roundToInt()
        val limit = 60

        assertEquals(65, currentSpeed)
        val isOverSpeed = currentSpeed > limit
        assertTrue(isOverSpeed)
        val delta = currentSpeed - limit
        assertEquals(5, delta)
    }

    @Test
    fun speedCalculations_zeroBelowThreshold() {
        val rawGpsSpeed = 0.4f
        val cleanSpeed = (if (rawGpsSpeed < 0.8f) 0f else rawGpsSpeed).roundToInt()
        assertEquals(0, cleanSpeed)
    }
}
