package com.example.viavox.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import com.example.viavox.data.TrackingRepository
import com.example.viavox.model.PoiCategory
import com.example.viavox.model.TrackingState
import com.example.viavox.model.ViaVoxConfig
import com.example.viavox.model.ScanDirection
import com.example.viavox.model.AssistanceMode
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
        // Opening the UI must not activate location. An explicit session already
        // running in the foreground service remains active independently.
    }

    fun onAppHidden(keepSessionActive: Boolean = false) {
        // Preserve a user-started foreground tracking session when switching apps
        // or locking the phone. Only an explicit Stop ends this session.
        if (keepSessionActive || state.value.isActive) return
    }

    fun toggleBackgroundVoice(enabled: Boolean) {
        saveAndApplyConfig(state.value.config.copy(backgroundVoiceEnabled = enabled))
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

    fun togglePoiNarration(enabled: Boolean) {
        saveAndApplyConfig(state.value.config.copy(narratePoiEnabled = enabled))
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

    fun updateAssistanceMode(mode: String) {
        saveAndApplyConfig(state.value.config.copy(
            assistanceMode = AssistanceMode.from(mode).storedValue
        ))
    }

    fun updateSurroundingsRadius(meters: Int) {
        saveAndApplyConfig(state.value.config.copy(
            surroundingsRadiusMeters = meters.coerceIn(50,500)
        ))
    }

    fun toggleTour(enabled: Boolean) {
        saveAndApplyConfig(state.value.config.copy(tourEnabled = enabled))
    }

    fun scanSurroundings(direction: ScanDirection) {
        val snapshot = state.value
        val label = if (direction == ScanDirection.AROUND) "360°" else "À minha frente"
        val issue = when {
            !snapshot.isActive -> "GPS desligado. Toque em Ativar localização."
            !snapshot.hasLocationFix -> "Aguardando o primeiro sinal GPS. Tente novamente."
            (snapshot.accuracyMeters ?: 100) > 55 ->
                "Precisão GPS insuficiente (±${snapshot.accuracyMeters ?: 100} m). Aguarde melhorar."
            else -> null
        }
        if (issue != null) {
            TrackingRepository.updateSurroundingsResult(issue)
            return
        }
        // Immediate visual acknowledgement: never let a touch look inert.
        TrackingRepository.updateSurroundingsResult(
            "$label: iniciando busca em até ${snapshot.config.surroundingsRadiusMeters} m..."
        )
        try {
            ViaVoxTrackingService.scan(getApplication(), direction)
        } catch (error: Exception) {
            TrackingRepository.updateSurroundingsResult(
                "$label: não foi possível enviar a solicitação ao serviço GPS. " +
                    "Encerre e reative a localização."
            )
            android.util.Log.e("ViaVoxScan", "Failed to deliver manual scan", error)
        }
    }

    fun updateVehicleType(type: String) {
        val newConfig = state.value.config.copy(
            vehicleType = com.example.viavox.model.VehicleMode.from(type).storedValue
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
