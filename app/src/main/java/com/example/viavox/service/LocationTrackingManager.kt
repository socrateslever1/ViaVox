package com.example.viavox.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log

class LocationTrackingManager(
    private val context: Context,
    private val onLocationUpdated: (Location) -> Unit,
    private val onError: (String) -> Unit
) {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private var isTracking = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onLocationUpdated(location)
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

        override fun onProviderEnabled(provider: String) {}

        override fun onProviderDisabled(provider: String) {
            onError("GPS desativado no dispositivo")
        }
    }

    @SuppressLint("MissingPermission")
    fun startTracking() {
        if (locationManager == null) {
            onError("Serviço de localização indisponível")
            return
        }

        if (isTracking) return

        try {
            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            if (!isGpsEnabled && !isNetworkEnabled) {
                onError("Por favor, ative os serviços de localização (GPS)")
                return
            }

            // High frequency updates for driving (1.5 seconds, 2 meters)
            if (isGpsEnabled) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1500L,
                    2f,
                    locationListener,
                    Looper.getMainLooper()
                )
            }

            if (isNetworkEnabled) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    2000L,
                    3f,
                    locationListener,
                    Looper.getMainLooper()
                )
            }

            val lastKnown = (if (isGpsEnabled) locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) else null)
                ?: (if (isNetworkEnabled) locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) else null)

            lastKnown?.let {
                onLocationUpdated(it)
            }

            isTracking = true
            Log.d("LocationTrackingManager", "GPS tracking started successfully")
        } catch (e: SecurityException) {
            Log.e("LocationTrackingManager", "Missing location permissions", e)
            onError("Permissão de localização negada")
        } catch (e: Exception) {
            Log.e("LocationTrackingManager", "Error starting location updates", e)
            onError("Erro ao iniciar localização: ${e.message}")
        }
    }

    fun stopTracking() {
        if (!isTracking) return
        try {
            locationManager?.removeUpdates(locationListener)
            Log.d("LocationTrackingManager", "GPS tracking stopped")
        } catch (e: Exception) {
            Log.w("LocationTrackingManager", "Error stopping updates", e)
        } finally {
            isTracking = false
        }
    }
}
