package com.example.viavox

import com.example.viavox.model.Place
import com.example.viavox.service.ConfidentStreet
import com.example.viavox.service.GeoFix
import com.example.viavox.service.IndexedStreetLine
import com.example.viavox.service.RoadDetectionEngine
import com.example.viavox.service.RoadMapMatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class FastStreetEntryTest {
    private fun line(name:String, lon:Double, south:Double=-3.14,
                     north:Double=-3.12) = IndexedStreetLine(
        name,1302603,listOf(south to lon,north to lon)
    )

    @Test fun choosesActualStreetPolylineOnAccurateUniqueGpsFix() {
        val fix=GeoFix(-3.1300,-60.0200,accuracyMeters=4f,
            speedMps=5f,bearing=0f,hasBearing=true)
        val found=RoadMapMatcher.choose(fix,listOf(
            line("Rua Benjamin Constant",-60.0200),
            line("Rua Paralela",-60.0198)
        ))
        assertEquals("Rua Benjamin Constant",found?.name)
        assertTrue((found?.distanceMeters ?: 100.0) < 2.0)
    }

    @Test fun refusesIntersectionAndUncertainGpsInsteadOfInventingStreet() {
        val crossing=IndexedStreetLine("Rua Cruzamento",1302603,
            listOf(-3.1300 to -60.0210,-3.1300 to -60.0190))
        val road=line("Avenida Principal",-60.0200)
        val intersection=GeoFix(-3.1300,-60.0200,accuracyMeters=4f,
            speedMps=1.0f,hasBearing=false)
        assertNull(RoadMapMatcher.choose(intersection,listOf(road,crossing)))
        assertNull(RoadMapMatcher.choose(intersection.copy(
            accuracyMeters=28f),listOf(road)))
    }

    @Test fun refusesTwoParallelStreetsWithinGpsUncertainty() {
        val fix=GeoFix(-3.1300,-60.02005,accuracyMeters=8f,
            speedMps=3.0f,hasBearing=false)
        assertNull(RoadMapMatcher.choose(fix,listOf(
            line("Rua A",-60.0200),line("Rua B",-60.02010)
        )))
    }

    @Test fun offlineRecognizesStreetEntryAfterTwoDistinctFixesWithoutNetwork() =
        runTest(UnconfinedTestDispatcher()) {
            val spoken=mutableListOf<String>()
            val engine=RoadDetectionEngine(
                coroutineScope=backgroundScope,
                onPlaceUpdated={},
                onRoadAnnounceNeeded={ message, _ -> spoken.add(message) },
                onStatusMessage={},
                reverseGeocodeProvider={_,_-> Result.failure(IOException("offline")) }
            )
            engine.cityNameForCode={ if(it==1302603) "Manaus" else "" }
            engine.localRoadLookup={ fix ->
                when {
                    fix.longitude < -60.02005 -> ConfidentStreet(
                        "Rua Antiga",1302603,1.0,19.0)
                    else -> ConfidentStreet(
                        "Rua Nova",1302603,1.0,19.0)
                }
            }
            engine.onGeoFixUpdate(GeoFix(-3.1300,-60.02020,
                accuracyMeters=5f,speedMps=5f,timestampMs=1_000L))
            assertEquals("Rua Antiga",engine.getCurrentConfirmedRoad())
            engine.onGeoFixUpdate(GeoFix(-3.1300,-60.02000,
                accuracyMeters=5f,speedMps=5f,timestampMs=3_000L))
            assertEquals("Rua Antiga",engine.getCurrentConfirmedRoad())
            engine.onGeoFixUpdate(GeoFix(-3.12992,-60.02000,
                accuracyMeters=5f,speedMps=5f,timestampMs=4_400L))
            assertEquals("Rua Nova",engine.getCurrentConfirmedRoad())
            assertEquals(2,spoken.size)
            engine.onGeoFixUpdate(GeoFix(-3.12983,-60.02000,
                accuracyMeters=5f,speedMps=5f,timestampMs=6_000L))
            assertEquals(2,spoken.size)
        }

    @Test fun stationaryOrGpsJitterCannotChangeRoadFromLocalCandidates() =
        runTest(UnconfinedTestDispatcher()) {
            val spoken=mutableListOf<String>()
            var candidate="Rua Real"
            val engine=RoadDetectionEngine(
                coroutineScope=backgroundScope,
                onPlaceUpdated={},
                onRoadAnnounceNeeded={ message, _ -> spoken.add(message) },
                onStatusMessage={},
                reverseGeocodeProvider={_,_->Result.failure(IOException("offline"))}
            )
            engine.localRoadLookup={ ConfidentStreet(candidate,1302603,0.5,15.0) }
            engine.onGeoFixUpdate(GeoFix(-3.13,-60.02,
                accuracyMeters=4f,timestampMs=1_000L))
            candidate="Rua Paralela"
            engine.onGeoFixUpdate(GeoFix(-3.130001,-60.020001,
                accuracyMeters=4f,speedMps=0f,timestampMs=3_000L))
            engine.onGeoFixUpdate(GeoFix(-3.130002,-60.020002,
                accuracyMeters=4f,speedMps=0f,timestampMs=5_000L))
            assertEquals(listOf("Você está na Rua Real."),spoken)
        }
}
