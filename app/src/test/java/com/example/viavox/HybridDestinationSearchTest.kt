package com.example.viavox

import com.example.viavox.data.DestinationSearchPolicy
import com.example.viavox.data.NavigationNetworkService
import com.example.viavox.model.MapPlace
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class HybridDestinationSearchTest {
    @Test fun acceptsCicomQueriesRegardlessOfOrderOrAccents() {
        val variants = DestinationSearchPolicy.queryVariants("24ª CICOM")
        assertTrue(variants.any { it.contains("Companhia Interativa Comunitária") })
        assertTrue(variants.any { it.contains("Lourenço da Silva Braga") })
        assertTrue(DestinationSearchPolicy.queryVariants("cicom 24").size > 1)
    }

    @Test fun savedLandmarksAreSearchedOfflineWithoutInventedCoordinates() {
        val real = MapPlace("24ª CICOM", -3.1011, -60.0144, "Manaus")
        val found = DestinationSearchPolicy.localMatches("24 cicom", listOf(real))
        assertEquals(1, found.size)
        assertEquals(real.latitude, found.single().latitude, 0.0)
        assertTrue(DestinationSearchPolicy.localMatches("24 cicom", emptyList()).isEmpty())
    }

    @Test fun parsesNominatimPointReturnedInPortuguese() {
        val json = """
        [{"lat":"-3.119","lon":"-60.022","name":"Praça do Congresso",
        "display_name":"Praça do Congresso, Centro, Manaus",
        "address":{"city":"Manaus"}}]
        """.trimIndent()
        val result = NavigationNetworkService.parseNominatim(json)
        assertEquals("Praça do Congresso", result.single().name)
        assertEquals(-60.022, result.single().longitude, 0.000001)
    }

    @Test fun photon400FallsBackToExplicitNominatimSearch() = runBlocking {
        val endpoints = mutableListOf<String>()
        val fakeHttp = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url.toString()
            endpoints.add(url)
            val photon = url.contains("/api?")
            val body = if (photon) "{}" else
                """[{"lat":"-3.110","lon":"-60.011","display_name":"Avenida Brasil, Manaus"}]"""
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(if (photon) 400 else 200)
                .message(if (photon) "Bad Request" else "OK")
                .body(body.toResponseBody())
                .build()
        }.build()
        val service = NavigationNetworkService(
            http = fakeHttp, photonBase = "https://test.example",
            osrmBase = "https://router.test", nominatimBase = "https://test.example"
        )
        val result = service.search("Avenida Brasil Manaus", -3.1, -60.0).getOrThrow()
        assertEquals("Avenida Brasil", result.single().name)
        assertTrue(endpoints.first().contains("/api?"))
        assertFalse(endpoints.first().contains("lang=pt"))
        assertTrue(endpoints.any { it.contains("/search?") })
    }

    @Test fun favoritesRemainAvailableWhenOnlineProvidersFail() = runBlocking {
        val offline = MapPlace("Minha Casa", -3.10, -60.02)
        val fakeHttp = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request())
                .protocol(Protocol.HTTP_1_1).code(502).message("Down")
                .body("{}".toResponseBody()).build()
        }.build()
        val service = NavigationNetworkService(
            fakeHttp, "https://offline.test", "https://router.test", "https://offline.test"
        )
        val out = service.search("minha casa",null,null,listOf(offline)).getOrThrow()
        assertEquals(offline, out.first())
    }

    @Test fun recognizesCicomEvenWhenNumberComesFirstAndWhileTyping() {
        assertEquals("24",DestinationSearchPolicy.cicomNumber("24 cicom"))
        assertEquals("24",DestinationSearchPolicy.cicomNumber("CICOM 24"))
        assertEquals("24",DestinationSearchPolicy.cicomNumber("24ª CICOM"))
        assertEquals("24",DestinationSearchPolicy.cicomNumber("24 ci"))
        assertTrue(DestinationSearchPolicy.isCicomQuery("24 cicom"))
        assertFalse(DestinationSearchPolicy.isCicomQuery("Cicomar"))
    }

    @Test fun liveSuggestionsNeverOfferForeignCicomar() = runBlocking {
        val requests = mutableListOf<String>()
        val fake = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request().url.toString()
            val data = """
                {"features":[{"attributes":{"OBJECTID":21056,
                  "NOME_PONTO":"VIGÉSIMA QUARTA COMPANHIA INTERATIVA COMUNITÁRIA24ª CICOM",
                  "SIGLA_PONTO":"24ª CICOM","TIPO":"POLÍCIA MILITAR",
                  "ENDERECO":"Av. Manaus Moderna","BAIRRO":"Centro"},
                  "geometry":{"x":-60.01634535299996,"y":-3.13791017099993}}]}
            """.trimIndent()
            Response.Builder().request(chain.request())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(data.toResponseBody()).build()
        }.build()
        val service = NavigationNetworkService(
            http = fake,
            photonBase = "https://photon.invalid",
            nominatimBase = "https://nominatim.invalid"
        )
        val result = service.suggest("24 ci",-3.1,-60.0)
        assertEquals("24ª CICOM",result.first().name)
        assertTrue(result.all { it.latitude < 0 && it.longitude < 0 })
        assertEquals(1, requests.size)
        assertTrue(requests.single().contains("arcgis"))
        val explicit = service.search("24 cicom",-3.1,-60.0).getOrThrow()
        assertEquals("24ª CICOM",explicit.first().name)
        assertTrue(requests.none { it.contains("photon.invalid") })
    }

    @Test fun verifiedLocalCicomRemainsFindableWhenServicesAreOffline() = runBlocking {
        val fake = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request())
                .protocol(Protocol.HTTP_1_1).code(502).message("Unavailable")
                .body("{}".toResponseBody()).build()
        }.build()
        val service = NavigationNetworkService(
            fake,"https://photon.invalid","https://router.invalid","https://nominatim.invalid"
        )
        val results = service.search("24 cicom",-3.1,-60.0).getOrThrow()
        assertEquals("24ª CICOM",results.first().name)
        assertEquals(-3.13791017099993,results.first().latitude,1e-8)
    }

    @Test fun rawTechnicalPoiErrorsStayOutOfUserHud() {
        val display = com.example.viavox.ui.components.PoiStatusPresenter.display(
            "Servidor de pontos de referência indisponível (502)"
        )
        assertTrue(display.contains("Referências indisponíveis"))
        assertFalse(display.contains("502"))
    }
}
