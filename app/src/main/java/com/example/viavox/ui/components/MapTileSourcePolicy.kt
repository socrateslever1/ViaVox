package com.example.viavox.ui.components

/**
 * Basemap requests must have a working, authorized provider.
 * CARTO requires an account API key. Without one, the public OSM raster
 * endpoint is used in accordance with its interactive-viewport tile policy.
 */
object MapTileSourcePolicy {
    fun urls(style: String, z: Int, x: Int, y: Int, cartoApiKey: String = ""): List<String> {
        require(z in 0..19 && x in 0 until (1 shl z) && y in 0 until (1 shl z))
        val osm = "https://tile.openstreetmap.org/$z/$x/$y.png"
        val carto = cartoApiKey.trim()
        if (carto.isBlank()) return listOf(osm)
        val layer = if (style == "dark") "dark_all" else "rastertiles/voyager"
        val encodedKey = java.net.URLEncoder.encode(carto, "UTF-8")
        return listOf("https://basemaps.cartocdn.com/$layer/$z/$x/$y.png?key=$encodedKey", osm)
    }

    fun attribution(cartoApiKey: String): String = if (cartoApiKey.isBlank())
        "© OpenStreetMap contributors"
    else "© OpenStreetMap contributors · © CARTO"
}
