package com.example.viavox.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.viavox.model.MapPlace
import com.example.viavox.model.NavigationMath
import com.example.viavox.model.NavigationRoute
import com.example.viavox.model.RouteStep
import com.example.viavox.model.SavedTrip
import com.example.viavox.model.StoredRoute
import com.example.viavox.model.TripPoint
import org.json.JSONArray
import org.json.JSONObject

/**
 * Private SQLite database, no external account and no cloud upload.
 * Tracks are recorded only after explicitly pressing "Gravar percurso".
 */
class ViaVoxHistoryDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "viavox_history.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE favorites (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, subtitle TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, created_at INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE saved_routes (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, payload TEXT NOT NULL, created_at INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE trips (id INTEGER PRIMARY KEY AUTOINCREMENT, started_at INTEGER NOT NULL, finished_at INTEGER NOT NULL DEFAULT 0, distance_m REAL NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE trip_points (id INTEGER PRIMARY KEY AUTOINCREMENT, trip_id INTEGER NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, recorded_at INTEGER NOT NULL, accuracy REAL NOT NULL)"
        )
        db.execSQL("CREATE INDEX ix_trip_points_trip ON trip_points(trip_id,recorded_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future upgrades must migrate data without dropping user routes/trips.
    }

    fun addFavorite(point: MapPlace): Long {
        val values = ContentValues().apply {
            put("name", point.name)
            put("subtitle", point.subtitle)
            put("lat", point.latitude)
            put("lon", point.longitude)
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insertOrThrow("favorites", null, values)
    }

    fun getFavorites(): List<Pair<Long, MapPlace>> {
        val out = mutableListOf<Pair<Long, MapPlace>>()
        readableDatabase.rawQuery(
            "SELECT id,name,lat,lon,subtitle FROM favorites ORDER BY created_at DESC LIMIT 250", null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out.add(cursor.getLong(0) to MapPlace(cursor.getString(1), cursor.getDouble(2),
                    cursor.getDouble(3), cursor.getString(4)))
            }
        }
        return out
    }

    fun deleteFavorite(id: Long) = writableDatabase.delete("favorites", "id=?", arrayOf(id.toString()))

    fun saveRoute(route: NavigationRoute): Long {
        val data = JSONObject().apply {
            put("destName", route.destination.name)
            put("destLat", route.destination.latitude)
            put("destLon", route.destination.longitude)
            put("destDetail", route.destination.subtitle)
            put("distance", route.distanceMeters)
            put("duration", route.durationSeconds)
            put("points", JSONArray().apply {
                route.coordinates.forEach { (lat,lon) ->
                    put(JSONArray().put(lat).put(lon))
                }
            })
            put("steps", JSONArray().apply {
                route.steps.forEach { step ->
                    put(JSONObject().apply {
                        put("lat",step.latitude)
                        put("lon",step.longitude)
                        put("instruction",step.instruction)
                        put("distance",step.distanceMeters)
                    })
                }
            })
        }
        return writableDatabase.insertOrThrow("saved_routes",null,ContentValues().apply {
            put("title",route.destination.name)
            put("payload",data.toString())
            put("created_at",System.currentTimeMillis())
        })
    }

    fun getRoutes(): List<StoredRoute> {
        val routes = mutableListOf<StoredRoute>()
        readableDatabase.rawQuery(
            "SELECT id,title,payload,created_at FROM saved_routes ORDER BY created_at DESC LIMIT 120",null
        ).use { cursor ->
            while(cursor.moveToNext()) {
                try {
                    val json = JSONObject(cursor.getString(2))
                    val pointsJson = json.getJSONArray("points")
                    val points = (0 until pointsJson.length()).map { i ->
                        val pair = pointsJson.getJSONArray(i)
                        pair.getDouble(0) to pair.getDouble(1)
                    }
                    val stepArray = json.getJSONArray("steps")
                    val steps = (0 until stepArray.length()).map { i ->
                        val step = stepArray.getJSONObject(i)
                        RouteStep(step.getDouble("lat"),step.getDouble("lon"),
                            step.getString("instruction"),step.getDouble("distance"))
                    }
                    routes += StoredRoute(cursor.getLong(0),cursor.getString(1),cursor.getLong(3),
                        NavigationRoute(
                            MapPlace(json.getString("destName"),json.getDouble("destLat"),
                                json.getDouble("destLon"),json.optString("destDetail")),
                            points,json.getDouble("distance"),json.getDouble("duration"),steps
                        )
                    )
                } catch (_: Exception) {
                    // Ignore corrupt legacy item, keep other saved routes visible.
                }
            }
        }
        return routes
    }

    fun deleteRoute(id: Long) = writableDatabase.delete("saved_routes","id=?",arrayOf(id.toString()))

    fun startTrip(): Long = writableDatabase.insertOrThrow("trips",null,ContentValues().apply {
        put("started_at",System.currentTimeMillis())
        put("finished_at",0L)
        put("distance_m",0.0)
    })

    fun appendTripPoint(tripId: Long, point: TripPoint, cumulativeMeters: Double) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.insertOrThrow("trip_points",null,ContentValues().apply {
                put("trip_id",tripId)
                put("lat",point.latitude)
                put("lon",point.longitude)
                put("recorded_at",point.recordedAt)
                put("accuracy",point.accuracy)
            })
            writableDatabase.update("trips", ContentValues().apply {
                put("distance_m",cumulativeMeters)
            },"id=?",arrayOf(tripId.toString()))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    fun finishTrip(id: Long) {
        writableDatabase.update("trips",ContentValues().apply {
            put("finished_at",System.currentTimeMillis())
        },"id=?",arrayOf(id.toString()))
    }

    /** Last-active sessions are recovered without losing already stored GPS points. */
    fun recoverUnfinishedTrips() {
        writableDatabase.execSQL("UPDATE trips SET finished_at = ? WHERE finished_at = 0",
            arrayOf(System.currentTimeMillis()))
    }

    fun getTrips(): List<SavedTrip> {
        val trips = mutableListOf<SavedTrip>()
        readableDatabase.rawQuery(
            "SELECT id,started_at,finished_at,distance_m FROM trips ORDER BY started_at DESC LIMIT 100",null
        ).use { cursor ->
            while(cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val points = mutableListOf<TripPoint>()
                readableDatabase.rawQuery(
                    "SELECT lat,lon,recorded_at,accuracy FROM trip_points WHERE trip_id=? ORDER BY recorded_at",
                    arrayOf(id.toString())
                ).use { positions ->
                    while(positions.moveToNext()) {
                        points += TripPoint(positions.getDouble(0),positions.getDouble(1),
                            positions.getLong(2),positions.getFloat(3))
                    }
                }
                trips += SavedTrip(id,cursor.getLong(1),cursor.getLong(2),cursor.getDouble(3),points)
            }
        }
        return trips
    }

    fun deleteTrip(id: Long) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("trip_points","trip_id=?", arrayOf(id.toString()))
            writableDatabase.delete("trips","id=?",arrayOf(id.toString()))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }
}
