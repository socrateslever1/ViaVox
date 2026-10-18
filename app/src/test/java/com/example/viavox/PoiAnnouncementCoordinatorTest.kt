package com.example.viavox

import com.example.viavox.model.PoiCategory
import com.example.viavox.model.PointOfInterest
import com.example.viavox.service.PoiAnnouncementCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PoiAnnouncementCoordinatorTest {
    private val poi = PointOfInterest("osm_way_321","Posto Amazonas",PoiCategory.FUEL,0.0004,0.0)
    private fun pending(coordinator: PoiAnnouncementCoordinator, time: Long, busy: Boolean = false) =
        coordinator.ready(
            nowMs = time, poiIntervalSeconds = 20, ttsBusy = busy,
            voiceEnabled = true, latitude = 0.0, longitude = 0.0,
            accuracyMeters = 6f, speedMps = 5f, bearingDegrees = 0f
        )

    @Test
    fun streetAnnouncedFirstThenLandmarkFollowsAfterAudioQuietTime() {
        val gate = PoiAnnouncementCoordinator()
        gate.roadWasAnnounced(10_000L)
        gate.offer(poi, 11_000L)
        assertNull(pending(gate, 12_000L))
        assertNull(pending(gate, 14_000L))
        assertNull(pending(gate, 17_000L, busy = true))
        assertEquals(poi, pending(gate, 17_000L))
        gate.poiWasAnnounced(17_000L)
        gate.offer(poi, 18_000L)
        assertNull(pending(gate, 25_000L))
        assertEquals(poi, pending(gate, 38_000L))
    }

    @Test
    fun ignoresLandmarkBehindOrOutsideGpsQuality() {
        val gate = PoiAnnouncementCoordinator()
        gate.offer(poi,10_000L)
        val wrongDirection = gate.ready(12_000L,20,false,true,0.0008,0.0,5f,8f,0f)
        assertNull(wrongDirection)
        gate.offer(poi,13_000L)
        assertNull(gate.ready(15_000L,20,false,true,0.0,0.0,65f,8f,0f))
    }

    @Test
    fun resetAndVoiceOffAreRespected() {
        val gate = PoiAnnouncementCoordinator()
        gate.offer(poi, 1_000L)
        assertNull(gate.ready(3_000L,20,false,false,0.0,0.0,6f,5f,0f))
        gate.reset()
        assertNull(pending(gate, 5_000L))
    }
}
