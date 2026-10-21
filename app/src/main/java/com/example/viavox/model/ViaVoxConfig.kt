package com.example.viavox.model

import android.content.SharedPreferences

data class ViaVoxConfig(
    val speechRate: Float = 0.95f,
    val minIntervalSeconds: Int = 20,
    val narratePoiEnabled: Boolean = true,
    val backgroundVoiceEnabled: Boolean = false,
    val enableHealthPoi: Boolean = true,
    val enableEducationPoi: Boolean = true,
    val enableParksPoi: Boolean = true,
    val enableFuelPoi: Boolean = true,
    val enablePublicPoi: Boolean = true,
    val nativeVoiceId: String = "",
    val vehicleType: String = "car", // "car", "motorcycle" or "walking"
    val enableSafetyAlerts: Boolean = true, // Speed cameras and tolls
    val enablePoliceCircumscription: Boolean = false // Optional CICOM narration
) {
    fun isMotorcycle(): Boolean = VehicleMode.from(vehicleType) == VehicleMode.MOTORCYCLE
    fun isWalking(): Boolean = VehicleMode.from(vehicleType) == VehicleMode.WALKING

    fun saveTo(prefs: SharedPreferences) {
        prefs.edit()
            .putFloat("speech_rate", speechRate)
            .putInt("min_interval_seconds", minIntervalSeconds)
            .putBoolean("narrate_poi_enabled", narratePoiEnabled)
            .putBoolean("background_voice_enabled", backgroundVoiceEnabled)
            .putBoolean("poi_health", enableHealthPoi)
            .putBoolean("poi_education", enableEducationPoi)
            .putBoolean("poi_parks", enableParksPoi)
            .putBoolean("poi_fuel", enableFuelPoi)
            .putBoolean("poi_public", enablePublicPoi)
            .putString("native_voice_id", nativeVoiceId)
            .putString("vehicle_type", vehicleType)
            .putBoolean("safety_alerts", enableSafetyAlerts)
            .putBoolean("police_circ", enablePoliceCircumscription)
            .apply()
    }

    companion object {
        fun loadFrom(prefs: SharedPreferences): ViaVoxConfig {
            return ViaVoxConfig(
                speechRate = prefs.getFloat("speech_rate", 0.95f),
                minIntervalSeconds = prefs.getInt("min_interval_seconds", 20),
                narratePoiEnabled = prefs.getBoolean("narrate_poi_enabled", true),
                backgroundVoiceEnabled = prefs.getBoolean("background_voice_enabled", false),
                enableHealthPoi = prefs.getBoolean("poi_health", true),
                enableEducationPoi = prefs.getBoolean("poi_education", true),
                enableParksPoi = prefs.getBoolean("poi_parks", true),
                enableFuelPoi = prefs.getBoolean("poi_fuel", true),
                enablePublicPoi = prefs.getBoolean("poi_public", true),
                nativeVoiceId = prefs.getString("native_voice_id", "")?.trim().orEmpty(),
                vehicleType = VehicleMode.from(prefs.getString("vehicle_type", "car")).storedValue,
                enableSafetyAlerts = prefs.getBoolean("safety_alerts", true),
                enablePoliceCircumscription = prefs.getBoolean("police_circ", false)
            )
        }
    }
}
