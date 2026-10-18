package com.example.viavox.model

import android.content.SharedPreferences

data class ViaVoxConfig(
    val speechRate: Float = 0.95f,
    val minIntervalSeconds: Int = 20,
    val enableHealthPoi: Boolean = true,
    val enableEducationPoi: Boolean = true,
    val enableParksPoi: Boolean = true,
    val enableFuelPoi: Boolean = true,
    val enablePublicPoi: Boolean = true,
    val franciscaBrokerUrl: String = "",
    val franciscaAccessToken: String = "",
    val voiceProvider: String = "android",
    val nativeVoiceId: String = ""
) {
    /** Configuration presence does not guarantee Azure availability; an audio test does. */
    fun hasFranciscaSettings(): Boolean =
        franciscaBrokerUrl.trim().startsWith("https://", ignoreCase = true) &&
            franciscaAccessToken.isNotBlank()

    fun saveTo(prefs: SharedPreferences) {
        prefs.edit()
            .putFloat("speech_rate", speechRate)
            .putInt("min_interval_seconds", minIntervalSeconds)
            .putBoolean("poi_health", enableHealthPoi)
            .putBoolean("poi_education", enableEducationPoi)
            .putBoolean("poi_parks", enableParksPoi)
            .putBoolean("poi_fuel", enableFuelPoi)
            .putBoolean("poi_public", enablePublicPoi)
            .putString("francisca_broker_url", franciscaBrokerUrl.trim())
            .putString("francisca_access_token", franciscaAccessToken.trim())
            .putString("voice_provider", voiceProvider)
            .putString("native_voice_id", nativeVoiceId)
            .apply()
    }

    companion object {
        fun loadFrom(prefs: SharedPreferences): ViaVoxConfig {
            return ViaVoxConfig(
                speechRate = prefs.getFloat("speech_rate", 0.95f),
                minIntervalSeconds = prefs.getInt("min_interval_seconds", 20),
                enableHealthPoi = prefs.getBoolean("poi_health", true),
                enableEducationPoi = prefs.getBoolean("poi_education", true),
                enableParksPoi = prefs.getBoolean("poi_parks", true),
                enableFuelPoi = prefs.getBoolean("poi_fuel", true),
                enablePublicPoi = prefs.getBoolean("poi_public", true),
                franciscaBrokerUrl = prefs.getString("francisca_broker_url", "")?.trim().orEmpty(),
                franciscaAccessToken = prefs.getString("francisca_access_token", "")?.trim().orEmpty(),
                voiceProvider = prefs.getString("voice_provider", "android")?.takeIf { it == "francisca" } ?: "android",
                nativeVoiceId = prefs.getString("native_voice_id", "")?.trim().orEmpty()
            )
        }
    }
}
