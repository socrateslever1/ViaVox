package com.example.viavox

import com.example.viavox.model.PoiCategory
import com.example.viavox.model.PointOfInterest
import com.example.viavox.service.PoiAnnouncementCoordinator
import org.junit.Assert.*
import org.junit.Test

class PoiAnnouncementCoordinatorTest {
    private val road = "Rua Benjamin Constant"
    private val poi = PointOfInterest("osm_node_321","Hospital Público",
        PoiCategory.HEALTH,0.00018,0.0,streetAddress="R. Benjamin Constant, 10")
    private fun pending(coordinator: PoiAnnouncementCoordinator,time: Long,busy: Boolean = false) =
        coordinator.ready(
            nowMs = time, poiIntervalSeconds = 20, ttsBusy = busy,
            voiceEnabled = true, latitude = 0.0, longitude = 0.0,
            accuracyMeters = 6f, speedMps = 5f, bearingDegrees = 0f,
            currentRoad = road
        )

    @Test fun streetFirstThenNearbyReferenceAfterAudioQuietTime() {
        val gate = PoiAnnouncementCoordinator()
        gate.roadWasAnnounced(10_000L)
        gate.offer(poi,11_000L)
        assertNull(pending(gate,12_000L))
        assertNull(pending(gate,14_000L))
        assertNull(pending(gate,17_000L,busy=true))
        assertEquals(poi,pending(gate,17_000L))
        gate.poiWasAnnounced(17_000L)
        gate.offer(poi,18_000L)
        assertNull(pending(gate,25_000L))
        assertEquals(poi,pending(gate,38_000L))
    }

    @Test fun dropsQueuedReferenceWhenStreetChangesOrPassingIt() {
        val gate = PoiAnnouncementCoordinator()
        gate.offer(poi,10_000L)
        assertNull(gate.ready(12_000L,20,false,true,
            0.0,0.0,5f,8f,0f,"Rua Aristides Rocha"))
        gate.offer(poi,13_000L)
        assertNull(gate.ready(15_000L,20,false,true,
            0.0008,0.0,5f,8f,0f,road))
    }

    @Test fun voiceOffAndResetAreRespected() {
        val gate=PoiAnnouncementCoordinator()
        gate.offer(poi,1000L)
        assertNull(gate.ready(3000L,20,false,false,0.0,0.0,6f,5f,0f,road))
        gate.reset()
        assertNull(pending(gate,5000L))
    }
}
