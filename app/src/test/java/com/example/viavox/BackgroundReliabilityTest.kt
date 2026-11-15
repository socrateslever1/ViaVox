package com.example.viavox

import com.example.viavox.model.GpsSpeed
import com.example.viavox.model.Place
import com.example.viavox.service.BackgroundSessionPolicy
import com.example.viavox.service.GeoFix
import com.example.viavox.service.LocationPowerPolicy
import com.example.viavox.service.RoadDetectionEngine
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BackgroundReliabilityTest {
    // ---- an explicit session survives the system killing the process

    @Test fun explicitSessionIsRestoredUnlessTheUserStoppedIt() {
        assertTrue(BackgroundSessionPolicy.mayRestoreExplicitSession(
            sessionActive = true, manuallyPaused = false))
        assertFalse("pressing Encerrar must never be undone",
            BackgroundSessionPolicy.mayRestoreExplicitSession(
                sessionActive = true, manuallyPaused = true))
        assertFalse("opening the app alone never starts GPS",
            BackgroundSessionPolicy.mayRestoreExplicitSession(
                sessionActive = false, manuallyPaused = false))
    }

    // ---- low power must be left when the trip starts, even with no speed reported

    private fun fix(seconds: Int, metersNorth: Double) =
        GpsSpeed.Fix(-3.1 + metersNorth / 111_195.0, -60.0, seconds * 1000L, 8f)

    @Test fun leavingThePlaceWhereLowPowerBeganBringsFullPrecisionBack() {
        val anchor = fix(0, 0.0)
        assertFalse(LocationPowerPolicy.leftLowPowerPlace(anchor, fix(12, 10.0)))
        assertTrue(LocationPowerPolicy.leftLowPowerPlace(anchor, fix(24, 45.0)))
        assertFalse("no anchor, nothing to leave",
            LocationPowerPolicy.leftLowPowerPlace(null, fix(24, 500.0)))
    }

    @Test fun movementIsJudgedByDerivedSpeedNotByAMissingReading() {
        // The provider reports no speed; 100 m in 5 s is a moving car.
        val derived = GpsSpeed.kmh(false, 0f, null, fix(10, 100.0), fix(5, 0.0)) / 3.6f
        assertTrue(LocationPowerPolicy.isMoving(derived))
        assertFalse(LocationPowerPolicy.isMoving(0.4f))
    }

    // ---- the street veto must not silence the voice when it cannot be sure

    private fun spokenRoadWith(accuracy: Float, mapped: Set<String>?): String? {
        var confirmed: String? = null
        runTest(UnconfinedTestDispatcher()) {
            val engine = RoadDetectionEngine(
                coroutineScope = backgroundScope,
                onPlaceUpdated = {},
                onRoadAnnounceNeeded = { _, _ -> },
                onStatusMessage = {},
                reverseGeocodeProvider = { _, _ -> Result.success(Place(road = "Rua do Geocodificador")) }
            )
            engine.localRoadNamesNear = { mapped }
            engine.onGeoFixUpdate(GeoFix(-3.13, -60.02, accuracyMeters = accuracy, timestampMs = 1_000L))
            confirmed = engine.getCurrentConfirmedRoad()
        }
        return confirmed
    }

    private val otherStreet = setOf(RoadDetectionEngine.normalizeForComparison("Rua Vizinha"))

    @Test fun accurateFixWithAClearlyDifferentLocalStreetIsVetoed() {
        assertNull(spokenRoadWith(8f, otherStreet))
    }

    @Test fun vagueFixCannotContradictTheGeocoder() {
        assertEquals("Rua do Geocodificador", spokenRoadWith(30f, otherStreet))
    }

    @Test fun coveredAreaWithNoStreetNearbyDoesNotSilenceTheGeocoder() {
        assertEquals("Rua do Geocodificador", spokenRoadWith(8f, emptySet()))
    }

    @Test fun noLocalCoverageAtAllTrustsTheGeocoder() {
        assertEquals("Rua do Geocodificador", spokenRoadWith(8f, null))
    }
}
