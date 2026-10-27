package com.example.viavox.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Weekly, unmetered-only refresh from the project's public, versioned
 * GitHub release. The pack is generated from Geofabrik OSM extracts and
 * official municipality outlines. No user API key, no AI inference.
 */
class GeoPackUpdater(
    private val context:Context,
    private val catalog:GeoCatalogDatabase,
    private val client:OkHttpClient=OkHttpClient.Builder()
        .connectTimeout(10,TimeUnit.SECONDS)
        .readTimeout(30,TimeUnit.SECONDS)
        .callTimeout(90,TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    suspend fun refreshIfDue():Boolean = withContext(Dispatchers.IO) {
        val connectivity=context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return@withContext false
        val network=connectivity.activeNetwork ?: return@withContext false
        val capabilities=connectivity.getNetworkCapabilities(network)
            ?: return@withContext false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            return@withContext false

        val prefs=context.getSharedPreferences("viavox_geopack",Context.MODE_PRIVATE)
        val now=System.currentTimeMillis()
        val last=prefs.getLong("last_attempt",0L)
        // A legacy place-only snapshot is not equivalent to the new mapped
        // road-line layer; retry on Wi-Fi rather than waiting another week.
        val installed=catalog.installedGeoPackVersion().isNotBlank() &&
            RoadGeometryIndex.get(context).count() >= 1000
        // Don't retry missing packages on every foreground launch.
        if(last>0 && now-last < if(installed) 7L*24*60*60*1000 else 12L*60*60*1000)
            return@withContext false
        prefs.edit().putLong("last_attempt",now).apply()

        val file=File(context.cacheDir,"amazonas-geopack-downloading.gz")
        try {
            val request=Request.Builder()
                .url(PACK_URL)
                .header("User-Agent","ViaVox/1.0 (offline Amazonas geodata)")
                .header("Accept","application/octet-stream")
                .build()
            client.newCall(request).execute().use { response ->
                if(!response.isSuccessful) {
                    Log.i("ViaVoxGeoPack","Pack endpoint HTTP " + response.code)
                    return@withContext false
                }
                val body=response.body ?: return@withContext false
                if(body.contentLength()>MAX_COMPRESSED_BYTES)
                    return@withContext false
                file.outputStream().buffered().use { output ->
                    body.byteStream().use { input ->
                        val buffer=ByteArray(32*1024)
                        var written=0L
                        while(true) {
                            val read=input.read(buffer)
                            if(read<0) break
                            written+=read
                            if(written>MAX_COMPRESSED_BYTES)
                                error("Geopack exceeds size limit")
                            output.write(buffer,0,read)
                        }
                    }
                }
            }
            if(file.length()<100) return@withContext false
            // Road RTree is built separately, preserving all previous places and
            // aliases even when a road-map update is unavailable.
            file.inputStream().use {
                RoadGeometryIndex.get(context).importFromGeopack(it)
            }
            file.inputStream().use { catalog.importGeoPack(it) }
            Log.i("ViaVoxGeoPack","Amazonas catalogue imported and indexed")
            true
        } catch (cancelled:CancellationException) {
            throw cancelled
        } catch (error:Exception) {
            Log.w("ViaVoxGeoPack","Package unavailable; old offline data retained",error)
            false
        } finally {
            file.delete()
        }
    }

    companion object {
        // This is not an invented third-party URL. The repository's scheduled
        // workflow publishes/updates exactly this GitHub Release asset.
        const val PACK_URL =
            "https://github.com/socrateslever1/ViaVox/releases/download/" +
            "geopack-am-latest/amazonas-geopack.jsonl.gz"
        private const val MAX_COMPRESSED_BYTES=60L*1024*1024
    }
}
