package com.example.viavox.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Single fused GPS stream with adaptive power policy.
 * Navigation/actual movement: high accuracy; stationary: lower-power intervals.
 * LocationManager GPS is kept as a fallback for devices without Google Play Services.
 */
class LocationTrackingManager(
    private val context: Context,
    private val onLocationUpdated: (Location) -> Unit,
    private val onError: (String) -> Unit
) {
    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    private val platformManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val qualityGate = LocationQualityGate()
    private var started = false
    private var highAccuracy = true
    private var platformFallback = false
    private var stationarySinceMs: Long? = null
    private var lastMovementAnchor: Location? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::onNewLocation)
        }
    }

    private val platformListener = LocationListener { location ->
        onNewLocation(location)
    }

    @SuppressLint("MissingPermission")
    fun startTracking() {
        if (started) return
        started = true
        highAccuracy = true
        platformFallback = false
        stationarySinceMs = null
        qualityGate.reset()
        configureFusedLocation(true)

        // Only use a genuinely fresh cached fix; stale cache causes the wrong
        // road to be announced and makes the location marker jump on startup.
        try {
            fusedClient.lastLocation.addOnSuccessListener { cached ->
                if (started && cached != null) onNewLocation(cached)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cached fused fix unavailable", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun configureFusedLocation(high: Boolean) {
        if (!started || platformFallback) return
        highAccuracy = high
        val request = if (high) {
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_500L)
                .setMinUpdateIntervalMillis(900L)
                .setMinUpdateDistanceMeters(2f)
                .setWaitForAccurateLocation(true)
                .build()
        } else {
            LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 12_000L)
                .setMinUpdateIntervalMillis(6_000L)
                .setMinUpdateDistanceMeters(5f)
                .build()
        }
        try {
            fusedClient.removeLocationUpdates(callback)
            fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
                .addOnFailureListener { error ->
                    Log.w(TAG, "Fused location failed; using platform GPS", error)
                    if (started) startPlatformFallback()
                }
        } catch (e: SecurityException) {
            started = false
            onError("Permissão de localização negada")
        } catch (e: Exception) {
            Log.w(TAG, "Fused location not available", e)
            startPlatformFallback()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startPlatformFallback() {
        if (platformFallback || !started) return
        platformFallback = true
        fusedClient.removeLocationUpdates(callback)
        val gpsEnabled = platformManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        val networkEnabled = platformManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        try {
            when {
                gpsEnabled -> platformManager?.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 5_000L, 5f, platformListener, Looper.getMainLooper()
                )
                networkEnabled -> platformManager?.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 10_000L, 10f, platformListener, Looper.getMainLooper()
                )
                else -> onError("Ative a localização do dispositivo")
            }
        } catch (e: SecurityException) {
            onError("Permissão de localização negada")
        } catch (e: Exception) {
            Log.w(TAG, "Platform GPS unavailable", e)
            onError("Não foi possível iniciar o GPS")
        }
    }

    private fun onNewLocation(location: Location) {
        if (!started) return
        val now = System.currentTimeMillis()
        val candidate = LocationSample(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else 100f,
            speedMps = if (location.hasSpeed()) location.speed else 0f,
            timestampMs = location.time
        )
        // Power mode follows accepted low-error samples, not GPS jitter.
        // Even when a stationary fix is deliberately suppressed by the quality gate,
        // its passage of time should still permit a low-power transition.
        val valid = qualityGate.accept(candidate, now)
        val anchor = lastMovementAnchor
        val displacedMeters = if (valid && anchor != null) location.distanceTo(anchor) else 0f
        // Wake high-accuracy GPS for walking speeds (>= 0.9 m/s) or real displacement (>= 12 m)
        // even when a low-power Wi-Fi/cell fix omits speed.
        val moving = valid && (
            (location.hasSpeed() && location.speed >= 0.9f) ||
                displacedMeters >= 12f
            )
        if (moving) {
            stationarySinceMs = null
            lastMovementAnchor = location
            if (!highAccuracy && !platformFallback) configureFusedLocation(true)
        } else if (valid && anchor == null) {
            lastMovementAnchor = location
        } else if (!platformFallback && highAccuracy &&
            candidate.accuracyMeters <= 65f && now - candidate.timestampMs <= 15_000L &&
            (location.hasSpeed().not() || location.speed < 0.9f)
        ) {
            val since = stationarySinceMs ?: now.also { stationarySinceMs = it }
            if (now - since >= 25_000L) configureFusedLocation(false)
        }

        if (valid) onLocationUpdated(location)
    }

    /** Manual scans need a fresh high-accuracy fix even when adaptive tracking
     * is in low-power mode or the stationary quality gate suppresses updates. */
    @SuppressLint("MissingPermission")
    suspend fun freshScanFix(): Location? =
        kotlinx.coroutines.withTimeoutOrNull(4_000L) {
            kotlinx.coroutines.suspendCancellableCoroutine<Location?> { continuation ->
                val token = com.google.android.gms.tasks.CancellationTokenSource()
                continuation.invokeOnCancellation { token.cancel() }
                try {
                    fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token.token)
                        .addOnSuccessListener { fix ->
                            val valid = fix?.takeIf {
                                it.hasAccuracy() && it.accuracy in 0.1f..75f &&
                                    System.currentTimeMillis() - it.time in 0..15_000L &&
                                    it.latitude.isFinite() && it.longitude.isFinite()
                            }
                            if (continuation.isActive) continuation.resumeWith(Result.success(valid))
                        }
                        .addOnFailureListener {
                            if (continuation.isActive) continuation.resumeWith(Result.success(null))
                        }
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resumeWith(Result.success(null))
                }
            }
        }

    fun stopTracking() {
        if (!started) return
        started = false
        fusedClient.removeLocationUpdates(callback)
        try {
            platformManager?.removeUpdates(platformListener)
        } catch (e: Exception) {
            Log.w(TAG, "Could not remove platform GPS listener", e)
        }
        qualityGate.reset()
        stationarySinceMs = null
        lastMovementAnchor = null
        platformFallback = false
    }

    private companion object {
        const val TAG = "ViaVoxLocation"
    }
}
