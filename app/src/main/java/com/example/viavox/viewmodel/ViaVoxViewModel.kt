package com.example.viavox.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.viavox.data.NominatimService
import com.example.viavox.model.Place
import com.example.viavox.model.TrackingState
import com.example.viavox.service.LocationTrackingManager
import com.example.viavox.service.TextToSpeechManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.hypot

class ViaVoxViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("viavox_prefs", Context.MODE_PRIVATE)
    private val nominatimService = NominatimService()

    private val ttsManager = TextToSpeechManager(application) { isSpeaking ->
        _state.update { it.copy(isSpeaking = isSpeaking) }
    }

    private val _state = MutableStateFlow(
        TrackingState(
            isVoiceEnabled = prefs.getBoolean("voice_enabled", true)
        )
    )
    val state: StateFlow<TrackingState> = _state.asStateFlow()

    private val locationTracker = LocationTrackingManager(
        context = application,
        onLocationUpdated = { lat, lon, acc ->
            onLocationReceived(lat, lon, acc)
        },
        onError = { errorMsg ->
            _state.update { it.copy(statusMessage = errorMsg) }
        }
    )

    // Business logic state mirrors original web applet
    private var lastLookupTime = 0L
    private var lastLookupPoint: Pair<Double, Double>? = null
    private var lastSpokenRoad = ""
    private var pendingRoad = ""
    private var pendingCount = 0
    private var lastSpeechTime = 0L
    private var isLookupBusy = false
    private var sessionCounter = 0

    fun onPermissionResult(granted: Boolean) {
        _state.update { it.copy(hasLocationPermission = granted) }
        if (granted && _state.value.isActive) {
            locationTracker.startTracking()
        } else if (!granted) {
            _state.update {
                it.copy(
                    isActive = false,
                    statusMessage = "Permissão de localização negada"
                )
            }
        }
    }

    fun startTracking() {
        sessionCounter++
        lastSpokenRoad = ""
        pendingRoad = ""
        pendingCount = 0
        lastLookupTime = 0L
        lastLookupPoint = null

        _state.update {
            it.copy(
                isActive = true,
                statusMessage = "Aguardando sinal de GPS..."
            )
        }

        locationTracker.startTracking()
    }

    fun stopTracking() {
        sessionCounter++
        locationTracker.stopTracking()
        ttsManager.stop()
        _state.update {
            it.copy(
                isActive = false,
                statusMessage = "Localização pausada"
            )
        }
    }

    fun toggleVoice(enabled: Boolean) {
        prefs.edit().putBoolean("voice_enabled", enabled).apply()
        _state.update { it.copy(isVoiceEnabled = enabled) }
        if (!enabled) {
            ttsManager.stop()
        }
    }

    fun repeatCurrentLocationSpeech() {
        val currentPlace = _state.value.place ?: return
        speak(currentPlace.toSpeechMessage())
    }

    private fun speak(message: String) {
        if (!_state.value.isVoiceEnabled) return
        ttsManager.speak(message)
        lastSpeechTime = System.currentTimeMillis()
    }

    fun onLocationReceived(lat: Double, lon: Double, accuracy: Float) {
        val roundedAcc = accuracy.toInt()
        _state.update {
            it.copy(
                latitude = lat,
                longitude = lon,
                accuracyMeters = roundedAcc,
                isActive = true
            )
        }

        if (roundedAcc > 80) {
            _state.update {
                it.copy(statusMessage = "Sinal GPS impreciso (±$roundedAcc m)")
            }
            return
        }

        val now = System.currentTimeMillis()
        val prior = lastLookupPoint
        val metersMoved = if (prior != null) {
            val dLat = (lat - prior.first) * 111000.0
            val dLon = (lon - prior.second) * 111000.0 * cos(Math.toRadians(lat))
            hypot(dLat, dLon)
        } else {
            Double.POSITIVE_INFINITY
        }

        // Throttle lookup: at least 11 seconds AND (moved >= 18m OR 60 seconds passed)
        if (now - lastLookupTime >= 11000L && (metersMoved >= 18.0 || now - lastLookupTime >= 60000L)) {
            lastLookupTime = now
            lastLookupPoint = Pair(lat, lon)
            lookupAddress(lat, lon)
        }
    }

    private fun lookupAddress(lat: Double, lon: Double) {
        if (isLookupBusy) return
        val currentSession = sessionCounter
        isLookupBusy = true

        viewModelScope.launch {
            try {
                val result = nominatimService.reverseGeocode(lat, lon)
                if (currentSession != sessionCounter) return@launch

                result.onSuccess { place ->
                    _state.update {
                        it.copy(
                            place = place,
                            statusMessage = "Localização atualizada"
                        )
                    }

                    if (place.road == "Via não identificada") return@onSuccess

                    if (lastSpokenRoad == place.road) {
                        pendingRoad = ""
                        pendingCount = 0
                        return@onSuccess
                    }

                    if (pendingRoad == place.road) {
                        pendingCount++
                    } else {
                        pendingRoad = place.road
                        pendingCount = 1
                    }

                    val now = System.currentTimeMillis()
                    // Confirmation requirement: count >= 2 and at least 12 seconds since last speech
                    if (pendingCount >= 2 && (now - lastSpeechTime > 12000L)) {
                        lastSpokenRoad = place.road
                        pendingRoad = ""
                        pendingCount = 0
                        speak(place.toSpeechMessage())
                    }
                }.onFailure { err ->
                    _state.update {
                        it.copy(
                            statusMessage = err.message ?: "Erro ao identificar endereço"
                        )
                    }
                }
            } finally {
                isLookupBusy = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        locationTracker.stopTracking()
        ttsManager.shutdown()
    }
}
