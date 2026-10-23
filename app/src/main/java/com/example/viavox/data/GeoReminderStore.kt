package com.example.viavox.data

import android.content.Context
import com.example.viavox.model.GeoReminder
import com.example.viavox.model.GeoReminderPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Stores reminders on-device; no account, server or location history upload. */
class GeoReminderStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "viavox_location_reminders_v1", Context.MODE_PRIVATE
    )

    @Synchronized
    fun all(): List<GeoReminder> {
        val result = mutableListOf<GeoReminder>()
        val array = try { JSONArray(preferences.getString("items", "[]")) }
            catch (_: Exception) { JSONArray() }
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
            val title = obj.optString("title").trim().takeIf { it.isNotBlank() } ?: continue
            val lat = obj.optDouble("lat", Double.NaN)
            val lon = obj.optDouble("lon", Double.NaN)
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 ||
                lon !in -180.0..180.0) continue
            result += GeoReminder(
                id, title.take(120), lat, lon,
                obj.optInt("radius", 100).coerceIn(75, 500),
                obj.optBoolean("armed", false),
                obj.optBoolean("completed", false)
            )
        }
        return result
    }

    @Synchronized
    fun add(title: String, latitude: Double, longitude: Double, radius: Int): GeoReminder? {
        val clean = title.trim().replace(Regex("\\s+"), " ").take(120)
        if (clean.isBlank() || !latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        val reminder = GeoReminder(UUID.randomUUID().toString(), clean, latitude, longitude,
            radius.coerceIn(75,500))
        persist(all().filterNot { it.completed } + reminder)
        return reminder
    }

    @Synchronized
    fun remove(id: String) {
        persist(all().filterNot { it.id == id })
    }

    @Synchronized
    fun onFix(lat: Double, lon: Double, accuracy: Float): List<GeoReminder> {
        val before = all()
        val result = GeoReminderPolicy.evaluate(before, lat, lon, accuracy)
        if (result.items != before) persist(result.items)
        return result.triggered
    }

    @Synchronized
    private fun persist(reminders: List<GeoReminder>) {
        val array = JSONArray()
        reminders.takeLast(80).forEach {
            array.put(JSONObject().apply {
                put("id", it.id)
                put("title", it.title)
                put("lat", it.latitude)
                put("lon", it.longitude)
                put("radius", it.radiusMeters)
                put("armed", it.armed)
                put("completed", it.completed)
            })
        }
        preferences.edit().putString("items", array.toString()).apply()
    }
}
