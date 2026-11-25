package com.example.viavox

import com.example.viavox.data.AppUpdatePolicy
import com.example.viavox.model.Place
import com.example.viavox.service.GeoFix
import com.example.viavox.service.IndexedStreetLine
import com.example.viavox.service.RoadDetectionEngine
import com.example.viavox.service.RoadMapMatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class StreetAccuracyAndUpdateTest {
    private fun northSouth(name:String, lon:Double) = IndexedStreetLine(
        name,1302603,listOf(-3.14 to lon,-3.12 to lon))
    private fun eastWest(name:String, lat:Double) = IndexedStreetLine(
        name,1302603,listOf(lat to -60.03,lat to -60.01))

    @Test fun weakFixNeedsMovingTrailBeforeNamingStreet() {
        val lines=listOf(northSouth("Avenida Principal",-60.0200),
            northSouth("Rua Distante",-60.0190))
        val weak=GeoFix(-3.1300,-60.02002,accuracyMeters=16f,speedMps=8f,
            bearing=0f,hasBearing=true,timestampMs=10_000L)
        assertNull(RoadMapMatcher.choose(weak,lines))
        val trail=listOf(
            GeoFix(-3.13012,-60.02001,accuracyMeters=15f,speedMps=8f,timestampMs=8_500L),
            GeoFix(-3.13024,-60.02003,accuracyMeters=15f,speedMps=8f,timestampMs=7_000L))
        assertEquals("Avenida Principal",
            RoadMapMatcher.choose(weak,lines,trail)?.name)
        assertNull(RoadMapMatcher.choose(weak.copy(accuracyMeters=24f),lines,trail))
    }

    @Test fun trailKeepsDrivenStreetAtSlowCrossingWithoutHeading() {
        val lines=listOf(northSouth("Avenida Principal",-60.0200),
            eastWest("Rua Cruzamento",-3.1300))
        val atCrossing=GeoFix(-3.13000,-60.0200,accuracyMeters=5f,speedMps=1.5f,
            hasBearing=false,timestampMs=10_000L)
        assertNull(RoadMapMatcher.choose(atCrossing,lines))
        val trail=listOf(
            GeoFix(-3.13030,-60.0200,accuracyMeters=5f,timestampMs=7_000L),
            GeoFix(-3.13060,-60.0200,accuracyMeters=5f,timestampMs=4_000L))
        assertEquals("Avenida Principal",
            RoadMapMatcher.choose(atCrossing,lines,trail)?.name)
    }

    @Test fun staleOrJumpingFixesAreNotUsedAsTrail() {
        val now=GeoFix(-3.13,-60.02,accuracyMeters=5f,timestampMs=20_000L)
        val usable=RoadMapMatcher.usableTrail(now,listOf(
            GeoFix(-3.1301,-60.02,accuracyMeters=5f,timestampMs=1_000L),
            GeoFix(-3.1400,-60.02,accuracyMeters=5f,timestampMs=19_000L),
            GeoFix(-3.13001,-60.02,accuracyMeters=5f,timestampMs=18_500L),
            GeoFix(-3.1301,-60.02,accuracyMeters=40f,timestampMs=18_000L),
            GeoFix(-3.1302,-60.02,accuracyMeters=6f,timestampMs=17_000L)))
        assertEquals(listOf(17_000L),usable.map { it.timestampMs })
    }

    @Test fun mappedNamesNearFixDistinguishCoverageFromEmptyMap() {
        val fix=GeoFix(-3.1300,-60.0200,accuracyMeters=8f)
        assertNull(RoadMapMatcher.namesNear(fix,emptyList()))
        assertNull(RoadMapMatcher.namesNear(fix,
            listOf(northSouth("Rua Longe",-60.0100))))
        val names=RoadMapMatcher.namesNear(fix,listOf(
            northSouth("Av. Principal",-60.0200),
            northSouth("Rua dos Fundos",-60.0196)))!!
        assertEquals(setOf(RoadDetectionEngine.normalizeForComparison(
            "Avenida Principal")),names)
    }

    @Test fun geocoderStreetAbsentFromLocalMapIsNeverAnnounced() =
        runTest(UnconfinedTestDispatcher()) {
            val spoken=mutableListOf<String>()
            val engine=RoadDetectionEngine(
                coroutineScope=backgroundScope,
                onPlaceUpdated={},
                onRoadAnnounceNeeded={ message, _ -> spoken.add(message) },
                onStatusMessage={},
                reverseGeocodeProvider={_,_->
                    Result.success(Place(road="Rua do Prédio Vizinho")) }
            )
            engine.localRoadNamesNear={
                setOf(RoadDetectionEngine.normalizeForComparison("Rua Certa")) }
            engine.onGeoFixUpdate(GeoFix(-3.13,-60.02,
                accuracyMeters=30f,timestampMs=1_000L))
            assertNull(engine.getCurrentConfirmedRoad())
            assertTrue(spoken.isEmpty())
        }

    @Test fun geocoderStreetIsAcceptedWhereLocalMapAgreesOrHasNoCoverage() =
        runTest(UnconfinedTestDispatcher()) {
            for (mapped in listOf<Set<String>?>(null,
                setOf(RoadDetectionEngine.normalizeForComparison("R. Certa")))) {
                val engine=RoadDetectionEngine(
                    coroutineScope=backgroundScope,
                    onPlaceUpdated={},
                    onRoadAnnounceNeeded={ _, _ -> },
                    onStatusMessage={},
                    reverseGeocodeProvider={_,_->
                        Result.success(Place(road="Rua Certa")) }
                )
                engine.localRoadNamesNear={ mapped }
                engine.onGeoFixUpdate(GeoFix(-3.13,-60.02,
                    accuracyMeters=30f,timestampMs=1_000L))
                assertEquals("Rua Certa",engine.getCurrentConfirmedRoad())
            }
        }

    @Test fun updateMetadataIsValidatedBeforeBeingOffered() {
        val sha="a".repeat(64)
        val info=AppUpdatePolicy.parse(
            """{"versionCode":1042,"versionName":"0.12.0","sha256":"$sha","size":18000000,"notes":""}""")!!
        assertEquals(1042,info.versionCode)
        assertTrue(AppUpdatePolicy.isNewer(info,41))
        assertFalse(AppUpdatePolicy.isNewer(info,1042))
        assertNull(AppUpdatePolicy.parse("not json"))
        assertNull(AppUpdatePolicy.parse(
            """{"versionCode":1042,"versionName":"0.12.0","sha256":"abc","size":18000000}"""))
        assertNull(AppUpdatePolicy.parse(
            """{"versionCode":0,"versionName":"0.12.0","sha256":"$sha","size":18000000}"""))
        assertNull(AppUpdatePolicy.parse(
            """{"versionCode":7,"versionName":"0.12.0","sha256":"$sha","size":12}"""))
    }

    @Test fun updateChecksAreThrottledUnlessRequested() {
        val hour=60L*60*1000
        assertTrue(AppUpdatePolicy.shouldCheck(10*hour,0L,false))
        val minute = 60L * 1000
        assertFalse(AppUpdatePolicy.shouldCheck(10*hour,10*hour-4*minute,false))
        assertTrue(AppUpdatePolicy.shouldCheck(10*hour,10*hour-4*minute,true))
        assertTrue(AppUpdatePolicy.shouldCheck(10*hour,10*hour-5*minute,false))
        assertTrue(AppUpdatePolicy.shouldCheck(10*hour,11*hour,false))
    }
}
