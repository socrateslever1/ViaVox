package com.example.viavox.model

/**
 * State representing current GPS tracking, address information, and speech narration.
 */
data class TrackingState(
    val isActive: Boolean = false,
    val latitude: Double = -3.119,
    val longitude: Double = -60.022,
    val accuracyMeters: Int? = null,
    val place: Place? = null,
    val statusMessage: String = "Pronto para localizar",
    val isVoiceEnabled: Boolean = true,
    val isSpeaking: Boolean = false,
    val hasLocationPermission: Boolean = false
)
