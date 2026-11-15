package com.example.viavox

import com.example.viavox.data.GeoContextInterpreter
import com.example.viavox.data.GeoContextInterpreter.PlaceCategory
import com.example.viavox.model.NearbyLandmark
import com.example.viavox.model.Place
import com.example.viavox.model.ScanDirection
import com.example.viavox.model.SurroundingsPolicy
import com.example.viavox.service.GeoFix
import com.example.viavox.service.RoadDetectionEngine
import com.example.viavox.service.TerritoryGeojsonResolver
import com.example.viavox.service.TerritoryMatch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PlaceRankingAndInteriorTest {
    private val lat = -3.1100
    private val lon = -60.0100
    private fun at(meters: Double) = lat + meters / 111_195.0
    private fun landmark(id: String, name: String, kind: String, meters: Double,
                         subkind: String = "") =
        NearbyLandmark(id, name, at(meters), lon, kind, subkind = subkind)

    // ---- categories

    @Test fun categoriesUseAccentFreeNamesAndTagValues() {
        assertEquals(PlaceCategory.HEALTH, GeoContextInterpreter.category("pharmacy", "Drogaria Santo Remédio"))
        assertEquals(PlaceCategory.WORSHIP, GeoContextInterpreter.category("", "Igreja Assembleia de Deus"))
        assertEquals(PlaceCategory.LANDMARK, GeoContextInterpreter.category("", "Mercado Municipal Adolpho Lisboa"))
        assertEquals(PlaceCategory.HEALTH, GeoContextInterpreter.category("", "Posto de Saúde Raiz"))
        assertEquals("a bare tag value is enough",
            PlaceCategory.EMERGENCY, GeoContextInterpreter.category("amenity hospital", "São José"))
        assertEquals(PlaceCategory.EMERGENCY, GeoContextInterpreter.category("fire_station", "Posto Central"))
        assertEquals(PlaceCategory.TRANSPORT, GeoContextInterpreter.category("", "Terminal Rodoviário de Manaus"))
        assertEquals(PlaceCategory.OTHER, GeoContextInterpreter.category("", "Casa da Dona Maria"))
    }

    // ---- variety in a crowded scan

    @Test fun crowdedScanStillShowsHospitalSchoolAndSquare() {
        val points = listOf(
            landmark("c1", "Igreja Assembleia de Deus", "amenity", 20.0),
            landmark("c2", "Igreja Pentecostal Deus é Amor", "amenity", 30.0),
            landmark("c3", "Igreja Adventista", "amenity", 40.0),
            landmark("c4", "Igreja Batista", "amenity", 45.0),
            landmark("c5", "Igreja Universal", "amenity", 55.0),
            landmark("shop1", "Loja de Roupas Bela", "shop", 25.0),
            landmark("shop2", "Mercadinho do Zé", "shop", 35.0),
            landmark("shop3", "Loja Beta", "shop", 48.0),
            landmark("school", "Escola Municipal São José", "amenity", 180.0),
            landmark("park", "Praça da Saudade", "leisure", 120.0),
            landmark("hospital", "Hospital Delphina Aziz", "amenity", 250.0)
        )
        val picked = SurroundingsPolicy.select(
            points, lat, lon, 300, ScanDirection.AROUND, null, 5f).map { it.landmark.id }
        assertEquals(5, picked.size)
        assertTrue("hospital must appear", "hospital" in picked)
        assertTrue("school must appear", "school" in picked)
        assertTrue("square must appear", "park" in picked)
        assertTrue("at most one church", picked.count { it.startsWith("c") } <= 1)
        assertTrue("at most two shops", picked.count { it.startsWith("shop") } <= 2)
    }

    @Test fun quietAreaStillGetsAFullAnswerFromWhatExists() {
        val churches = (1..3).map { landmark("c$it", "Igreja Número $it", "amenity", 20.0 * it) }
        val picked = SurroundingsPolicy.select(
            churches, lat, lon, 200, ScanDirection.AROUND, null, 5f)
        assertEquals("the cap never leaves a scan emptier than the data allows", 3, picked.size)
    }

    @Test fun tagValueFromTheOnlineScanDrivesTheRanking() {
        // The online scan stores kind="amenity" and the value in subkind.
        val hospital = landmark("h", "Santa Casa", "amenity", 200.0, subkind = "hospital")
        val shop = landmark("s", "Santa Loja", "shop", 40.0, subkind = "clothes")
        val picked = SurroundingsPolicy.select(
            listOf(shop, hospital), lat, lon, 300, ScanDirection.AROUND, null, 5f)
        assertEquals("h", picked.first().landmark.id)
    }

    @Test fun tourPrefersSightsOverEverydayPlaces() {
        val sight = GeoContextInterpreter.tourPenalty("tourism", "museum", "Museu da Amazônia")
        val square = GeoContextInterpreter.tourPenalty("leisure", "park", "Praça")
        val school = GeoContextInterpreter.tourPenalty("amenity", "school", "Escola X")
        val shop = GeoContextInterpreter.tourPenalty("shop", "clothes", "Loja Y")
        assertTrue(sight < square && square < school && school < shop)
        assertEquals("places from the offline pack carry the value as kind",
            0.0, GeoContextInterpreter.tourPenalty("park", "", "Parque do Mindu"), 0.0)
    }

    // ---- neighbourhoods outside Manaus

    private fun asset(name: String): String =
        listOf(File("src/main/assets", name), File("app/src/main/assets", name))
            .first { it.isFile }.readText(Charsets.UTF_8)

    private val layers by lazy {
        TerritoryGeojsonResolver.parse(
            asset("bairros_manaus.geojson"), asset("cicoms_manaus.geojson"),
            asset("bairros_interior_am.geojson"))
    }

    @Test fun interiorTownsResolveOfflineToTheirOwnNeighbourhoodAndCity() {
        val parintins = layers.resolve(-2.6283, -56.7358, 4f)
        assertEquals("Parintins", parintins?.city)
        assertEquals("Centro", parintins?.neighbourhood)
        assertNull("the interior has no Manaus-style zones", parintins?.zone)
        assertNull("and no CICOM or CPA", parintins?.cicom)
        assertEquals("Itacoatiara", layers.resolve(-3.1386, -58.4449, 4f)?.city)
    }

    @Test fun manausKeepsItsOwnLayerAndNoExplicitCity() {
        val centre = layers.resolve(-3.1340, -60.0220, 5f)
        assertEquals("CENTRO", centre?.neighbourhood)
        assertEquals("Zona Sul", centre?.zone)
        assertNull("null means Manaus", centre?.city)
    }

    @Test fun engineDoesNotLabelAnInteriorTownAsManaus() = runTest(UnconfinedTestDispatcher()) {
        val seen = mutableListOf<Place>()
        fun engineFor(match: TerritoryMatch) = RoadDetectionEngine(
            coroutineScope = backgroundScope,
            onPlaceUpdated = { seen += it },
            onRoadAnnounceNeeded = { _, _ -> },
            onStatusMessage = {},
            reverseGeocodeProvider = { _, _ -> Result.success(Place(road = "Rua Teste", city = "Outra")) }
        ).also { it.territoryPolygonLookup = { match } }

        engineFor(TerritoryMatch(neighbourhood = "Centro", city = "Parintins"))
            .onGeoFixUpdate(GeoFix(-2.6283, -56.7358, accuracyMeters = 5f, timestampMs = 1_000L))
        engineFor(TerritoryMatch(neighbourhood = "CENTRO", zone = "Zona Sul"))
            .onGeoFixUpdate(GeoFix(-3.134, -60.022, accuracyMeters = 5f, timestampMs = 1_000L))
        assertEquals(listOf("Parintins", "Manaus"), seen.map { it.city })
    }
}
