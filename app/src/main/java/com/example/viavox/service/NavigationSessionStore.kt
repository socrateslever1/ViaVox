package com.example.viavox.service

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.example.viavox.model.MapPlace
import com.example.viavox.model.NavigationRoute
import com.example.viavox.model.RouteStep
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * An atomic private file persists a user's active route without Binder's 1MB
 * intent size limit. It does not change or migrate existing saved routes/trips.
 */
class NavigationSessionStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir,"viavox_active_navigation.json"))
    private val lock = globalFileLock

    private companion object {
        val globalFileLock = Any()
    }

    fun write(route: NavigationRoute) = synchronized(lock) {
        require(route.coordinates.size in 2..25_000) { "Rota sem geometria válida" }
        val json = JSONObject().apply {
            put("destination",JSONObject().apply {
                put("name",route.destination.name)
                put("lat",route.destination.latitude)
                put("lon",route.destination.longitude)
                put("subtitle",route.destination.subtitle)
            })
            put("distance",route.distanceMeters)
            put("duration",route.durationSeconds)
            put("coordinates",JSONArray().apply {
                route.coordinates.forEach { (lat,lon) ->
                    put(JSONArray().put(lat).put(lon))
                }
            })
            put("steps",JSONArray().apply {
                route.steps.forEach { step ->
                    put(JSONObject().apply {
                        put("lat",step.latitude)
                        put("lon",step.longitude)
                        put("instruction",step.instruction)
                        put("distance",step.distanceMeters)
                    })
                }
            })
        }.toString()
        val output = file.startWrite()
        try {
            output.write(json.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }

    fun read(): NavigationRoute? = synchronized(lock) {
        try {
            val json = JSONObject(file.openRead().use {
                it.bufferedReader(Charsets.UTF_8).readText()
            })
            val dest = json.getJSONObject("destination")
            val coordinates = json.getJSONArray("coordinates").let { items ->
                (0 until items.length()).map { i ->
                    val xy = items.getJSONArray(i)
                    xy.getDouble(0) to xy.getDouble(1)
                }
            }
            val steps = json.getJSONArray("steps").let { items ->
                (0 until items.length()).map { i ->
                    val entry = items.getJSONObject(i)
                    RouteStep(
                        entry.getDouble("lat"),entry.getDouble("lon"),
                        entry.getString("instruction"),entry.getDouble("distance")
                    )
                }
            }
            if (coordinates.size < 2) return@synchronized null
            NavigationRoute(
                destination = MapPlace(
                    dest.getString("name"),dest.getDouble("lat"),dest.getDouble("lon"),
                    dest.optString("subtitle")
                ),
                coordinates = coordinates,
                distanceMeters = json.getDouble("distance"),
                durationSeconds = json.getDouble("duration"),
                steps = steps
            )
        } catch (_: java.io.FileNotFoundException) {
            null
        } catch (e: Exception) {
            Log.w("ViaVoxNav","Could not recover active route",e)
            null
        }
    }

    fun clear() = synchronized(lock) {
        file.delete()
    }
}
