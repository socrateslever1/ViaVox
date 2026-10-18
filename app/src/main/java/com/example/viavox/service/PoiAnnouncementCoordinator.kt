package com.example.viavox.service

import com.example.viavox.data.PoiSelectionPolicy
import com.example.viavox.model.PointOfInterest

/**
 * Street changes always win over landmark messages. A POI is queued until
 * the road announcement finishes and the configurable POI cooldown permits it.
 * Uses real positions and rejects old/passed landmarks before playback.
 */
class PoiAnnouncementCoordinator {
    private var pending: PointOfInterest? = null
    private var offeredAtMs = 0L
    private var lastRoadAnnouncementMs = Long.MIN_VALUE
    private var lastPoiAnnouncementMs = Long.MIN_VALUE

    fun reset() {
        pending = null
        offeredAtMs = 0L
        lastRoadAnnouncementMs = Long.MIN_VALUE
        lastPoiAnnouncementMs = Long.MIN_VALUE
    }

    fun roadWasAnnounced(nowMs: Long) {
        lastRoadAnnouncementMs = nowMs
    }

    fun offer(poi: PointOfInterest, nowMs: Long) {
        if (pending?.id != poi.id) {
            pending = poi
            offeredAtMs = nowMs
        }
    }

    fun ready(
        nowMs: Long,
        poiIntervalSeconds: Int,
        ttsBusy: Boolean,
        voiceEnabled: Boolean,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float,
        speedMps: Float,
        bearingDegrees: Float?
    ): PointOfInterest? {
        val poi = pending ?: return null
        if (!voiceEnabled || ttsBusy) return null
        if (nowMs - offeredAtMs > 22_000L) {
            pending = null
            return null
        }
        if (nowMs - offeredAtMs < 1_500L) return null
        if (lastRoadAnnouncementMs != Long.MIN_VALUE &&
            nowMs - lastRoadAnnouncementMs < 5_500L
        ) return null
        if (lastPoiAnnouncementMs != Long.MIN_VALUE &&
            nowMs - lastPoiAnnouncementMs < poiIntervalSeconds.coerceIn(10, 90) * 1000L
        ) return null

        // Current GPS must still place the landmark nearby and not behind us.
        val stillRelevant = PoiSelectionPolicy.choose(
            listOf(poi), latitude, longitude, accuracyMeters, speedMps, bearingDegrees
        ) != null
        if (!stillRelevant) {
            pending = null
            return null
        }
        return poi
    }

    fun poiWasAnnounced(nowMs: Long) {
        lastPoiAnnouncementMs = nowMs
        pending = null
    }

    fun clear() { pending = null }
}
