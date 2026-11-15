package com.example.viavox

import com.example.viavox.data.SafetyAlertPack
import com.example.viavox.service.SafetyAlertEngine
import com.example.viavox.service.SafetyAlertEngine.SafetyFeature
import com.example.viavox.service.SafetyAlertEngine.SafetyFeature.Type
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class OfflineSafetyAlertsTest {
    private fun pack(vararg lines: String, trailerRecords: Int? = null): ByteArray {
        val body = StringBuilder()
        body.append("""{"type":"header","schema":1,"region":"AM","version":"2026-11-14"}""").append('\n')
        lines.forEach { body.append(it).append('\n') }
        body.append("""{"type":"end","records":${trailerRecords ?: lines.size}}""").append('\n')
        return ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(body.toString().toByteArray()) }
        }.toByteArray()
    }

    private fun alert(id: String, kind: String, lat: Double = -3.13, lon: Double = -60.02,
                      extra: String = "") =
        """{"type":"alert","kind":"$kind","id":"$id","city_code":1302603,"lat":$lat,"lon":$lon$extra}"""

    // ---- data pack reader

    @Test fun readsValidAlertsAndSkipsEverythingElse() {
        val alerts = SafetyAlertPack.read(ByteArrayInputStream(pack(
            """{"type":"street","city_code":1302603,"name":"Rua X","lat":-3.1,"lon":-60.0}""",
            alert("osm:node:1", "speed_camera", extra = ""","maxspeed":60,"name":"Radar da Djalma""""),
            alert("osm:node:2", "speed_bump"),
            alert("osm:node:2", "speed_bump"),                       // duplicate id
            alert("osm:node:3", "teleporter"),                        // unknown kind
            alert("osm:node:4", "speed_camera", lat = 10.0),          // outside Amazonas
            alert("osm:node:5", "level_crossing", extra = ""","maxspeed":2""")
        )))
        assertEquals(listOf("osm:node:1", "osm:node:2", "osm:node:5"), alerts.map { it.id })
        assertEquals(60, alerts[0].maxspeed)
        assertEquals("Radar da Djalma", alerts[0].name)
        assertNull("an absurd speed limit is dropped", alerts[2].maxspeed)
    }

    @Test fun truncatedOrInconsistentPackIsRejectedWholesale() {
        // Trailer promises 9 records but only 1 line precedes it.
        assertThrows(IllegalArgumentException::class.java) {
            SafetyAlertPack.read(ByteArrayInputStream(
                pack(alert("osm:node:1", "speed_camera"), trailerRecords = 9)))
        }
        // No trailer at all.
        val noTrailer = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use {
                it.write(("""{"type":"header","schema":1,"region":"AM","version":"2026-11-14"}""" +
                    "\n" + alert("osm:node:1", "speed_camera") + "\n").toByteArray())
            }
        }.toByteArray()
        assertThrows(IllegalStateException::class.java) {
            SafetyAlertPack.read(ByteArrayInputStream(noTrailer))
        }
    }

    @Test fun packKindsMapToEngineTypes() {
        val mapped = SafetyAlertPack.KINDS.associateWith { kind ->
            SafetyAlertPack.toFeature(com.example.viavox.data.PackAlert(
                "id-$kind", kind, "", null, -3.1, -60.0))?.type
        }
        assertEquals(Type.SPEED_BUMP, mapped["speed_bump"])
        assertEquals(Type.LEVEL_CROSSING, mapped["level_crossing"])
        assertEquals(Type.SPEED_CAMERA, mapped["speed_camera"])
        assertEquals(Type.TOLL_BOOTH, mapped["toll_booth"])
        assertEquals(Type.RED_LIGHT_CAMERA, mapped["red_light_camera"])
        assertTrue(mapped.values.none { it == null })
    }

    // ---- engine

    private fun feature(id: String, type: Type, lat: Double, lon: Double = -60.02) =
        SafetyFeature(id = id, type = type, latitude = lat, longitude = lon)

    private val metersPerDegree = 111_195.0
    private fun north(meters: Double) = -3.1000 + meters / metersPerDegree

    private fun engine(spoken: MutableList<String>, client: OkHttpClient = OkHttpClient()) =
        SafetyAlertEngine(client = client, onAlert = { spoken += it })

    @Test fun lombadaIsWarnedLateWhileCameraIsWarnedEarly() = runBlocking {
        val spoken = mutableListOf<String>()
        val engine = engine(spoken)
        val features = listOf(
            feature("camera", Type.SPEED_CAMERA, north(400.0), -60.02),
            feature("bump", Type.SPEED_BUMP, north(400.0), -60.0202))
        engine.localAlertLookup = { _, _ -> features }

        engine.evaluate(-3.1000, -60.02, 0f, 40f)          // both 400 m ahead
        assertEquals(1, spoken.size)
        assertTrue(spoken.single().contains("radar"))

        engine.evaluate(north(200.0), -60.02, 0f, 40f)     // bump now 200 m ahead
        assertEquals(2, spoken.size)
        assertTrue(spoken.last().contains("lombada à frente em 200 metros"))
    }

    @Test fun passedPointIsAnnouncedAgainOnTheNextPass() = runBlocking {
        val spoken = mutableListOf<String>()
        val engine = engine(spoken)
        engine.localAlertLookup = { _, _ ->
            listOf(feature("camera", Type.SPEED_CAMERA, north(300.0)))
        }
        engine.evaluate(-3.1000, -60.02, 0f, 50f)
        assertEquals(1, spoken.size)
        engine.evaluate(-3.1000, -60.02, 0f, 50f)
        assertEquals("same pass, not repeated", 1, spoken.size)
        // Drive on until the camera is 1.2 km behind, then come back.
        engine.evaluate(north(1500.0), -60.02, 0f, 50f)
        engine.evaluate(-3.1000, -60.02, 0f, 50f)
        assertEquals("next pass is announced", 2, spoken.size)
    }

    @Test fun onlineQueryOnlyRunsWhereNoPackIsInstalled() = runBlocking {
        var requests = 0
        val counting = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(503).message("Down").body("{}".toResponseBody()).build()
        }.build()

        val withPack = engine(mutableListOf(), counting)
        withPack.localAlertLookup = { _, _ -> emptyList() }   // pack installed, nothing here
        withPack.evaluate(-3.1, -60.02, 0f, 40f)
        assertEquals("a pack answers offline, no network", 0, requests)

        val withoutPack = engine(mutableListOf(), counting)
        withoutPack.evaluate(-3.1, -60.02, 0f, 40f)           // lookup returns null
        assertTrue("no pack yet: falls back to the online query", requests > 0)
    }

    @Test fun differentKindsNearEachOtherAreNotDuplicates() {
        val merged = SafetyAlertEngine.mergeAlerts(
            listOf(feature("toll", Type.TOLL_BOOTH, -3.1000)),
            listOf(feature("bump", Type.SPEED_BUMP, -3.10001),
                feature("camera", Type.SPEED_CAMERA, -3.10002),
                feature("camera2", Type.SPEED_CAMERA, -3.10003)))
        assertEquals(setOf("toll", "bump", "camera"), merged.map { it.id }.toSet())
    }

    @Test fun spokenDistancesAreRounded() {
        assertEquals(190, SafetyAlertEngine.speakableDistance(187))
        assertEquals(200, SafetyAlertEngine.speakableDistance(196))
        assertEquals(60, SafetyAlertEngine.speakableDistance(58))
        assertEquals("Atenção: passagem de nível à frente em 250 metros.",
            SafetyAlertEngine.buildAlertMessage(
                feature("x", Type.LEVEL_CROSSING, -3.1), 250))
    }
}
