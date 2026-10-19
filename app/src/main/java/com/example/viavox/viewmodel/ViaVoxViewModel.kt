package com.example.viavox.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import com.example.viavox.data.TrackingRepository
import com.example.viavox.model.PoiCategory
import com.example.viavox.model.TrackingState
import com.example.viavox.model.ViaVoxConfig
import com.example.viavox.service.ViaVoxTrackingService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ViaVoxViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("viavox_prefs", Context.MODE_PRIVATE)

    val state: StateFlow<TrackingState> = TrackingRepository.state

    private val _isSettingsDialogOpen = MutableStateFlow(false)
    val isSettingsDialogOpen: StateFlow<Boolean> = _isSettingsDialogOpen.asStateFlow()

    init {
        val initialConfig = ViaVoxConfig.loadFrom(prefs)
        val initialVoice = prefs.getBoolean("voice_enabled", true)
        TrackingRepository.updateConfig(initialConfig)
        TrackingRepository.updateVoiceEnabled(initialVoice)
    }

    fun onPermissionResult(granted: Boolean) {
        TrackingRepository.updatePermission(granted)
        if (!granted) {
            TrackingRepository.updateActive(false)
            TrackingRepository.updateStatus("Permissão de localização necessária")
        }
    }

    fun isManuallyPaused(): Boolean = prefs.getBoolean("tracking_manually_paused", false)

    fun startTracking() {
        prefs.edit().putBoolean("tracking_manually_paused", false).apply()
        ViaVoxTrackingService.start(getApplication())
    }

    fun onAppVisible() {
        if (!isManuallyPaused() && state.value.hasLocationPermission) {
            ViaVoxTrackingService.start(getApplication())
        }
    }

    fun onAppHidden() {
        // Normal free-mode tracking is screen-only, not a background recorder.
        val app = getApplication<Application>()
        app.stopService(Intent(app, ViaVoxTrackingService::class.java))
        TrackingRepository.updateActive(false)
    }

    fun stopTracking() {
        prefs.edit().putBoolean("tracking_manually_paused", true).apply()
        ViaVoxTrackingService.stop(getApplication())
    }

    fun toggleVoice(enabled: Boolean) {
        prefs.edit().putBoolean("voice_enabled", enabled).apply()
        TrackingRepository.updateVoiceEnabled(enabled)
        if (state.value.isActive) {
            ViaVoxTrackingService.updateConfig(getApplication())
        }
    }

    fun testVoice() {
        ViaVoxTrackingService.testVoice(getApplication())
    }

    fun repeatCurrentLocationSpeech() {
        ViaVoxTrackingService.repeatSpeech(getApplication())
    }

    fun openSettings() {
        _isSettingsDialogOpen.value = true
    }

    fun closeSettings() {
        _isSettingsDialogOpen.value = false
    }

    fun updateVoiceSelection(provider: String, nativeVoiceId: String) {
        saveAndApplyConfig(
            state.value.config.copy(
                voiceProvider = if (provider == "francisca") "francisca" else "android",
                nativeVoiceId = nativeVoiceId
            )
        )
    }

    fun updateSpeechRate(rate: Float) {
        val newConfig = state.value.config.copy(speechRate = rate)
        saveAndApplyConfig(newConfig)
    }

    fun updateInterval(seconds: Int) {
        val newConfig = state.value.config.copy(minIntervalSeconds = seconds)
        saveAndApplyConfig(newConfig)
    }

    fun updateFranciscaBrokerUrl(url: String) {
        val newConfig = state.value.config.copy(franciscaBrokerUrl = url.trim())
        saveAndApplyConfig(newConfig)
    }

    fun updateFranciscaAccessToken(token: String) {
        saveAndApplyConfig(state.value.config.copy(franciscaAccessToken = token.trim()))
    }

    fun togglePoiCategory(category: PoiCategory, enabled: Boolean) {
        val current = state.value.config
        val newConfig = when (category) {
            PoiCategory.HEALTH -> current.copy(enableHealthPoi = enabled)
            PoiCategory.EDUCATION -> current.copy(enableEducationPoi = enabled)
            PoiCategory.PARK -> current.copy(enableParksPoi = enabled)
            PoiCategory.FUEL -> current.copy(enableFuelPoi = enabled)
            PoiCategory.PUBLIC -> current.copy(enablePublicPoi = enabled)
        }
        saveAndApplyConfig(newConfig)
    }

    fun updateVehicleType(type: String) {
        val newConfig = state.value.config.copy(
            vehicleType = if (type.equals("motorcycle", ignoreCase = true)) "motorcycle" else "car"
        )
        saveAndApplyConfig(newConfig)
    }

    fun updateSafetyAlerts(enabled: Boolean) {
        val newConfig = state.value.config.copy(enableSafetyAlerts = enabled)
        saveAndApplyConfig(newConfig)
    }

    fun updatePoliceCircumscription(enabled: Boolean) {
        val newConfig = state.value.config.copy(enablePoliceCircumscription = enabled)
        saveAndApplyConfig(newConfig)
    }

    fun updateGoogleMapsApiKey(key: String) {
        val newConfig = state.value.config.copy(googleMapsApiKey = key.trim())
        saveAndApplyConfig(newConfig)
    }

    private fun saveAndApplyConfig(config: ViaVoxConfig) {
        config.saveTo(prefs)
        TrackingRepository.updateConfig(config)
        if (state.value.isActive) {
            ViaVoxTrackingService.updateConfig(getApplication())
        }
    }
}
