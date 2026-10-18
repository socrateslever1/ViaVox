package com.example.viavox.viewmodel

import android.app.Application
import android.content.Context
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

    fun startTracking() {
        ViaVoxTrackingService.start(getApplication())
    }

    fun stopTracking() {
        ViaVoxTrackingService.stop(getApplication())
    }

    fun toggleVoice(enabled: Boolean) {
        prefs.edit().putBoolean("voice_enabled", enabled).apply()
        TrackingRepository.updateVoiceEnabled(enabled)
        if (state.value.isActive) {
            ViaVoxTrackingService.updateConfig(getApplication())
        }
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

    private fun saveAndApplyConfig(config: ViaVoxConfig) {
        config.saveTo(prefs)
        TrackingRepository.updateConfig(config)
        if (state.value.isActive) {
            ViaVoxTrackingService.updateConfig(getApplication())
        }
    }
}
