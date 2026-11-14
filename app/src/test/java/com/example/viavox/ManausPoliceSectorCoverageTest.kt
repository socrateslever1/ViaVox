package com.example.viavox

import com.example.viavox.service.PoliceJurisdictionSpeechResolver
import com.example.viavox.service.TerritoryGeojsonResolver
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Checks the bundled Manaus map itself, not synthetic polygons. */
class ManausPoliceSectorCoverageTest {
    private fun asset(name:String):String =
        listOf(File("src/main/assets",name),File("app/src/main/assets",name))
            .first { it.isFile }.readText()

    private val map by lazy {
        TerritoryGeojsonResolver.parse(
            asset("bairros_manaus.geojson"),asset("cicoms_manaus.geojson"))
    }

    @Test fun formerlyUncoveredAreasNowResolveToTheirCicomAndCpa() {
        val jorgeTeixeira=map.resolve(-3.00901,-59.90875,6f)
        assertEquals("30ª CICOM",jorgeTeixeira?.cicom)
        assertEquals("CPA Leste",jorgeTeixeira?.cpa)
        assertEquals("Zona Leste",jorgeTeixeira?.zone)
        val saoJorge=map.resolve(-3.10152,-60.04109,6f)
        assertEquals("21ª CICOM",saoJorge?.cicom)
        assertEquals("CPA Oeste",saoJorge?.cpa)
        assertEquals("Zona Oeste",saoJorge?.zone)
    }

    @Test fun everyCicomFromFirstToThirtiethHasACpa() {
        for (number in 1..30) assertNotNull("CICOM $number",
            PoliceJurisdictionSpeechResolver.resolve("${number}ª CICOM","Manaus"))
    }

    @Test fun cicomAndCpaMatchTheZoneOfTheirNeighbourhood() {
        // One interior point per zone: bairro zone and CPA must agree.
        val expectations=listOf(
            Triple(-3.1340 to -60.0220,"Zona Sul","CPA Sul"),
            Triple(-3.0350 to -59.9850,"Zona Norte","CPA Norte"),
            Triple(-3.0850 to -60.0450,"Zona Centro-Oeste","CPA Centro-Sul/Centro-Oeste"),
            Triple(-3.0900 to -60.0150,"Zona Centro-Sul","CPA Centro-Sul/Centro-Oeste"))
        for ((point,zone,cpa) in expectations) {
            val match=map.resolve(point.first,point.second,6f)
            assertEquals(zone,match?.zone)
            assertEquals(cpa,match?.cpa)
        }
    }
}
