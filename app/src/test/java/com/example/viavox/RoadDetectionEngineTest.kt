package com.example.viavox

import com.example.viavox.service.RoadDetectionEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class RoadDetectionEngineTest {

    @Test
    fun testNormalizeRoadName_abbreviationsExpanded() {
        assertEquals("Rua das Flores", RoadDetectionEngine.normalizeRoadName("R. das Flores"))
        assertEquals("Avenida Djalma Batista", RoadDetectionEngine.normalizeRoadName("Av. Djalma Batista"))
        assertEquals("Travessa Belém", RoadDetectionEngine.normalizeRoadName("Trav. Belém"))
        assertEquals("Alameda Cosme Ferreira", RoadDetectionEngine.normalizeRoadName("Al. Cosme Ferreira"))
        assertEquals("Rodovia AM-010", RoadDetectionEngine.normalizeRoadName("Rod. AM-010"))
    }

    @Test
    fun testNormalizeForComparison_ignoresAccentsAndCase() {
        val s1 = RoadDetectionEngine.normalizeForComparison("Avenida São Luís")
        val s2 = RoadDetectionEngine.normalizeForComparison("AVENIDA SAO LUIS")
        assertEquals(s1, s2)

        val r1 = RoadDetectionEngine.normalizeForComparison("Rua Pará")
        val r2 = RoadDetectionEngine.normalizeForComparison("rua para")
        assertEquals(r1, r2)
    }
}
