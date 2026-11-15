package com.example.viavox.model

/**
 * Speed shown to the driver. GPS Doppler speed is best, but some providers
 * (the network provider and some platform fallbacks) report none, and the app
 * used to show 0 km/h while the car was moving. Then the speed comes from the
 * distance between consecutive fixes.
 */
object GpsSpeed {
    data class Fix(val latitude: Double, val longitude: Double,
                   val timeMs: Long, val accuracyMeters: Float)

    private const val MAX_PLAUSIBLE_MPS = 70.0 // 252 km/h
    /** A reported speed this uncertain is not trusted when a better value exists. */
    private const val MAX_TRUSTED_SPEED_ERROR_MPS = 3.0f

    fun kmh(
        hasSpeed: Boolean, speedMps: Float, speedAccuracyMps: Float?,
        current: Fix, previous: Fix?
    ): Float {
        val reported = speedMps.takeIf {
            hasSpeed && it.isFinite() && it >= 0f && it <= MAX_PLAUSIBLE_MPS
        }
        val trusted = reported != null &&
            (speedAccuracyMps == null || speedAccuracyMps < 0f ||
                speedAccuracyMps <= MAX_TRUSTED_SPEED_ERROR_MPS)
        if (trusted) return reported!! * 3.6f
        val derived = derive(current, previous)
        return when {
            derived != null -> (derived * 3.6).toFloat()
            reported != null -> reported * 3.6f
            else -> 0f
        }
    }

    /** m/s from two fixes, or null when they cannot tell movement from noise. */
    fun derive(current: Fix, previous: Fix?): Double? {
        if (previous == null) return null
        val seconds = (current.timeMs - previous.timeMs) / 1000.0
        if (seconds < 0.8 || seconds > 12.0) return null
        val meters = NavigationMath.meters(
            previous.latitude, previous.longitude, current.latitude, current.longitude)
        // Movement smaller than the position error is indistinguishable from jitter.
        val noise = maxOf(previous.accuracyMeters, current.accuracyMeters).toDouble() * 0.6
        if (meters < noise) return 0.0
        val speed = meters / seconds
        return speed.takeIf { it <= MAX_PLAUSIBLE_MPS }
    }
}
