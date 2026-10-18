package com.example.viavox.service

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Platform-independent GPS quality checks. Never invents/snaps coordinates. */
data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val speedMps: Float,
    val timestampMs: Long
)

class LocationQualityGate {
    private var lastAccepted: LocationSample? = null

    fun reset() { lastAccepted = null }

    /**
     * Reject stale, inaccurate, impossible jumps and stationary GPS wander.
     * One valid stationary fix is always accepted for the first street announcement.
     * Accuracy is a confidence estimate, not a promise of road-level positioning.
     */
    fun accept(sample: LocationSample, nowMs: Long): Boolean {
        if (!sample.latitude.isFinite() || !sample.longitude.isFinite() ||
            sample.latitude !in -90.0..90.0 || sample.longitude !in -180.0..180.0 ||
            !sample.accuracyMeters.isFinite() || sample.accuracyMeters <= 0f ||
            sample.accuracyMeters > 75f || sample.timestampMs <= 0L ||
            nowMs - sample.timestampMs > 15_000L || sample.timestampMs - nowMs > 5_000L
        ) return false

        val previous = lastAccepted
        if (previous != null) {
            if (sample.timestampMs <= previous.timestampMs) return false
            val elapsedSeconds = (sample.timestampMs - previous.timestampMs) / 1000.0
            val distance = calculateDistanceMeters(previous, sample)
            val locationTolerance = max(previous.accuracyMeters, sample.accuracyMeters) * 1.5

            // Impossibly fast displacement is usually a provider switch or multipath jump.
            if (distance > 65.0 * elapsedSeconds + locationTolerance) return false

            // When stopped, don't move the puck to each different noisy GPS fix.
            val stopped = previous.speedMps < 1.2f && sample.speedMps < 1.2f
            val driftLimit = max(4.0, min(previous.accuracyMeters, sample.accuracyMeters).toDouble())
            if (stopped && distance < driftLimit &&
                sample.accuracyMeters >= previous.accuracyMeters * 0.6f
            ) return false
        }
        lastAccepted = sample
        return true
    }

    private fun calculateDistanceMeters(a: LocationSample, b: LocationSample): Double {
        val lat = (a.latitude + b.latitude) / 2.0
        val dy = (b.latitude - a.latitude) * 111_195.0
        val dx = (b.longitude - a.longitude) * 111_195.0 * cos(Math.toRadians(lat))
        return hypot(dx, dy)
    }
}
