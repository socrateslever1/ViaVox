package com.example.viavox.service

/** GPS bearing is true-north relative; Android sensor azimuth is magnetic-north relative. */
object HeadingCorrectionPolicy {
    fun trueNorth(magneticDegrees: Float, declinationDegrees: Float): Float? {
        if (!magneticDegrees.isFinite() || !declinationDegrees.isFinite() ||
            declinationDegrees !in -90f..90f) return null
        return ((magneticDegrees + declinationDegrees) % 360f + 360f) % 360f
    }
}
