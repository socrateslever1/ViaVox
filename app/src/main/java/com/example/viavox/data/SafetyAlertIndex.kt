package com.example.viavox.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.viavox.service.SafetyAlertEngine.SafetyFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.math.cos

/** One validated "alert" record of the regional data pack. */
data class PackAlert(
    val id: String,
    val kind: String,
    val name: String,
    val maxspeed: Int?,
    val latitude: Double,
    val longitude: Double
)

/**
 * Pure reader for the alert records of the geographic pack, so the rules are
 * testable without Android. It validates the whole stream (header, record
 * count, end trailer) before returning anything: a truncated download yields
 * an exception, never a partial list.
 */
object SafetyAlertPack {
    val KINDS = setOf("speed_camera", "red_light_camera", "toll_booth",
        "level_crossing", "speed_bump")
    private const val MAX_RECORDS = 1_500_000
    private val VERSION = Regex("""[0-9]{4}-[0-9]{2}-[0-9]{2}""")

    fun read(compressed: InputStream): List<PackAlert> {
        val alerts = ArrayList<PackAlert>()
        val seenIds = HashSet<String>()
        var seen = 0
        GZIPInputStream(compressed).bufferedReader().use { reader ->
            val header = JSONObject(reader.readLine() ?: error("Empty pack"))
            require(header.optInt("schema") == 1 && header.optString("region") == "AM") {
                "Invalid pack header"
            }
            require(VERSION.matches(header.optString("version"))) { "Invalid pack version" }
            var finished = false
            while (!finished) {
                val line = reader.readLine() ?: error("Missing pack trailer")
                require(line.length <= 24_000) { "Excessive pack record" }
                val data = JSONObject(line)
                val type = data.optString("type")
                if (type == "end") {
                    require(data.optInt("records", -1) == seen && reader.readLine() == null) {
                        "Incomplete pack"
                    }
                    finished = true
                    continue
                }
                require(++seen <= MAX_RECORDS) { "Pack too large" }
                if (type != "alert") continue
                val kind = data.optString("kind")
                val id = data.optString("id").take(90)
                val lat = data.optDouble("lat", Double.NaN)
                val lon = data.optDouble("lon", Double.NaN)
                if (kind !in KINDS || id.isBlank() || !seenIds.add(id) ||
                    !lat.isFinite() || !lon.isFinite() ||
                    lat !in -10.5..3.0 || lon !in -74.0..-56.0) continue
                val speed = data.optInt("maxspeed", -1).takeIf { it in 5..200 }
                alerts += PackAlert(id, kind, data.optString("name").trim().take(80),
                    speed, lat, lon)
            }
        }
        return alerts
    }

    fun toFeature(alert: PackAlert): SafetyFeature? {
        val type = when (alert.kind) {
            "speed_camera" -> SafetyFeature.Type.SPEED_CAMERA
            "red_light_camera" -> SafetyFeature.Type.RED_LIGHT_CAMERA
            "toll_booth" -> SafetyFeature.Type.TOLL_BOOTH
            "level_crossing" -> SafetyFeature.Type.LEVEL_CROSSING
            "speed_bump" -> SafetyFeature.Type.SPEED_BUMP
            else -> return null
        }
        return SafetyFeature(
            id = alert.id, type = type,
            latitude = alert.latitude, longitude = alert.longitude,
            speedLimitKmh = alert.maxspeed, location = alert.name, source = "pack"
        )
    }
}

/**
 * Offline copy of the road-safety points (speed cameras, lombadas, level
 * crossings, toll booths) extracted from OpenStreetMap into the weekly pack.
 * Its own database keeps the existing geographic catalogue untouched.
 */
class SafetyAlertIndex(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "viavox_safety_alerts.db", null, 1) {
    private val importLock = Mutex()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE alerts(
            id TEXT UNIQUE NOT NULL, kind TEXT NOT NULL, name TEXT NOT NULL,
            maxspeed INTEGER, lat REAL NOT NULL, lon REAL NOT NULL)""")
        db.execSQL("""CREATE VIRTUAL TABLE alert_bounds USING rtree(
            rowid, min_lat, max_lat, min_lon, max_lon)""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    /** Replaces the snapshot only when the pack validates and has alerts. */
    suspend fun importFromGeopack(input: InputStream): Int = withContext(Dispatchers.IO) {
        importLock.withLock {
            val alerts = SafetyAlertPack.read(input)
            if (alerts.isEmpty()) return@withLock 0
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.execSQL("DELETE FROM alerts")
                db.execSQL("DELETE FROM alert_bounds")
                for (alert in alerts) {
                    val values = ContentValues().apply {
                        put("id", alert.id); put("kind", alert.kind)
                        put("name", alert.name)
                        if (alert.maxspeed != null) put("maxspeed", alert.maxspeed)
                        put("lat", alert.latitude); put("lon", alert.longitude)
                    }
                    val rowId = db.insertWithOnConflict(
                        "alerts", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                    if (rowId > 0) db.execSQL(
                        "INSERT INTO alert_bounds VALUES(?,?,?,?,?)",
                        arrayOf(rowId, alert.latitude, alert.latitude,
                            alert.longitude, alert.longitude))
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            alerts.size
        }
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM alerts", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    /**
     * Safety points within [radiusMeters]. Null means no pack has been
     * installed yet (the caller falls back to the online query); an empty
     * list means the area really has none.
     */
    suspend fun around(lat: Double, lon: Double, radiusMeters: Int): List<SafetyFeature>? =
        withContext(Dispatchers.IO) {
            if (!lat.isFinite() || !lon.isFinite()) return@withContext emptyList()
            if (count() == 0) return@withContext null
            val dLat = radiusMeters / 111_195.0
            val dLon = radiusMeters / (111_195.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.15))
            val result = ArrayList<SafetyFeature>()
            readableDatabase.rawQuery(
                """SELECT a.id,a.kind,a.name,a.maxspeed,a.lat,a.lon FROM alert_bounds b
                   JOIN alerts a ON a.rowid=b.rowid
                   WHERE b.max_lat>=? AND b.min_lat<=? AND b.max_lon>=? AND b.min_lon<=?
                   LIMIT 600""",
                arrayOf((lat - dLat).toString(), (lat + dLat).toString(),
                    (lon - dLon).toString(), (lon + dLon).toString())
            ).use { c ->
                while (c.moveToNext()) {
                    SafetyAlertPack.toFeature(PackAlert(
                        c.getString(0), c.getString(1), c.getString(2),
                        if (c.isNull(3)) null else c.getInt(3),
                        c.getDouble(4), c.getDouble(5)))?.let(result::add)
                }
            }
            result
        }

    companion object {
        @Volatile private var shared: SafetyAlertIndex? = null
        fun get(context: Context): SafetyAlertIndex =
            shared ?: synchronized(this) {
                shared ?: SafetyAlertIndex(context.applicationContext).also { shared = it }
            }
    }
}
