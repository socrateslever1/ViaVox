package com.example.viavox

import com.example.viavox.ui.components.MapTileSourcePolicy
import org.junit.Assert.*
import org.junit.Test

/** Prevent an empty map when CARTO requires a paid/account API key. */
class MapTileSourcePolicyTest {
    @Test fun publicOsmTileWorksWithoutCartoCredentials() {
        listOf("bright", "liberty", "dark").forEach { style ->
            assertEquals(
                listOf("https://tile.openstreetmap.org/17/43689/66077.png"),
                MapTileSourcePolicy.urls(style, 17, 43689, 66077)
            )
        }
        assertEquals("© OpenStreetMap contributors", MapTileSourcePolicy.attribution(""))
    }

    @Test fun configuredCartoKeyKeepsOsmAsFallback() {
        val urls = MapTileSourcePolicy.urls("dark", 12, 1457, 2134, "test key")
        assertEquals(
            "https://basemaps.cartocdn.com/dark_all/12/1457/2134.png?key=test+key",
            urls[0]
        )
        assertEquals("https://tile.openstreetmap.org/12/1457/2134.png", urls[1])
        assertTrue(MapTileSourcePolicy.attribution("test key").contains("CARTO"))
    }

    @Test fun tileCoordinateValidationIsExplicit() {
        assertThrows(IllegalArgumentException::class.java) {
            MapTileSourcePolicy.urls("bright", 19, -1, 10)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MapTileSourcePolicy.urls("bright", 20, 1, 1)
        }
    }
}
