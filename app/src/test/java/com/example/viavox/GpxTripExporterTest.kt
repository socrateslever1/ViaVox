package com.example.viavox

import com.example.viavox.data.GpxTripExporter
import com.example.viavox.model.SavedTrip
import com.example.viavox.model.TripPoint
import org.junit.Assert.*
import org.junit.Test

class GpxTripExporterTest {
    @Test fun writesRealGpsCoordinatesAndUtcTimestamps() {
        val trip = SavedTrip(
            id = 12L,startedAt = 0L,finishedAt = 5000L,
            distanceMeters = 60.0,
            points = listOf(
                TripPoint(-3.101,-60.012,1_700_000_000_000L,8f),
                TripPoint(-3.102,-60.013,1_700_000_005_000L,7f)
            )
        )
        val result = GpxTripExporter.encode(trip)
        assertTrue(result.contains("version=\"1.1\""))
        assertTrue(result.contains("lat=\"-3.1010000\" lon=\"-60.0120000\""))
        assertTrue(result.contains("lat=\"-3.1020000\" lon=\"-60.0130000\""))
        assertTrue(result.contains("2023-11-14T22:13:20Z"))
        assertEquals(2,Regex("<trkpt ").findAll(result).count())
        assertFalse(result.contains("NaN"))
    }

    @Test fun emptyOrInvalidTripFailsInsteadOfPretendingToHaveBeenRecorded() {
        val empty = SavedTrip(13L,0,0,0.0,emptyList())
        assertThrows(IllegalArgumentException::class.java) {
            GpxTripExporter.encode(empty)
        }
        val invalid = empty.copy(points=listOf(TripPoint(200.0,-60.0,10L,3f)))
        assertThrows(IllegalArgumentException::class.java) {
            GpxTripExporter.encode(invalid)
        }
    }
}
