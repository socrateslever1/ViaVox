package com.example.viavox.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.viavox.model.NearbyLandmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.math.cos

/**
 * Persistent, indexed local geographic catalogue. The source's official name is
 * always preserved separately from the optional audio alias.
 *
 * Important: this database stores *verified points*, not guessed road entrances.
 * Municipality and neighborhood records do not claim street-level GPS accuracy.
 * All public methods run SQLite off the main thread.
 */
class GeoCatalogDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "viavox_geography.db", null, 1) {

    private val appContext = context.applicationContext
    private val seedMutex = Mutex()
    private var seeded = false

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE municipalities(
            ibge_code INTEGER PRIMARY KEY, name TEXT NOT NULL, uf TEXT NOT NULL,
            source TEXT NOT NULL, updated_at INTEGER NOT NULL)""")
        db.execSQL("""CREATE INDEX municipalities_names ON municipalities(name COLLATE NOCASE)""")
        db.execSQL("""CREATE TABLE neighborhoods(
            city_code INTEGER NOT NULL, name TEXT NOT NULL,
            zone TEXT, source TEXT NOT NULL,
            PRIMARY KEY(city_code, name))""")
        db.execSQL("""CREATE TABLE streets(
            city_code INTEGER NOT NULL, name TEXT NOT NULL,
            lat REAL, lon REAL, source TEXT NOT NULL, updated_at INTEGER NOT NULL,
            PRIMARY KEY(city_code, name))""")
        db.execSQL("""CREATE TABLE places(
            id TEXT UNIQUE NOT NULL, official_name TEXT NOT NULL,
            speech_name TEXT, search_key TEXT NOT NULL,
            lat REAL NOT NULL, lon REAL NOT NULL, kind TEXT NOT NULL,
            approximate INTEGER NOT NULL DEFAULT 0, source TEXT NOT NULL,
            updated_at INTEGER NOT NULL)""")
        db.execSQL("""CREATE VIRTUAL TABLE place_bounds USING rtree(
            rowid, min_lat, max_lat, min_lon, max_lon)""")
        db.execSQL("""CREATE INDEX places_search_key ON places(search_key)""")
        db.execSQL("""CREATE TABLE aliases(
            canonical_key TEXT NOT NULL, spoken_name TEXT NOT NULL,
            city_code INTEGER, source TEXT NOT NULL,
            PRIMARY KEY(canonical_key, city_code))""")
        db.execSQL("""CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL)""")
        // Explicit city-specific override, not a blind rename of every bus terminal.
        db.execSQL("""INSERT INTO aliases(canonical_key,spoken_name,city_code,source)
            VALUES('terminal rodoviario engenheiro huascar angelim',
              'Terminal Rodoviário de Manaus',1302603,'user-approved')""")
        // First launch is fully functional offline; IBGE revalidates codes monthly.
        AmazonasMunicipalityDirectory.all.forEach { (code,name) ->
            val values = ContentValues().apply {
                put("ibge_code",code)
                put("name",name)
                put("uf","AM")
                put("source","IBGE/AAM:seed")
                put("updated_at",0L)
            }
            db.insertWithOnConflict("municipalities",null,values,
                SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future releases must use additive migrations, preserving offline caches.
    }

    suspend fun ensureSeeded() = seedMutex.withLock {
        if (seeded) return@withLock
        withContext(Dispatchers.IO) {
            val db = writableDatabase
            val existing = db.rawQuery("SELECT value FROM metadata WHERE key='seed_v1'",null)
                .use { if (it.moveToFirst()) it.getString(0) else null }
            if (existing != "ok") {
                val points = OfflineNearbyLandmarks.verifiedSites() +
                    runCatching {
                        appContext.assets.open("sedes_policiais.geojson").bufferedReader()
                            .use { OfflineNearbyLandmarks.parsePoliceStations(it.readText()) }
                    }.getOrDefault(emptyList())
                db.beginTransaction()
                try {
                    writePlaces(db,points,"bundled:verified")
                    val root = JSONObject(appContext.assets.open("bairros_manaus.geojson")
                        .bufferedReader().use { it.readText() })
                    val features = root.optJSONArray("features")
                    if (features != null) for (i in 0 until features.length()) {
                        val props = features.optJSONObject(i)?.optJSONObject("properties") ?: continue
                        val name = props.optString("name").trim()
                        if (name.isBlank()) continue
                        val v = ContentValues().apply {
                            put("city_code",1302603)
                            put("name",name)
                            put("zone",props.optString("zona"))
                            put("source","municipal:bundled")
                        }
                        db.insertWithOnConflict("neighborhoods",null,v,
                            SQLiteDatabase.CONFLICT_IGNORE)
                    }
                    db.execSQL("INSERT OR REPLACE INTO metadata VALUES('seed_v1','ok')")
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            seeded = true
        }
    }

    suspend fun upsertPlaces(places: List<NearbyLandmark>, source: String) =
        withContext(Dispatchers.IO) {
            if (places.isEmpty()) return@withContext
            ensureSeeded()
            val db = writableDatabase
            db.beginTransaction()
            try {
                writePlaces(db,places,source)
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }

    private fun writePlaces(db: SQLiteDatabase, points: List<NearbyLandmark>, source: String) {
        val now = System.currentTimeMillis()
        for (poi in points) {
            if (!poi.latitude.isFinite() || !poi.longitude.isFinite() ||
                poi.latitude !in -90.0..90.0 || poi.longitude !in -180.0..180.0 ||
                poi.id.isBlank() || poi.name.isBlank()) continue
            val name = poi.name.trim().take(150)
            val values = ContentValues().apply {
                put("official_name",name)
                put("speech_name",GeoSpeechPolicy.referenceAlias(name))
                put("search_key",fold(name))
                put("lat",poi.latitude)
                put("lon",poi.longitude)
                put("kind",poi.kind.take(40))
                put("approximate",if (poi.approximatePoint) 1 else 0)
                put("source",source.take(90))
                put("updated_at",now)
            }
            val rows = db.update("places",values,"id=?",arrayOf(poi.id))
            if (rows == 0) {
                values.put("id",poi.id)
                db.insertWithOnConflict("places",null,values,
                    SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.rawQuery("SELECT rowid FROM places WHERE id=?",arrayOf(poi.id)).use { cursor ->
                if (cursor.moveToFirst()) {
                    db.execSQL("INSERT OR REPLACE INTO place_bounds VALUES(?,?,?,?,?)",
                        arrayOf(cursor.getLong(0),poi.latitude,poi.latitude,
                            poi.longitude,poi.longitude))
                }
            }
        }
    }

    suspend fun around(lat: Double, lon: Double, radiusMeters: Int): List<NearbyLandmark> =
        withContext(Dispatchers.IO) {
            if (!lat.isFinite() || !lon.isFinite() ||
                lat !in -90.0..90.0 || lon !in -180.0..180.0) return@withContext emptyList()
            ensureSeeded()
            val radius = radiusMeters.coerceIn(50,1500)
            val dLat = radius / 111_195.0
            val dLon = radius / (111_195.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.15))
            val results = ArrayList<NearbyLandmark>()
            readableDatabase.rawQuery(
                """SELECT p.id,p.official_name,p.lat,p.lon,p.kind,p.approximate
                   FROM place_bounds b JOIN places p ON p.rowid=b.rowid
                   WHERE b.max_lat>=? AND b.min_lat<=? AND b.max_lon>=? AND b.min_lon<=?
                   LIMIT 1000""",
                arrayOf((lat-dLat).toString(),(lat+dLat).toString(),
                    (lon-dLon).toString(),(lon+dLon).toString())
            ).use { c ->
                while(c.moveToNext()) {
                    val point = NearbyLandmark(c.getString(0),c.getString(1),
                        c.getDouble(2),c.getDouble(3),c.getString(4),c.getInt(5)==1)
                    if (point.distanceTo(lat,lon) <= radius) results.add(point)
                }
            }
            results
        }

    suspend fun learnStreet(cityCode:Int, name:String, lat:Double, lon:Double,
                            source:String = "nominatim") = withContext(Dispatchers.IO) {
        if (name.isBlank() || !lat.isFinite() || !lon.isFinite() ||
            lat !in -90.0..90.0 || lon !in -180.0..180.0) return@withContext
        ensureSeeded()
        val values=ContentValues().apply {
            put("city_code",cityCode);put("name",name.take(150));put("lat",lat)
            put("lon",lon);put("source",source);put("updated_at",System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("streets",null,values,
            SQLiteDatabase.CONFLICT_REPLACE)
    }

    suspend fun replaceMunicipalities(municipalities:List<Pair<Int,String>>) =
        withContext(Dispatchers.IO) {
            // An incomplete or malformed response may not erase existing locations.
            if(municipalities.size != 62 || municipalities.any {
                it.first !in 1300000..1399999 || it.second.isBlank()
            } || municipalities.map { it.first }.distinct().size != 62) return@withContext false
            val db=writableDatabase
            db.beginTransaction()
            try {
                municipalities.forEach { (id,name) ->
                    val values=ContentValues().apply {
                        put("ibge_code",id);put("name",name);put("uf","AM")
                        put("source","IBGE");put("updated_at",System.currentTimeMillis())
                    }
                    db.insertWithOnConflict("municipalities",null,values,
                        SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.execSQL("INSERT OR REPLACE INTO metadata VALUES('ibge_updated_at',?)",
                    arrayOf(System.currentTimeMillis().toString()))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            true
        }

    suspend fun lastMunicipalitySync():Long = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT value FROM metadata WHERE key='ibge_updated_at'",null)
            .use { if (it.moveToFirst()) it.getString(0).toLongOrNull() ?: 0L else 0L }
    }

    suspend fun municipalityCount():Int = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM municipalities",null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /**
     * Offline suggestions only return verified geocoded POIs. A street learned
     * at a sample GPS fix is not automatically a safe routable entrance.
     */
    /**
     * A versioned, bounded, streamed geodata snapshot. Never import untrusted
     * arbitrary SQL or overwrite user data. A closing count trailer detects
     * truncated downloads before the installed version is advanced.
     */
    suspend fun importGeoPack(compressed: InputStream): Int = withContext(Dispatchers.IO) {
        ensureSeeded()
        val db=writableDatabase
        var count=0
        var completed=false
        val municipalityCodes=AmazonasMunicipalityDirectory.all.map { it.first }.toSet()
        GZIPInputStream(compressed).bufferedReader(Charsets.UTF_8).use { reader ->
            val headerLine=reader.readLine() ?: error("Pacote vazio")
            val header=JSONObject(headerLine)
            require(header.optString("type")=="header" &&
                header.optInt("schema")==1 &&
                header.optString("region")=="AM") { "Pacote geográfico inválido" }
            val version=header.optString("version").take(80)
            require(Regex("""[0-9]{4}-[0-9]{2}-[0-9]{2}""").matches(version)) {
                "Versão inválida"
            }
            db.beginTransaction()
            try {
                while(true) {
                    val line=reader.readLine() ?: break
                    require(line.length <= 4096) { "Registro geográfico excede limite" }
                    val data=JSONObject(line)
                    if(data.optString("type")=="end") {
                        require(data.optInt("records",-1)==count &&
                            reader.readLine()==null) { "Pacote incompleto" }
                        db.execSQL(
                            "INSERT OR REPLACE INTO metadata VALUES('geopack_version',?)",
                            arrayOf(version))
                        completed=true
                        break
                    }
                    require(++count <= 1_500_000) { "Pacote muito grande" }
                    val code=data.optInt("city_code")
                    if (code !in municipalityCodes) continue
                    val name=data.optString("name").trim().take(150)
                    val lat=data.optDouble("lat",Double.NaN)
                    val lon=data.optDouble("lon",Double.NaN)
                    if(name.isBlank() || !lat.isFinite() || !lon.isFinite() ||
                        lat !in -10.5..3.0 || lon !in -74.0..-56.0) continue
                    when(data.optString("type")) {
                        "poi" -> writePlaces(db,listOf(NearbyLandmark(
                            data.optString("id").take(90),name,lat,lon,
                            data.optString("kind","amenity").take(40),
                            data.optBoolean("approximate",false))),"OSM:Geofabrik")
                        "street" -> {
                            val values=ContentValues().apply {
                                put("city_code",code)
                                put("name",name)
                                put("lat",lat)
                                put("lon",lon)
                                put("source",if(data.optString("source")=="IBGE:faces:2022")
                                    "IBGE:faces:2022" else "OSM:Geofabrik")
                                put("updated_at",System.currentTimeMillis())
                            }
                            db.insertWithOnConflict("streets",null,values,
                                SQLiteDatabase.CONFLICT_IGNORE)
                        }
                    }
                    if(count%700==0) {
                        db.setTransactionSuccessful()
                        db.endTransaction()
                        db.beginTransaction()
                    }
                }
                require(completed) { "Fim do pacote geográfico ausente" }
                db.setTransactionSuccessful()
            } finally { if(db.inTransaction()) db.endTransaction() }
        }
        count
    }

    suspend fun installedGeoPackVersion():String = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery(
            "SELECT value FROM metadata WHERE key='geopack_version'",null)
            .use { if(it.moveToFirst()) it.getString(0) else "" }
    }

    suspend fun searchDestinations(query:String, limit:Int=8):
        List<com.example.viavox.model.MapPlace> = withContext(Dispatchers.IO) {
        val needle=fold(query)
        if (needle.length < 2) return@withContext emptyList()
        ensureSeeded()
        val results=mutableListOf<com.example.viavox.model.MapPlace>()
        readableDatabase.rawQuery(
            "SELECT official_name,lat,lon,source FROM places WHERE search_key LIKE ? LIMIT ?",
            arrayOf("%$needle%",limit.coerceIn(1,20).toString())
        ).use { c ->
            while(c.moveToNext()) results.add(com.example.viavox.model.MapPlace(
                name=c.getString(0),latitude=c.getDouble(1),longitude=c.getDouble(2),
                subtitle="Referência mapeada · "+c.getString(3)
            ))
        }
        results
    }

    suspend fun searchByName(query:String, limit:Int=20):List<String> =
        withContext(Dispatchers.IO) {
            val needle=fold(query)
            if (needle.length < 2) return@withContext emptyList()
            val results=mutableListOf<String>()
            readableDatabase.rawQuery(
                "SELECT official_name FROM places WHERE search_key LIKE ? LIMIT ?",
                arrayOf("%$needle%",limit.coerceIn(1,50).toString())
            ).use { while(it.moveToNext()) results.add(it.getString(0)) }
            results
        }

    companion object {
        @Volatile private var sharedInstance: GeoCatalogDatabase? = null

        fun shared(context: Context): GeoCatalogDatabase =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: GeoCatalogDatabase(context.applicationContext).also {
                    sharedInstance = it
                }
            }

        fun fold(s:String):String=Normalizer.normalize(s,Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"),"")
            .lowercase(Locale.forLanguageTag("pt-BR"))
            .replace(Regex("[^a-z0-9 ]")," ").replace(Regex("\\s+")," ").trim()
    }
}
