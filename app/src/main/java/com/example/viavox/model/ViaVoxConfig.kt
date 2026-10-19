package com.example.viavox.model

import android.content.SharedPreferences

data class ViaVoxConfig(
    val speechRate: Float = 0.95f,
    val minIntervalSeconds: Int = 20,
    val narratePoiEnabled: Boolean = true,
    val enableHealthPoi: Boolean = true,
    val enableEducationPoi: Boolean = true,
    val enableParksPoi: Boolean = true,
    val enableFuelPoi: Boolean = true,
    val enablePublicPoi: Boolean = true,
    val franciscaBrokerUrl: String = "",
    val franciscaAccessToken: String = "",
    val voiceProvider: String = "android",
    val nativeVoiceId: String = "",
    val vehicleType: String = "car", // "car" or "motorcycle"
    val enableSafetyAlerts: Boolean = true, // Speed cameras and tolls
    val enablePoliceCircumscription: Boolean = true, // Police districts / DIPs / CICOMs
    val googleMapsApiKey: String = "" // Optional hybrid data enrichment
) {
    /** Configuration presence does not guarantee Azure availability; an audio test does. */
    fun hasFranciscaSettings(): Boolean =
        franciscaBrokerUrl.trim().startsWith("https://", ignoreCase = true) &&
            franciscaAccessToken.isNotBlank()

    fun isMotorcycle(): Boolean = vehicleType.equals("motorcycle", ignoreCase = true)

    fun hasGoogleMapsKey(): Boolean = googleMapsApiKey.trim().isNotBlank()

    fun saveTo(prefs: SharedPreferences) {
        prefs.edit()
            .putFloat("speech_rate", speechRate)
            .putInt("min_interval_seconds", minIntervalSeconds)
            .putBoolean("narrate_poi_enabled", narratePoiEnabled)
            .putBoolean("poi_health", enableHealthPoi)
            .putBoolean("poi_education", enableEducationPoi)
            .putBoolean("poi_parks", enableParksPoi)
            .putBoolean("poi_fuel", enableFuelPoi)
            .putBoolean("poi_public", enablePublicPoi)
            .putString("francisca_broker_url", franciscaBrokerUrl.trim())
            .putString("francisca_access_token", franciscaAccessToken.trim())
            .putString("voice_provider", voiceProvider)
            .putString("native_voice_id", nativeVoiceId)
            .putString("vehicle_type", vehicleType)
            .putBoolean("safety_alerts", enableSafetyAlerts)
            .putBoolean("police_circ", enablePoliceCircumscription)
            .putString("google_maps_key", googleMapsApiKey.trim())
            .apply()
    }

    companion object {
        fun loadFrom(prefs: SharedPreferences): ViaVoxConfig {
            return ViaVoxConfig(
                speechRate = prefs.getFloat("speech_rate", 0.95f),
                minIntervalSeconds = prefs.getInt("min_interval_seconds", 20),
                narratePoiEnabled = prefs.getBoolean("narrate_poi_enabled", true),
                enableHealthPoi = prefs.getBoolean("poi_health", true),
                enableEducationPoi = prefs.getBoolean("poi_education", true),
                enableParksPoi = prefs.getBoolean("poi_parks", true),
                enableFuelPoi = prefs.getBoolean("poi_fuel", true),
                enablePublicPoi = prefs.getBoolean("poi_public", true),
                franciscaBrokerUrl = prefs.getString("francisca_broker_url", "")?.trim().orEmpty(),
                franciscaAccessToken = prefs.getString("francisca_access_token", "")?.trim().orEmpty(),
                voiceProvider = prefs.getString("voice_provider", "android")?.takeIf { it == "francisca" } ?: "android",
                nativeVoiceId = prefs.getString("native_voice_id", "")?.trim().orEmpty(),
                vehicleType = prefs.getString("vehicle_type", "car")?.takeIf { it == "motorcycle" } ?: "car",
                enableSafetyAlerts = prefs.getBoolean("safety_alerts", true),
                enablePoliceCircumscription = prefs.getBoolean("police_circ", true),
                googleMapsApiKey = prefs.getString("google_maps_key", "")?.trim().orEmpty()
            )
        }
    }
}
