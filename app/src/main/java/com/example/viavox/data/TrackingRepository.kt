package com.example.viavox.data

import com.example.viavox.model.Place
import com.example.viavox.model.PointOfInterest
import com.example.viavox.model.RelativeDirection
import com.example.viavox.model.TrackingState
import com.example.viavox.model.ViaVoxConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object TrackingRepository {

    private val _state = MutableStateFlow(TrackingState())
    val state: StateFlow<TrackingState> = _state.asStateFlow()

    fun updateActive(active: Boolean) {
        _state.update { it.copy(isActive = active) }
    }

    fun updateCoordinates(lat: Double, lon: Double, accuracy: Int?, speedKmh: Float, bearing: Float, hasBearing: Boolean) {
        _state.update {
            it.copy(
                hasLocationFix = true,
                latitude = lat,
                longitude = lon,
                accuracyMeters = accuracy,
                speedKmh = speedKmh,
                bearing = bearing,
                hasBearing = hasBearing
            )
        }
    }

    fun updatePlace(place: Place) {
        _state.update { it.copy(place = place) }
    }

    fun updatePoi(poi: PointOfInterest?, direction: RelativeDirection?) {
        _state.update {
            it.copy(currentPoi = poi, poiDirection = direction)
        }
    }

    fun updatePoiStatus(message: String) {
        _state.update { it.copy(poiStatus = message) }
    }

    fun updateStatus(message: String) {
        _state.update { it.copy(statusMessage = message) }
    }

    fun updateVoiceEnabled(enabled: Boolean) {
        _state.update { it.copy(isVoiceEnabled = enabled) }
    }

    fun updateSpeaking(isSpeaking: Boolean) {
        _state.update { it.copy(isSpeaking = isSpeaking) }
    }

    fun updateVoiceIssue(issue: String?) {
        _state.update { it.copy(voiceIssue = issue) }
    }

    fun updatePermission(granted: Boolean) {
        _state.update { it.copy(hasLocationPermission = granted) }
    }

    fun updateConfig(config: ViaVoxConfig) {
        _state.update { it.copy(config = config) }
    }

    fun updateSafetyFeatures(features: List<com.example.viavox.service.SafetyAlertEngine.SafetyFeature>) {
        _state.update { it.copy(safetyFeatures = features) }
    }
}
