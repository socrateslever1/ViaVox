package com.example.viavox

import com.example.viavox.model.Place
import com.example.viavox.service.GeoFix
import com.example.viavox.service.GeographicTransition
import com.example.viavox.service.RoadDetectionEngine
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GeographicTransitionEngineTest {
    @Test fun reportsZoneSeparatelyOnlyAfterTwoConfirmations() = runTest(UnconfinedTestDispatcher()) {
        val events = mutableListOf<GeographicTransition>()
        val engine = RoadDetectionEngine(
            coroutineScope = backgroundScope,
            onPlaceUpdated = {},
            onRoadAnnounceNeeded = { _, _ -> },
            onStatusMessage = {},
            onGeographicTransition = events::add
        )
        val street = "Rua Benjamin Constant"
        engine.processRoadDecision(Place(street,"Petrópolis","Manaus",zone="Zona Centro-Sul"),
            GeoFix(-3.10,-60.01,speedMps=4f))
        engine.processRoadDecision(Place(street,"Petrópolis","Manaus",zone="Zona Leste"),
            GeoFix(-3.1003,-60.01,speedMps=4f))
        assertEquals(1,events.size) // no zone-jump on one isolated GIS reading
        engine.processRoadDecision(Place(street,"Petrópolis","Manaus",zone="Zona Leste"),
            GeoFix(-3.1006,-60.01,speedMps=4f))
        assertEquals(2,events.size)
        assertEquals("Entrando na Zona Leste.",events.last().sentence())
    }

    @Test fun confirmsRoadThenAnnouncesNeighbourhoodAndRoadTogether() = runTest(UnconfinedTestDispatcher()) {
        val events = mutableListOf<GeographicTransition>()
        val engine = RoadDetectionEngine(
            coroutineScope = backgroundScope,
            onPlaceUpdated = {},
            onRoadAnnounceNeeded = { _, _ -> },
            onStatusMessage = {},
            onGeographicTransition = events::add
        )
        engine.processRoadDecision(Place("Rua A","Petrópolis","Manaus",zone="Zona Centro-Sul"),
            GeoFix(-3.1000,-60.0100,speedMps=6f))
        engine.processRoadDecision(Place("Rua B","Centro","Manaus",zone="Zona Sul"),
            GeoFix(-3.1003,-60.0100,speedMps=6f))
        assertEquals(1,events.size)
        engine.processRoadDecision(Place("Rua B","Centro","Manaus",zone="Zona Sul"),
            GeoFix(-3.1006,-60.0100,speedMps=6f))
        assertEquals(2,events.size)
        assertEquals("Entrando na Zona Sul. Bairro Centro. Rua B.",events.last().sentence())
        engine.processRoadDecision(Place("Rua B","Centro","Manaus",zone="Zona Sul"),
            GeoFix(-3.1009,-60.0100,speedMps=6f))
        assertEquals(2,events.size)
    }
}
