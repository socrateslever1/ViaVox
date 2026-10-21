package com.example.viavox

import com.example.viavox.data.ArcGisManausPlacesService
import com.example.viavox.data.ArcGisReferenceParser
import com.example.viavox.data.GisBounds
import com.example.viavox.data.ManausArcGisLayer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ArcGisManausPlacesServiceTest {
    private val example = """
        {"features":[
            {"attributes":{"OBJECTID":21056,
             "NOME_PONTO":"VIGÉSIMA QUARTA COMPANHIA INTERATIVA COMUNITÁRIA24ª CICOM",
             "SIGLA_PONTO":"24ª CICOM","TIPO":"POLÍCIA MILITAR",
             "SUBTIPO":"COMPANHIA INTERATIVA COMUNITÁRIA",
             "ENDERECO":"AV. MANAUS MODERNA","BAIRRO":"CENTRO"},
             "geometry":{"x":-60.01634535299996,"y":-3.13791017099993}},
            {"attributes":{"OBJECTID":1,"NOME_PONTO":"PALÁCIO DA JUSTIÇA",
             "TIPO":"ÓRGÃO PÚBLICO","ENDERECO":"AV. EDUARDO RIBEIRO",
             "BAIRRO":"CENTRO"},"geometry":{"x":-60.024206,"y":-3.130219}},
            {"attributes":{"OBJECTID":2,"NOME_PONTO":"OUTRO PAÍS"},
             "geometry":{"x":22.0,"y":44.0}}
        ]}
    """.trimIndent()

    @Test fun parsesTrueManausCoordinatesAndPoliceAbbreviation() {
        val places = ArcGisReferenceParser.parse(example)
        assertEquals(2,places.size)
        val unit = places.first()
        assertEquals(21056L,unit.id)
        assertEquals(-3.13791017099993,unit.latitude,0.0000001)
        assertEquals(-60.01634535299996,unit.longitude,0.0000001)
        assertEquals("24ª CICOM",unit.asDestination().name)
        assertEquals("24ª CICOM",unit.shortLabel)
        assertTrue(unit.asDestination().subtitle.contains("Manaus"))
    }

    @Test fun search24CicomUsesLocalArcGisLayerNotForeignGeocoder() = runBlocking {
        val urls = mutableListOf<String>()
        val network = OkHttpClient.Builder().addInterceptor { chain ->
            urls += chain.request().url.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(example.toResponseBody()).build()
        }.build()
        val service = ArcGisManausPlacesService(network)
        val results = service.search("24 cicom")
        assertEquals("24ª CICOM",results.first().name)
        assertEquals(1,urls.size)
        assertTrue(urls.first().contains("where="))
        assertTrue(urls.first().contains("24%25CICOM"))
    }

    @Test fun mapOnlyQueriesZoomedInManausViewport() = runBlocking {
        var count = 0
        val network = OkHttpClient.Builder().addInterceptor { chain ->
            count++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(example.toResponseBody()).build()
        }.build()
        val service = ArcGisManausPlacesService(network)
        val bounds = GisBounds(-60.04,-3.15,-60.00,-3.11)
        assertTrue(service.loadVisible(bounds,12.0).isEmpty())
        assertEquals(0,count)
        assertEquals(2,service.loadVisible(bounds,16.0).size)
        assertEquals(1,count)
        assertEquals(2,service.loadVisible(bounds,16.0).size)
        assertEquals(1,count) // cached; no excess requests at idle
        assertTrue(service.loadVisible(GisBounds(21.0,43.0,23.0,45.0),16.0).isEmpty())
    }

    @Test fun movingMoreThanSixtyMetersRefreshesVoiceLayerInsteadOfUsingStaleBlock() = runBlocking {
        var fetches = 0
        val fakeClient = OkHttpClient.Builder().addInterceptor { chain ->
            fetches++
            Response.Builder().request(chain.request())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(example.toResponseBody()).build()
        }.build()
        val source = ArcGisManausPlacesService(fakeClient)
        val first = GisBounds(-60.0220,-3.1430,-60.0190,-3.1400)
        val near = GisBounds(-60.0223,-3.1430,-60.0193,-3.1400)
        val newBlock = GisBounds(-60.0229,-3.1430,-60.0199,-3.1400)
        source.loadVisible(first,16.0)
        source.loadVisible(near,16.0)
        assertEquals(1,fetches)
        source.loadVisible(newBlock,16.0)
        assertEquals(2,fetches)
    }

    @Test fun refusesInvalidGeometriesAndHttpErrors() {
        assertFalse(ManausArcGisLayer.inCoverage(44.1,22.9))
        assertTrue(ArcGisReferenceParser.parse("""{"features":[]}""").isEmpty())
        var failed=false
        try {
            ArcGisReferenceParser.parse("""{"error":{"code":403,"message":"Private"}}""")
        } catch (_: IllegalStateException) { failed=true }
        assertTrue(failed)
    }
}
