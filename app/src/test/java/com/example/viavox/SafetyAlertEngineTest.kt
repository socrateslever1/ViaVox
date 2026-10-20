package com.example.viavox

import com.example.viavox.service.SafetyAlertEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SafetyAlertEngineTest {

    @Test
    fun parseOfficialAlerts_extractsValidFeatures() {
        val geoJson = """
        {
          "type": "FeatureCollection",
          "features": [
            {
              "type": "Feature",
              "geometry": { "type": "Point", "coordinates": [-60.025, -3.105] },
              "properties": {
                "id": "immu:001",
                "type": "speed_camera",
                "location": "Av. Djalma Batista",
                "maxspeedKmh": 60,
                "sourceName": "IMMU",
                "sourceUrl": "https://immu.manaus.am.gov.br",
                "sourceDate": "2026-10-15",
                "reviewedAt": "2026-10-20"
              }
            },
            {
              "type": "Feature",
              "geometry": { "type": "Point", "coordinates": [-60.030, -3.110] },
              "properties": {
                "id": "immu:002",
                "type": "speed_hump_camera",
                "location": "Av. Constantino Nery",
                "maxspeedKmh": 40,
                "sourceName": "IMMU",
                "sourceUrl": "https://immu.manaus.am.gov.br",
                "sourceDate": "2026-10-15",
                "reviewedAt": "2026-10-20"
              }
            },
            {
              "type": "Feature",
              "geometry": { "type": "Point", "coordinates": [-60.035, -3.115] },
              "properties": {
                "id": "immu:003",
                "type": "red_light_camera",
                "location": "Av. Brasil cruzamento",
                "sourceName": "IMMU",
                "sourceUrl": "https://immu.manaus.am.gov.br",
                "sourceDate": "2026-10-15",
                "reviewedAt": "2026-10-20"
              }
            }
          ]
        }
        """.trimIndent()

        val parsed = SafetyAlertEngine.parseOfficialAlerts(geoJson)
        assertEquals(3, parsed.size)
        assertEquals("immu:001", parsed[0].id)
        assertEquals(SafetyAlertEngine.SafetyFeature.Type.SPEED_CAMERA, parsed[0].type)
        assertEquals(60, parsed[0].speedLimitKmh)
        assertEquals("official", parsed[0].source)

        assertEquals("immu:002", parsed[1].id)
        assertEquals(SafetyAlertEngine.SafetyFeature.Type.SPEED_HUMP_CAMERA, parsed[1].type)
        assertEquals(40, parsed[1].speedLimitKmh)

        assertEquals("immu:003", parsed[2].id)
        assertEquals(SafetyAlertEngine.SafetyFeature.Type.RED_LIGHT_CAMERA, parsed[2].type)
        assertNull(parsed[2].speedLimitKmh)
    }

    @Test
    fun parseOfficialAlerts_ignoresMalformedOrIncompleteFeatures() {
        val geoJson = """
        {
          "type": "FeatureCollection",
          "features": [
            {
              "type": "Feature",
              "geometry": { "type": "Point", "coordinates": [-60.025, 95.0] },
              "properties": { "id": "bad:lat", "type": "speed_camera", "location": "Av. X", "sourceName": "IMMU", "sourceUrl": "https://a.com", "sourceDate": "2026-01-01", "reviewedAt": "2026-01-01" }
            },
            {
              "type": "Feature",
              "geometry": { "type": "Point", "coordinates": [-60.025, -3.100] },
              "properties": { "id": "bad:source", "type": "speed_camera", "location": "Av. X", "sourceName": "", "sourceUrl": "https://a.com", "sourceDate": "2026-01-01", "reviewedAt": "2026-01-01" }
            }
          ]
        }
        """.trimIndent()

        val parsed = SafetyAlertEngine.parseOfficialAlerts(geoJson)
        assertTrue(parsed.isEmpty())
    }

    @Test
    fun parseOsmAlerts_extractsSpeedCamerasAndTolls() {
        val osmJson = """
        {
          "elements": [
            {
              "type": "node",
              "id": 1001,
              "lat": -3.100,
              "lon": -60.020,
              "tags": { "highway": "speed_camera", "maxspeed": "50 km/h", "name": "Radar Av. Torquato" }
            },
            {
              "type": "node",
              "id": 1002,
              "lat": -3.120,
              "lon": -60.010,
              "tags": { "barrier": "toll_booth", "name": "Pedágio Ponte Rio Negro" }
            }
          ]
        }
        """.trimIndent()

        val parsed = SafetyAlertEngine.parseOsmAlerts(osmJson)
        assertEquals(2, parsed.size)
        assertEquals("osm:1001", parsed[0].id)
        assertEquals(SafetyAlertEngine.SafetyFeature.Type.SPEED_CAMERA, parsed[0].type)
        assertEquals(50, parsed[0].speedLimitKmh)
        assertEquals("osm", parsed[0].source)

        assertEquals("osm:1002", parsed[1].id)
        assertEquals(SafetyAlertEngine.SafetyFeature.Type.TOLL_BOOTH, parsed[1].type)
    }

    @Test
    fun mergeAlerts_deduplicatesOsmWhenOfficialPointIsWithin45m() {
        val official = listOf(
            SafetyAlertEngine.SafetyFeature(
                id = "immu:10",
                type = SafetyAlertEngine.SafetyFeature.Type.SPEED_CAMERA,
                latitude = -3.100000,
                longitude = -60.020000,
                speedLimitKmh = 60,
                location = "Av. Djalma",
                source = "official"
            )
        )
        // OSM duplicate ~20 meters away
        val osm = listOf(
            SafetyAlertEngine.SafetyFeature(
                id = "osm:9999",
                type = SafetyAlertEngine.SafetyFeature.Type.SPEED_CAMERA,
                latitude = -3.100150,
                longitude = -60.020000,
                speedLimitKmh = 60,
                source = "osm"
            ),
            SafetyAlertEngine.SafetyFeature(
                id = "osm:8888",
                type = SafetyAlertEngine.SafetyFeature.Type.SPEED_CAMERA,
                latitude = -3.105000,
                longitude = -60.020000,
                speedLimitKmh = 50,
                source = "osm"
            )
        )

        val merged = SafetyAlertEngine.mergeAlerts(official, osm)
        assertEquals(2, merged.size)
        assertEquals("immu:10", merged[0].id)
        assertEquals("osm:8888", merged[1].id)
    }

    @Test
    fun buildAlertMessage_formatsSpeechCorrectly() {
        val radar = SafetyAlertEngine.SafetyFeature(
            id = "1", type = SafetyAlertEngine.SafetyFeature.Type.SPEED_CAMERA,
            latitude = 0.0, longitude = 0.0, speedLimitKmh = 60
        )
        val hump = SafetyAlertEngine.SafetyFeature(
            id = "2", type = SafetyAlertEngine.SafetyFeature.Type.SPEED_HUMP_CAMERA,
            latitude = 0.0, longitude = 0.0, speedLimitKmh = 30
        )
        val light = SafetyAlertEngine.SafetyFeature(
            id = "3", type = SafetyAlertEngine.SafetyFeature.Type.RED_LIGHT_CAMERA,
            latitude = 0.0, longitude = 0.0
        )
        val toll = SafetyAlertEngine.SafetyFeature(
            id = "4", type = SafetyAlertEngine.SafetyFeature.Type.TOLL_BOOTH,
            latitude = 0.0, longitude = 0.0
        )

        assertEquals("Atenção: radar à frente em 200 metros, limite 60 por hora.", SafetyAlertEngine.buildAlertMessage(radar, 200))
        assertEquals("Atenção: lombada eletrônica à frente em 150 metros, limite 30 por hora.", SafetyAlertEngine.buildAlertMessage(hump, 150))
        assertEquals("Atenção: fiscalização semafórica à frente em 180 metros.", SafetyAlertEngine.buildAlertMessage(light, 180))
        assertEquals("Atenção: praça de pedágio à frente em 350 metros.", SafetyAlertEngine.buildAlertMessage(toll, 350))
    }

    @Test
    fun evaluate_triggersAlertWhenApproachingCamera() = runBlocking {
        var alertReceived: String? = null
        val engine = SafetyAlertEngine(
            onAlert = { msg -> alertReceived = msg }
        )

        // Set official camera 220m North of user
        // ~220m in latitude is ~0.002 degrees
        val cameraLat = -3.0980
        val userLat = -3.1000
        val lon = -60.0200

        engine.setOfficialAlerts(listOf(
            SafetyAlertEngine.SafetyFeature(
                id = "immu:test1",
                type = SafetyAlertEngine.SafetyFeature.Type.SPEED_CAMERA,
                latitude = cameraLat,
                longitude = lon,
                speedLimitKmh = 60
            )
        ))

        // Vehicle moving North (bearing = 0 degrees) at 45 km/h
        engine.evaluate(
            userLat = userLat,
            userLon = lon,
            bearing = 0f,
            speedKmh = 45f,
            enabled = true
        )

        assertNotNull(alertReceived)
        assertTrue(alertReceived!!.contains("radar à frente"))
        assertTrue(alertReceived!!.contains("limite 60 por hora"))
    }
}
