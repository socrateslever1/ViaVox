package com.example.viavox.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.viavox.service.ConfidentStreet
import com.example.viavox.service.GeoFix
import com.example.viavox.service.IndexedStreetLine
import com.example.viavox.service.RoadMapMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.math.cos

/**
 * Independent, additive road geometry index: leaves the established geographic
 * catalogue and its migrations untouched. No guessed street centroids.
 */
class RoadGeometryIndex(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "viavox_street_lines.db",null,1) {
    private val importLock = Mutex()

    override fun onCreate(db:SQLiteDatabase) {
        db.execSQL("""CREATE TABLE road_lines(
            id TEXT UNIQUE NOT NULL, name TEXT NOT NULL, city_code INTEGER NOT NULL,
            polyline TEXT NOT NULL, updated_at INTEGER NOT NULL
        )""")
        db.execSQL("""CREATE VIRTUAL TABLE road_bounds USING rtree(
            rowid,min_lat,max_lat,min_lon,max_lon
        )""")
        db.execSQL("""CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)""")
    }
    override fun onUpgrade(db:SQLiteDatabase, oldVersion:Int,newVersion:Int) {}

    /**
     * The full regional snapshot is committed only after header + record count
     * + end trailer validate; a partial download never replaces working roads.
     */
    suspend fun importFromGeopack(input:InputStream):Int = withContext(Dispatchers.IO) {
        importLock.withLock {
            val db=writableDatabase
            var seen=0
            var accepted=0
            val started=System.currentTimeMillis()
            GZIPInputStream(input).bufferedReader().use { reader ->
                val header=JSONObject(reader.readLine() ?: error("Empty road package"))
                require(header.optInt("schema")==1 &&
                    header.optString("region")=="AM") { "Invalid road map header" }
                val version=header.optString("version")
                require(Regex("""[0-9]{4}-[0-9]{2}-[0-9]{2}""").matches(version))
                db.beginTransaction()
                try {
                    var hasEnd=false
                    while(true) {
                        val line=reader.readLine() ?: break
                        require(line.length <= 24000) { "Excessive road geometry record" }
                        val data=JSONObject(line)
                        if (data.optString("type")=="end") {
                            require(data.optInt("records")==seen &&
                                reader.readLine()==null) { "Incomplete road geometry pack" }
                            require(accepted>=1000) { "Not enough mapped street shapes" }
                            db.execSQL("""DELETE FROM road_bounds WHERE rowid IN
                                (SELECT rowid FROM road_lines WHERE updated_at<?)""",
                                arrayOf(started))
                            db.execSQL("DELETE FROM road_lines WHERE updated_at<?",
                                arrayOf(started))
                            db.execSQL("INSERT OR REPLACE INTO meta VALUES('version',?)",
                                arrayOf(version))
                            hasEnd=true
                            break
                        }
                        require(++seen <= 1_500_000) { "Road package too large" }
                        if(data.optString("type")!="road_way") continue
                        val id=data.optString("id").take(90)
                        val name=data.optString("name").trim().take(150)
                        val city=data.optInt("city_code")
                        val points=data.optJSONArray("points") ?: continue
                        if(id.isBlank() || name.isBlank() ||
                            city !in 1300000..1399999 || points.length() !in 2..120) continue
                        val good=(0 until points.length()).mapNotNull { i ->
                            val point=points.optJSONArray(i) ?: return@mapNotNull null
                            val lat=point.optDouble(0,Double.NaN)
                            val lon=point.optDouble(1,Double.NaN)
                            if (lat in -10.5..3.0 && lon in -74.0..-56.0)
                                lat to lon else null
                        }
                        if(good.size<2) continue
                        val values=ContentValues().apply {
                            put("name",name)
                            put("city_code",city)
                            put("polyline",points.toString())
                            put("updated_at",System.currentTimeMillis())
                        }
                        if(db.update("road_lines",values,"id=?",arrayOf(id))==0) {
                            values.put("id",id)
                            db.insertWithOnConflict("road_lines",null,values,
                                SQLiteDatabase.CONFLICT_IGNORE)
                        }
                        db.rawQuery("SELECT rowid FROM road_lines WHERE id=?",arrayOf(id))
                            .use { c ->
                                if(c.moveToFirst()) db.execSQL(
                                    "INSERT OR REPLACE INTO road_bounds VALUES(?,?,?,?,?)",
                                    arrayOf(c.getLong(0),good.minOf { it.first },
                                        good.maxOf { it.first },good.minOf { it.second },
                                        good.maxOf { it.second }))
                            }
                        accepted++
                    }
                    require(hasEnd) { "Missing road map trailer" }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            accepted
        }
    }

    suspend fun count():Int = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM road_lines",null).use {
            if(it.moveToFirst()) it.getInt(0) else 0
        }
    }

    /** RTree query: only tiny local subset decoded, no network wait. */
    suspend fun match(fix:GeoFix):ConfidentStreet? = withContext(Dispatchers.IO) {
        if (fix.accuracyMeters <= 0f || fix.accuracyMeters > 12f ||
            !fix.latitude.isFinite() || !fix.longitude.isFinite())
            return@withContext null
        val radius=(fix.accuracyMeters+28.0).coerceAtMost(42.0)
        val dy=radius/111_195.0
        val dx=radius/(111_195.0*cos(Math.toRadians(fix.latitude)).coerceAtLeast(0.15))
        val lines=ArrayList<IndexedStreetLine>()
        readableDatabase.rawQuery(
            """SELECT r.name,r.city_code,r.polyline FROM road_bounds b
               JOIN road_lines r ON r.rowid=b.rowid
               WHERE b.max_lat>=? AND b.min_lat<=? AND b.max_lon>=? AND b.min_lon<=?
               LIMIT 150""",
            arrayOf((fix.latitude-dy).toString(),(fix.latitude+dy).toString(),
                (fix.longitude-dx).toString(),(fix.longitude+dx).toString())
        ).use { c ->
            while(c.moveToNext()) {
                val arr=runCatching { JSONArray(c.getString(2)) }.getOrNull()
                    ?: continue
                val points=(0 until arr.length()).mapNotNull { i ->
                    val pair=arr.optJSONArray(i) ?: return@mapNotNull null
                    val lat=pair.optDouble(0,Double.NaN)
                    val lon=pair.optDouble(1,Double.NaN)
                    if(lat.isFinite() && lon.isFinite()) lat to lon else null
                }
                if(points.size>=2) lines.add(IndexedStreetLine(c.getString(0),
                    c.getInt(1),points))
            }
        }
        RoadMapMatcher.choose(fix,lines)
    }

    companion object {
        @Volatile private var shared:RoadGeometryIndex?=null
        fun get(context:Context):RoadGeometryIndex =
            shared ?: synchronized(this) {
                shared ?: RoadGeometryIndex(context.applicationContext).also {
                    shared=it
                }
            }
    }
}
