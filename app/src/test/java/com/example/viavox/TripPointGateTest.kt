package com.example.viavox

import com.example.viavox.model.TripPoint
import com.example.viavox.service.TripPointGate
import org.junit.Assert.*
import org.junit.Test

class TripPointGateTest {
    private val gate = TripPointGate()
    private fun point(lat: Double, time: Long, accuracy: Float = 6f): TripPoint =
        TripPoint(lat,-60.0200,time,accuracy)

    @Test fun acceptsFirstAccuratePosition() {
        assertEquals(0.0,gate.distanceIfAccepted(null,point(-3.1000,1_000L))!!,0.001)
    }

    @Test fun rejectsFlickerAndStationaryJitter() {
        val previous = point(-3.1000,1_000L)
        assertNull(gate.distanceIfAccepted(previous,point(-3.10005,2_000L)))
        assertNull(gate.distanceIfAccepted(previous,point(-3.10001,6_000L)))
    }

    @Test fun recordsRealMovementWithoutTeleportation() {
        val previous = point(-3.1000,1_000L)
        val normal = gate.distanceIfAccepted(previous,point(-3.1002,6_000L))
        assertNotNull(normal)
        assertTrue(normal!! > 20.0)
        assertNull(gate.distanceIfAccepted(previous,point(-3.1900,6_000L)))
    }

    @Test fun blocksBadAccuracyOrCoordinates() {
        assertNull(gate.distanceIfAccepted(null,point(-3.1000,1_000L,85f)))
        assertNull(gate.distanceIfAccepted(null,point(999.0,1_000L)))
        assertNull(gate.distanceIfAccepted(null,point(Double.NaN,1_000L)))
        assertNull(gate.distanceIfAccepted(null,point(-3.1000,1_000L,0f)))
    }
}
