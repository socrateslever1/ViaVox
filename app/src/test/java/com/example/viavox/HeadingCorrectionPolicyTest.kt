package com.example.viavox

import com.example.viavox.service.HeadingCorrectionPolicy
import org.junit.Assert.*
import org.junit.Test

class HeadingCorrectionPolicyTest {
    @Test fun correctsMagneticNorthUsingLocalDeclination() {
        assertEquals(10f, HeadingCorrectionPolicy.trueNorth(0f, 10f) ?: -1f, 0.01f)
        assertEquals(350f, HeadingCorrectionPolicy.trueNorth(0f, -10f) ?: -1f, 0.01f)
        assertEquals(5f, HeadingCorrectionPolicy.trueNorth(355f, 10f) ?: -1f, 0.01f)
        assertEquals(170f, HeadingCorrectionPolicy.trueNorth(180f, -10f) ?: -1f, 0.01f)
    }

    @Test fun invalidAnglesDoNotCreateDirections() {
        assertNull(HeadingCorrectionPolicy.trueNorth(Float.NaN, 5f))
        assertNull(HeadingCorrectionPolicy.trueNorth(15f, Float.POSITIVE_INFINITY))
        assertNull(HeadingCorrectionPolicy.trueNorth(15f, 180f))
    }
}
