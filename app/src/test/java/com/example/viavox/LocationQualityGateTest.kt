package com.example.viavox

import com.example.viavox.service.LocationQualityGate
import com.example.viavox.service.LocationSample
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationQualityGateTest {
    private fun fix(
        latitude: Double = -3.1025,
        longitude: Double = -60.0235,
        accuracy: Float = 7f,
        speed: Float = 0f,
        time: Long = 10_000L
    ) = LocationSample(latitude, longitude, accuracy, speed, time)

    @Test
    fun firstStationaryFixIsAcceptedForInitialStreet() {
        val gate = LocationQualityGate()
        assertTrue(gate.accept(fix(), nowMs = 10_000L))
    }

    @Test
    fun doesNotMovePuckForStationaryGpsJitter() {
        val gate = LocationQualityGate()
        assertTrue(gate.accept(fix(), 10_000L))
        assertFalse(gate.accept(fix(latitude = -3.10248, time = 13_000L), 13_000L))
        assertTrue(gate.accept(fix(latitude = -3.1022, speed = 3f, time = 17_000L), 17_000L))
    }

    @Test
    fun rejectsOldCachedLocationsAndOutOfOrderCallbacks() {
        val gate = LocationQualityGate()
        assertFalse(gate.accept(fix(time = 1_000L), nowMs = 25_000L))
        assertTrue(gate.accept(fix(time = 25_000L), 25_000L))
        assertFalse(gate.accept(fix(time = 24_500L), 25_100L))
    }

    @Test
    fun rejectsLowAccuracyAndInvalidCoordinates() {
        val gate = LocationQualityGate()
        assertFalse(gate.accept(fix(accuracy = 130f), 10_000L))
        assertFalse(gate.accept(fix(latitude = 91.0), 10_000L))
        assertFalse(gate.accept(fix(longitude = Double.NaN), 10_000L))
        assertTrue(gate.accept(fix(accuracy = 8f), 10_000L))
    }

    @Test
    fun rejectsImpossibleTeleportButKeepsNextPlausibleReading() {
        val gate = LocationQualityGate()
        assertTrue(gate.accept(fix(), 10_000L))
        assertFalse(gate.accept(fix(latitude = -3.2, time = 11_000L, speed = 5f), 11_000L))
        assertTrue(gate.accept(fix(latitude = -3.1021, time = 16_000L, speed = 5f), 16_000L))
    }

    @Test
    fun resetAllowsFreshSession() {
        val gate = LocationQualityGate()
        assertTrue(gate.accept(fix(), 10_000L))
        gate.reset()
        assertTrue(gate.accept(fix(latitude = -3.119, time = 11_000L), 11_000L))
    }
}
