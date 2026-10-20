package com.example.viavox.model

enum class PoiCategory {
    HEALTH,
    EDUCATION,
    PARK,
    FUEL,
    PUBLIC
}

enum class RelativeDirection {
    RIGHT,
    LEFT,
    NEARBY
}

data class PointOfInterest(
    val id: String,
    val name: String,
    val category: PoiCategory,
    val latitude: Double,
    val longitude: Double,
    /** Street from the source's address field, not from nearby guessed geometry. */
    val streetAddress: String = ""
) {
    /**
     * Calculates great-circle distance in meters between this POI and given coordinate.
     */
    fun distanceTo(lat: Double, lon: Double): Double {
        val latRad1 = Math.toRadians(latitude)
        val latRad2 = Math.toRadians(lat)
        val deltaLat = Math.toRadians(lat - latitude)
        val deltaLon = Math.toRadians(lon - longitude)

        val a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
                Math.cos(latRad1) * Math.cos(latRad2) *
                Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        val earthRadius = 6371000.0 // meters
        return earthRadius * c
    }

    /**
     * Determines relative direction (RIGHT, LEFT, NEARBY) given user's location and vehicle bearing.
     */
    fun calculateRelativeDirection(userLat: Double, userLon: Double, vehicleBearing: Float, speedMps: Float): RelativeDirection {
        if (speedMps < 2.0f) {
            // Speed too low (< 7.2 km/h) to reliably determine left/right relative to vehicle heading
            return RelativeDirection.NEARBY
        }

        val dLon = Math.toRadians(longitude - userLon)
        val lat1 = Math.toRadians(userLat)
        val lat2 = Math.toRadians(latitude)

        val y = Math.sin(dLon) * Math.cos(lat2)
        val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon)

        var bearingToPoi = Math.toDegrees(Math.atan2(y, x)).toFloat()
        if (bearingToPoi < 0) {
            bearingToPoi += 360f
        }

        val angleDiff = ((bearingToPoi - vehicleBearing + 360f) % 360f)

        return when {
            angleDiff in 40f..140f -> RelativeDirection.RIGHT
            angleDiff in 220f..320f -> RelativeDirection.LEFT
            else -> RelativeDirection.NEARBY
        }
    }
}
