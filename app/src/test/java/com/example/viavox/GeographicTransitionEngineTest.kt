package com.example.viavox

import com.example.viavox.model.AssistanceMode
import com.example.viavox.model.Place
import com.example.viavox.service.GeoFix
import com.example.viavox.service.GeographicTransition
import com.example.viavox.service.RoadDetectionEngine
import com.example.viavox.service.PoliceJurisdictionSpeechResolver
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GeographicTransitionEngineTest {

    private fun engine(events: MutableList<GeographicTransition>,
        scope: kotlinx.coroutines.CoroutineScope) = RoadDetectionEngine(
        coroutineScope = scope,
        onPlaceUpdated = {},
        onRoadAnnounceNeeded = { _, _ -> },
        onStatusMessage = {},
        onGeographicTransition = events::add
    )

    @Test fun initialExplorerFollowsZoneBairroRua() = runTest(UnconfinedTestDispatcher()) {
        val events = mutableListOf<GeographicTransition>()
        val engine = engine(events, backgroundScope)
        engine.processRoadDecision(
            Place("Rua Benjamin Constant", "Petrópolis", "Manaus",
                zone = "Zona Sul", policeDistrict = "3º DIP · 3ª CICOM"),
            GeoFix(-3.10,-60.01,accuracyMeters=8f,speedMps=0f)
        )
        assertEquals(1, events.size)
        assertEquals(
            "Entrando na Zona Sul. Bairro Petrópolis. Rua Benjamin Constant.",
            events.single().sentence(AssistanceMode.EXPLORER)
        )
        assertEquals(
            "Entrando na Zona Sul. Área da 3ª CICOM. CPA Sul. Bairro Petrópolis. Rua Benjamin Constant.",
            events.single().sentence(AssistanceMode.TERRITORIAL)
        )
        assertEquals(
            "Entrando na Zona Sul. Bairro Petrópolis. Rua Benjamin Constant.",
            events.single().sentence(AssistanceMode.ACCESSIBILITY)
        )
    }

    @Test fun announcesZoneAndNeighbourhoodAsSoonAsConfirmedEvenWithoutRoad() =
        runTest(UnconfinedTestDispatcher()) {
            val events = mutableListOf<GeographicTransition>()
            val engine = engine(events, backgroundScope)
            engine.processRoadDecision(
                Place(Place.UNKNOWN_ROAD, "Centro", "Manaus", zone = "Zona Sul",
                    policeDistrict = "24º DIP · 24ª CICOM"),
                GeoFix(-3.13, -60.02, accuracyMeters=8f)
            )
            assertEquals(1, events.size)
            assertNull(events.single().road)
            assertEquals(
                "Entrando na Zona Sul. Área da 24ª CICOM. CPA Sul. Bairro Centro.",
                events.single().sentence(AssistanceMode.TERRITORIAL)
            )
            engine.processRoadDecision(
                Place("Rua 10 de Julho", "Centro", "Manaus", zone = "Zona Sul",
                    policeDistrict = "24º DIP · 24ª CICOM"),
                GeoFix(-3.1301,-60.02,accuracyMeters=8f)
            )
            assertEquals(2, events.size)
            assertEquals("Rua 10 de Julho.",events.last().sentence(AssistanceMode.TERRITORIAL))
        }

    @Test fun zoneChangesImmediatelyInSameStreetWithoutRepeatingRoad() =
        runTest(UnconfinedTestDispatcher()) {
            val events = mutableListOf<GeographicTransition>()
            val engine = engine(events, backgroundScope)
            val street = "Avenida Eduardo Ribeiro"
            engine.processRoadDecision(
                Place(street,"Centro","Manaus",zone="Zona Sul"),
                GeoFix(-3.13,-60.02,accuracyMeters=7f, speedMps=4f))
            engine.processRoadDecision(
                Place(street,"Centro","Manaus",zone="Zona Centro-Sul"),
                GeoFix(-3.12,-60.02,accuracyMeters=7f, speedMps=4f))
            assertEquals(2,events.size)
            assertEquals("Entrando na Zona Centro-Sul.",
                events.last().sentence(AssistanceMode.EXPLORER))
            engine.processRoadDecision(
                Place(street,"Centro","Manaus",zone="Zona Centro-Sul"),
                GeoFix(-3.119,-60.02,accuracyMeters=7f, speedMps=4f))
            assertEquals(2,events.size)
        }

    @Test fun changingCicomInSameCpaSpeaksCicomButDoesNotRepeatCpa() =
        runTest(UnconfinedTestDispatcher()) {
            val events = mutableListOf<GeographicTransition>()
            val engine = engine(events, backgroundScope)
            val road = "Rua Grande"
            fun enter(neigh:String, police:String, lat:Double) {
                engine.processRoadDecision(
                    Place(road,neigh,"Manaus",zone="Zona Sul",
                        policeDistrict=police),
                    GeoFix(lat,-60.02,accuracyMeters=8f,speedMps=4f)
                )
            }
            enter("Centro", "24º DIP · 24ª CICOM", -3.130)
            enter("Petrópolis", "3º DIP · 3ª CICOM", -3.120)
            assertEquals(2,events.size)
            assertEquals(
                "Área da 3ª CICOM. Bairro Petrópolis.",
                events.last().sentence(AssistanceMode.TERRITORIAL)
            )
            assertNull(events.last().cpa)
            enter("Centro","24º DIP · 24ª CICOM",-3.130)
            assertEquals(
                "Área da 24ª CICOM. Bairro Centro.",
                events.last().sentence(AssistanceMode.TERRITORIAL)
            )
        }

    @Test fun cpaIsAnnouncedOnceOnEntryAndAgainAfterLeavingAndReturning() =
        runTest(UnconfinedTestDispatcher()) {
            val events = mutableListOf<GeographicTransition>()
            val engine = engine(events, backgroundScope)
            val road = "Rua Grande"
            fun enter(neigh:String, zone:String, police:String, lat:Double) =
                engine.processRoadDecision(
                    Place(road,neigh,"Manaus",zone=zone,policeDistrict=police),
                    GeoFix(lat,-60.01,accuracyMeters=8f,speedMps=4f)
                )
            enter("Centro","Zona Sul","24º DIP · 24ª CICOM",-3.13)
            enter("Aleixo","Zona Centro-Sul","16º DIP · 16ª CICOM",-3.10)
            enter("Centro","Zona Sul","24º DIP · 24ª CICOM",-3.13)
            assertEquals(listOf("CPA Sul","CPA Centro-Sul/Centro-Oeste","CPA Sul"),
                events.mapNotNull { it.cpa })
        }

    @Test fun coarseGpsNeedsTwoConsistentReadingsForNewArea() =
        runTest(UnconfinedTestDispatcher()) {
            val events = mutableListOf<GeographicTransition>()
            val engine = engine(events, backgroundScope)
            val road = "Rua Grande"
            engine.processRoadDecision(
                Place(road,"Centro","Manaus",zone="Zona Sul"),
                GeoFix(-3.13,-60.02,accuracyMeters=35f, speedMps=4f))
            assertEquals(1,events.size) // Initial road, no unverified zone claim
            assertNull(events[0].zone)
            engine.processRoadDecision(
                Place(road,"Centro","Manaus",zone="Zona Sul"),
                GeoFix(-3.1301,-60.02,accuracyMeters=35f, speedMps=4f))
            assertEquals("Entrando na Zona Sul. Bairro Centro.",
                events.last().sentence(AssistanceMode.EXPLORER))
        }

    @Test fun neverInfersManausPoliceUnitFromUnknownOrOtherCity() {
        assertNull(PoliceJurisdictionSpeechResolver.resolve("Circunscrição policial",
            "Manaus"))
        assertNull(PoliceJurisdictionSpeechResolver.resolve("24ª CICOM",
            "Belém"))
        assertEquals("CPA Centro-Sul/Centro-Oeste",
            PoliceJurisdictionSpeechResolver.resolve("16º DIP · 16ª CICOM",
                "Manaus")?.cpa)
    }
}
