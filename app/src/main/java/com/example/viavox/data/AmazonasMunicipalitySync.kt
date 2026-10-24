package com.example.viavox.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/** Quiet, rate-limited refresh of the official Amazonas municipality directory. */
class AmazonasMunicipalitySync(
    private val database: GeoCatalogDatabase,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(12,TimeUnit.SECONDS)
        .connectTimeout(5,TimeUnit.SECONDS)
        .build()
) {
    suspend fun refreshIfDue(): Boolean = withContext(Dispatchers.IO) {
        val last = database.lastMunicipalitySync()
        if (last > 0 && System.currentTimeMillis()-last < 30L*24*60*60*1000)
            return@withContext false
        try {
            val request=Request.Builder()
                .url("https://servicodados.ibge.gov.br/api/v1/localidades/estados/13/municipios")
                .header("Accept","application/json")
                .header("User-Agent","ViaVox/1.0 (Amazonas geographic directory)")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val raw=response.body?.string().orEmpty()
                if (raw.length > 500_000) return@withContext false
                val municipalities=parse(raw)
                database.replaceMunicipalities(municipalities)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e:Exception) {
            Log.w("ViaVoxGeoCatalog","IBGE municipality update unavailable; retaining local data",e)
            false
        }
    }

    companion object {
        /** Protect against partial/foreign UF responses before updating SQLite. */
        fun parse(json:String):List<Pair<Int,String>> {
            val array=JSONArray(json)
            if (array.length()!=62) return emptyList()
            val out=mutableListOf<Pair<Int,String>>()
            for (i in 0 until array.length()) {
                val item=array.optJSONObject(i) ?: return emptyList()
                val id=item.optInt("id")
                val name=item.optString("nome").trim()
                if (id !in 1300000..1399999 || name.isEmpty()) return emptyList()
                out.add(id to name)
            }
            return out.takeIf { it.distinctBy { row -> row.first }.size==62 }
                ?: emptyList()
        }
    }
}
