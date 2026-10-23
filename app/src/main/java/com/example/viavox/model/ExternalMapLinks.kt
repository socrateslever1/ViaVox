package com.example.viavox.model

import java.util.Locale

/** Public HTTPS deep links are understood by installed map apps and browsers. */
object ExternalMapLinks {
    private fun valid(lat: Double?, lon: Double?): Boolean =
        lat != null && lon != null && lat.isFinite() && lon.isFinite() &&
            lat in -90.0..90.0 && lon in -180.0..180.0

    fun googleMaps(lat: Double?, lon: Double?): String {
        if (!valid(lat, lon)) return "https://www.google.com/maps"
        val coordinate = String.format(Locale.US, "%.6f,%.6f", lat, lon)
        return "https://www.google.com/maps/search/?api=1&query=$coordinate"
    }

    fun waze(lat: Double?, lon: Double?): String {
        if (!valid(lat, lon)) return "https://www.waze.com/live-map"
        val coordinate = String.format(Locale.US, "%.6f%%2C%.6f", lat, lon)
        // Do not initiate turn-by-turn navigation without a destination.
        return "https://www.waze.com/ul?ll=$coordinate&navigate=no"
    }
}
