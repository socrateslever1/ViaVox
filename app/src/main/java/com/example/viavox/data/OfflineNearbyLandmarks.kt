package com.example.viavox.data

import android.content.Context
import com.example.viavox.model.NearbyLandmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Small, attributed offline catalogue. Not a complete POI database.
 * Landmarks are confirmed points from official museums and OpenStreetMap data.
 * Police station points come from existing 'sedes_policiais.geojson' asset.
 * No arbitrary simulated stores or synthetic points are returned.
 */
class OfflineNearbyLandmarks(private val context: Context) {
    private val verifiedHistoricSites = verifiedSites()

    private var loaded: List<NearbyLandmark>? = null

    suspend fun all(): List<NearbyLandmark> = withContext(Dispatchers.IO) {
        synchronized(this@OfflineNearbyLandmarks) { loaded }?.let { return@withContext it }
        val points = runCatching {
            context.assets.open("sedes_policiais.geojson")
                .bufferedReader().use { parsePoliceStations(it.readText()) }
        }.getOrElse { emptyList() }
        (verifiedHistoricSites + points).also { answer ->
            synchronized(this@OfflineNearbyLandmarks) { loaded = answer }
        }
    }

    companion object {
        // Sources: https://visite.museus.gov.br/instituicoes/museu-teatro-amazonas/
        // https://mapcarta.com/pt/N1812729522
        // https://mapcarta.com/pt/N4586379890
        // https://pt.aroundus.com/p/4321798-palacio-da-justica-do-amazonas
        // Additional verified coordinates:
        // https://mapcarta.com/pt/N2304891822
        // https://www.buscacomercio.com/cidades/manaus/hospitais-e-clinicas/clinica-santa-rosa
        // https://buscacomercio.com/cidades/manaus/igrejas-e-templos/paroquia-nossa-senhora-de-fatima/
        // https://brfirmas.org/Pra%C3%A7a%2BQuatorze%2Bde%2BJaneiro-4980470
        // https://mapcarta.com/pt/W496550874
        // https://www.buscacomercio.com/cidades/manaus/hospitais-e-clinicas/hospital-infantil-dr-fajardo
        // https://mapcarta.com/pt/N10133032380
        // https://mapcarta.com/pt/N1721875999
        fun verifiedSites(): List<NearbyLandmark> = listOf(
        NearbyLandmark("museusgov:teatro-amazonas", "Teatro Amazonas",
            -3.130237195, -60.023431219, "tourism", approximatePoint = true),
        NearbyLandmark("osm:1812729522", "Palácio Rio Negro",
            -3.13506, -60.01677, "historic", approximatePoint = true),
        NearbyLandmark("osm:4586379890", "Mercado Municipal Adolpho Lisboa",
            -3.13995, -60.02355, "amenity", approximatePoint = true),
        NearbyLandmark("cultura:palacio-justica", "Palácio da Justiça",
            -3.13024, -60.02439, "historic", approximatePoint = true),
        // Validated POIs along the Praça 14 / Rua Emílio Moreira corridor:
        NearbyLandmark("osm:2304891822", "Cartório Moreira",
            -3.12335, -60.01356, "office"),
        NearbyLandmark("osm:clinica-santa-rosa", "Clínica Santa Rosa",
            -3.122803, -60.013081, "amenity"),
        NearbyLandmark("osm:fatima-praca14", "Paróquia Nossa Senhora de Fátima",
            -3.122206, -60.014575, "amenity"),
        NearbyLandmark("public:praca-quatorze", "Praça Quatorze de Janeiro",
            -3.122509, -60.014469, "leisure", approximatePoint = true),
        NearbyLandmark("osm:496550874", "Maternidade Balbina Mestrinho",
            -3.12519, -60.01124, "amenity", approximatePoint = true),
        NearbyLandmark("cnes:2017849", "Hospital Infantil Dr. Fajardo",
            -3.124865, -60.020214, "amenity", approximatePoint = true),
        NearbyLandmark("osm:10133032380", "Teatro Américo Alvarez",
            -3.12842, -60.01718, "tourism"),
        NearbyLandmark("osm:1721875999", "Amazonprev",
            -3.13204, -60.01326, "office")
    )

        fun parsePoliceStations(text: String): List<NearbyLandmark> {
            val array = JSONObject(text).optJSONArray("features") ?: return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val entry = array.optJSONObject(i) ?: return@mapNotNull null
                val name = entry.optJSONObject("properties")?.optString("name")?.trim()
                    .orEmpty()
                val coords = entry.optJSONObject("geometry")
                    ?.takeIf { it.optString("type") == "Point" }
                    ?.optJSONArray("coordinates") ?: return@mapNotNull null
                val lon = coords.optDouble(0, Double.NaN)
                val lat = coords.optDouble(1, Double.NaN)
                if (name.isBlank() || !lat.isFinite() || !lon.isFinite() ||
                    lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
                NearbyLandmark("offline:police:$i", name.take(70), lat, lon, "police")
            }
        }
    }
}
