package com.example.viavox

import com.example.viavox.service.TerritoryGeojsonResolver
import com.example.viavox.service.RoadDetectionEngine
import com.example.viavox.service.GeoFix
import com.example.viavox.service.GeographicTransition
import com.example.viavox.model.AssistanceMode
import com.example.viavox.model.Place
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TerritoryGeojsonResolverTest {
    private fun feature(name:String, zone:String?=null, west:Double=-60.030,
                        south:Double=-3.140, east:Double=-60.010, north:Double=-3.120): String {
        val zoned = if (zone != null) ""","zona":"$zone"""" else ""
        return """{"type":"Feature","properties":{"name":"$name"$zoned},
         "geometry":{"type":"Polygon","coordinates":[[
         [$west,$south],[$east,$south],[$east,$north],[$west,$north],[$west,$south]
         ]]}}"""
    }

    private fun collection(vararg features:String): String =
        """{"type":"FeatureCollection","features":[${features.joinToString(",")}]}"""

    private fun maps(): TerritoryGeojsonResolver =
        TerritoryGeojsonResolver.parse(
            collection(feature("Centro","Zona Sul")),
            collection(feature("24ª CICOM (24º DIP) - Setor 24.2"))
        )

    @Test fun knownOfflinePolygonAnnouncesZoneBairroCicomCpa() {
        val match = maps().resolve(-3.130, -60.020, 7f)
        assertNotNull(match)
        assertEquals("Zona Sul",match?.zone)
        assertEquals("Centro",match?.neighbourhood)
        assertEquals("24ª CICOM",match?.cicom)
        assertEquals("CPA Sul",match?.cpa)
    }

    @Test fun nearPolygonEdgeOrInaccurateGpsDoesNotInventEntry() {
        assertNull(maps().resolve(-3.139995, -60.020, 10f))
        assertNull(maps().resolve(-3.130, -60.020, 40f))
        assertNull(maps().resolve(-4.0, -60.020, 7f))
    }

    @Test fun gpsPolygonSpeaksBeforeAnyAddressLookupCompletes() =
        runTest(UnconfinedTestDispatcher()) {
            val events = mutableListOf<GeographicTransition>()
            val resolver = maps()
            val engine = RoadDetectionEngine(
                coroutineScope=backgroundScope,
                onPlaceUpdated={},
                onRoadAnnounceNeeded={_,_->},
                onStatusMessage={},
                onGeographicTransition=events::add
            )
            engine.territoryPolygonLookup = { fix ->
                resolver.resolve(fix.latitude,fix.longitude,fix.accuracyMeters)
            }
            // With no Nominatim call ever completing, the polygon still produces the entry.
            engine.onGeoFixUpdate(GeoFix(-3.13,-60.02,
                accuracyMeters=7f,speedMps=1f,timestampMs=1000L))
            assertEquals(1,events.size)
            assertEquals(
                "Entrando na Zona Sul. Área da 24ª CICOM. CPA Sul. Bairro Centro.",
                events.single().sentence(AssistanceMode.TERRITORIAL)
            )
            engine.processRoadDecision(
                Place("Rua 10 de Julho","Centro","Manaus",zone="Zona Sul",
                    policeDistrict="24º DIP · 24ª CICOM"),
                GeoFix(-3.13,-60.02,accuracyMeters=7f)
            )
            assertEquals(2,events.size)
            assertEquals("Rua 10 de Julho.",events.last().sentence(AssistanceMode.EXPLORER))
        }

    @Test fun polygonGeometryOverridesWrongOnlineNeighbourhood() =
        runTest(UnconfinedTestDispatcher()) {
            val events = mutableListOf<GeographicTransition>()
            val engine = RoadDetectionEngine(
                coroutineScope=backgroundScope,
                onPlaceUpdated={},
                onRoadAnnounceNeeded={_,_->},
                onStatusMessage={},
                onGeographicTransition=events::add
            )
            val resolver=maps()
            engine.territoryPolygonLookup = {
                resolver.resolve(it.latitude,it.longitude,it.accuracyMeters)
            }
            engine.processRoadDecision(
                Place("Rua 10 de Julho","São Jorge","Manaus",zone="Zona Oeste"),
                GeoFix(-3.13,-60.02,accuracyMeters=7f)
            )
            assertEquals("Entrando na Zona Sul. Bairro Centro. Rua 10 de Julho.",
                events.single().sentence(AssistanceMode.EXPLORER))
        }
}
