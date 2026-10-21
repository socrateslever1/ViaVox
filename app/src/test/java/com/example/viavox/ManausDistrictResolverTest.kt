package com.example.viavox

import com.example.viavox.data.ManausDistrictResolver
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class ManausDistrictResolverTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun loadBundledPolygons() {
            val candidates = listOf(
                File("src/main/assets/bairros_manaus.geojson"),
                File("app/src/main/assets/bairros_manaus.geojson")
            )
            val source = candidates.firstOrNull(File::isFile)
                ?: error("bairros_manaus.geojson not found")
            assertTrue(ManausDistrictResolver.loadGeoJson(source.readText(Charsets.UTF_8)))
        }
    }

    @Test fun resolvesUfamAndCoroadoRoundaboutFromBundledPolygons() {
        assertEquals(
            "Coroado",
            ManausDistrictResolver.resolveDistrict(-3.100250, -59.976519)?.name
        )
        assertEquals(
            "Coroado",
            ManausDistrictResolver.resolveDistrict(-3.092517, -59.988227)?.name
        )
    }

    @Test fun keepsTheAleixoSideOfTheBoundaryInAleixo() {
        val district = ManausDistrictResolver.resolveDistrict(-3.093110, -59.989190)
        assertEquals("Aleixo", district?.name)
        assertEquals("Zona Centro-Sul", district?.zone)
    }

    @Test fun neverUsesOverlappingRectanglesOutsideARealPolygon() {
        assertNull(ManausDistrictResolver.resolveDistrict(-3.249, -60.179))
    }
}
