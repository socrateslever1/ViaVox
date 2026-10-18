package com.example.viavox

import com.example.viavox.model.Place
import com.example.viavox.service.GeoFix
import com.example.viavox.service.RoadDetectionEngine
import com.example.viavox.service.SpeechFallbackRouter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class RoadDetectionEngineTest {

    @Test
    fun testNormalizeRoadName_abbreviationsExpanded() {
        assertEquals("Rua das Flores", RoadDetectionEngine.normalizeRoadName("R. das Flores"))
        assertEquals("Avenida Djalma Batista", RoadDetectionEngine.normalizeRoadName("Av. Djalma Batista"))
        assertEquals("Travessa Belém", RoadDetectionEngine.normalizeRoadName("Trav. Belém"))
        assertEquals("Alameda Cosme Ferreira", RoadDetectionEngine.normalizeRoadName("Al. Cosme Ferreira"))
        assertEquals("Rodovia AM-010", RoadDetectionEngine.normalizeRoadName("Rod. AM-010"))
    }

    @Test
    fun testNormalizeForComparison_ignoresAccentsAndCaseAndAbbreviations() {
        val s1 = RoadDetectionEngine.normalizeForComparison("Av. São Luís")
        val s2 = RoadDetectionEngine.normalizeForComparison("AVENIDA SAO LUIS")
        assertEquals(s1, s2)

        val r1 = RoadDetectionEngine.normalizeForComparison("R. Pará")
        val r2 = RoadDetectionEngine.normalizeForComparison("Rua Para")
        assertEquals(r1, r2)
    }

    // Scenario 1: Identificação da primeira rua com usuário parado
    @Test
    fun scenario01_firstStreetIdentifiedWhenStationary_announcesImmediately() = runTest(UnconfinedTestDispatcher()) {
        val announcements = mutableListOf<Pair<String, Boolean>>()
        val updatedPlaces = mutableListOf<Place>()

        val engine = RoadDetectionEngine(
            coroutineScope = backgroundScope,
            onPlaceUpdated = { updatedPlaces.add(it) },
            onRoadAnnounceNeeded = { msg, isFirst -> announcements.add(msg to isFirst) },
            onStatusMessage = {},
            reverseGeocodeProvider = { _, _ ->
                Result.success(Place(road = "Avenida Constantino Nery", neighbourhood = "Chapada"))
            }
        )

        // User is completely stationary (speedMps = 0f)
        engine.onGeoFixUpdate(
            GeoFix(latitude = -3.1025, longitude = -60.0235, accuracyMeters = 8f, speedMps = 0f, timestampMs = 1_000L)
        )

        assertEquals(1, announcements.size)
        assertEquals("Você está na Avenida Constantino Nery, bairro Chapada.", announcements[0].first)
        assertTrue(announcements[0].second)
        assertEquals("Avenida Constantino Nery", engine.getCurrentConfirmedRoad())
    }

    // Scenario 2: Identificação de rua ao iniciar o deslocamento
    @Test
    fun scenario02_streetIdentifiedUponStartingMovement_announcesCorrectly() = runTest(UnconfinedTestDispatcher()) {
        val announcements = mutableListOf<String>()
        var callCount = 0

        val engine = RoadDetectionEngine(
            coroutineScope = backgroundScope,
            onPlaceUpdated = {},
            onRoadAnnounceNeeded = { msg, _ -> announcements.add(msg) },
            onStatusMessage = {},
            reverseGeocodeProvider = { _, _ ->
                callCount++
                if (callCount == 1) {
                    // Initial stationary position inside parking lot had no street name
                    Result.success(Place(road = Place.UNKNOWN_ROAD, neighbourhood = "Adrianópolis"))
                } else {
                    // User starts moving onto Rua Paraíba
                    Result.success(Place(road = "Rua Paraíba", neighbourhood = "Adrianópolis"))
                }
            }
        )

        engine.onGeoFixUpdate(
            GeoFix(latitude = -3.1100, longitude = -60.0150, speedMps = 0f, timestampMs = 1_000L)
        )
        assertTrue(announcements.isEmpty())

        // User starts moving (speed = 4.5 m/s)
        engine.onGeoFixUpdate(
            GeoFix(latitude = -3.1102, longitude = -60.0152, speedMps = 4.5f, timestampMs = 10_000L)
        )
        assertEquals(1, announcements.size)
        assertEquals("Você está na Rua Paraíba, bairro Adrianópolis.", announcements[0])
    }

    // Scenario 3: Mudança confirmada de rua
    @Test
    fun scenario03_confirmedStreetChange_announcesNewStreet() = runTest(UnconfinedTestDispatcher()) {
        val announcements = mutableListOf<Pair<String, Boolean>>()
        var currentRoad = "Avenida Constantino Nery"
        var currentBairro = "Chapada"

        val engine = RoadDetectionEngine(
            coroutineScope = backgroundScope,
            onPlaceUpdated = {},
            onRoadAnnounceNeeded = { msg, isFirst -> announcements.add(msg to isFirst) },
            onStatusMessage = {},
            reverseGeocodeProvider = { _, _ ->
                Result.success(Place(road = currentRoad, neighbourhood = currentBairro))
            }
        )

        // Initial street
        engine.onGeoFixUpdate(
            GeoFix(latitude = -3.1025, longitude = -60.0235, speedMps = 5f, timestampMs = 1_000L)
        )
        assertEquals(1, announcements.size)
        assertTrue(announcements[0].second)

        // Enter Rua Pará - 1st reading (candidate started, not yet announced)
        currentRoad = "Rua Pará"
        currentBairro = "Nossa Senhora das Graças"
        engine.onGeoFixUpdate(
            GeoFix(latitude = -3.1030, longitude = -60.0235, speedMps = 6f, timestampMs = 10_000L)
        )
        assertEquals(1, announcements.size)

        // 2nd reading further along Rua Pará (>= 10m displacement) -> confirmed!
        engine.onGeoFixUpdate(
            GeoFix(latitude = -3.1032, longitude = -60.0235, speedMps = 6f, timestampMs = 16_000L)
        )
        assertEquals(2, announcements.size)
        assertEquals("Agora você está na Rua Pará, bairro Nossa Senhora das Graças.", announcements[1].first)
        assertFalse(announcements[1].second)
    }

    // Scenario 4: Oscilação de GPS sem mudança verdadeira
    @Test
    fun scenario04_gpsOscillationAtIntersection_doesNotTriggerFalseAnnouncement() = runTest(UnconfinedTestDispatcher()) {
        val announcements = mutableListOf<String>()
        var reportedRoad = "Avenida Djalma Batista"

        val engine = RoadDetectionEngine(
            coroutineScope = backgroundScope,
            onPlaceUpdated = {},
            onRoadAnnounceNeeded = { msg, _ -> announcements.add(msg) },
            onStatusMessage = {},
            reverseGeocodeProvider = { _, _ ->
                Result.success(Place(road = reportedRoad, neighbourhood = "Chapada"))
            }
        )

        // Confirmed on Av. Djalma Batista
        engine.onGeoFixUpdate(
            GeoFix(latitude = -3.1000, longitude = -60.0200, speedMps = 6f, timestampMs = 1_000L)
        )
        assertEquals(1, announcements.size)

        // Single GPS bounce onto crossing street "Rua João Valério" at intersection
        reportedRoad = "Rua João Valério"
        engine.onGeoFixUpdate(
            GeoFix(latitude = -3.1003, longitude = -60.0200, speedMps = 6f, timestampMs = 10_000L)
        )
        assertEquals("Single intersection bounce must not announce", 1, announcements.size)

        // Next GPS fix returns to Av. Djalma Batista
        reportedRoad = "Avenida Djalma Batista"
        engine.onGeoFixUpdate(
            GeoFix(latitude = -3.1006, longitude = -60.0200, speedMps = 6f, timestampMs = 18_000L)
        )
        assertEquals("Returning to original road must not announce", 1, announcements.size)

        // Poor GPS accuracy (> 70m) must also be ignored
        reportedRoad = "Rua Inexistente"
        engine.onGeoFixUpdate(
            GeoFix(latitude = -3.1010, longitude = -60.0200, accuracyMeters = 95f, speedMps = 6f, timestampMs = 28_000L)
        )
        assertEquals(1, announcements.size)
    }

    // Scenario 5: Permanência prolongada na mesma rua
    @Test
    fun scenario05_prolongedStayOnSameStreet_neverRepeatsAnnouncement() = runTest(UnconfinedTestDispatcher()) {
        val announcements = mutableListOf<String>()
        val variations = listOf(
            "Avenida Constantino Nery",
            "Av. Constantino Nery",
            "AVENIDA CONSTANTINO NERY",
            "Avenida Constantino Nery"
        )
        var idx = 0

        val engine = RoadDetectionEngine(
            coroutineScope = backgroundScope,
            onPlaceUpdated = {},
            onRoadAnnounceNeeded = { msg, _ -> announcements.add(msg) },
            onStatusMessage = {},
            reverseGeocodeProvider = { _, _ ->
                val road = variations[idx % variations.size]
                idx++
                Result.success(Place(road = road, neighbourhood = "Chapada"))
            }
        )

        var lat = -3.1000
        var time = 1_000L
        repeat(6) {
            engine.onGeoFixUpdate(
                GeoFix(latitude = lat, longitude = -60.0200, speedMps = 8f, timestampMs = time)
            )
            lat -= 0.0005 // ~55m per step
            time += 15_000L
        }

        assertEquals("Must announce only once despite staying on the street for a long time", 1, announcements.size)
    }

    // Scenario 6: Ausência de nome de rua
    @Test
    fun scenario06_missingRoadName_doesNotInventOrAnnounceStreet() = runTest(UnconfinedTestDispatcher()) {
        val announcements = mutableListOf<String>()

        val engine = RoadDetectionEngine(
            coroutineScope = backgroundScope,
            onPlaceUpdated = {},
            onRoadAnnounceNeeded = { msg, _ -> announcements.add(msg) },
            onStatusMessage = {},
            reverseGeocodeProvider = { _, _ ->
                Result.success(Place(road = "", neighbourhood = "Centro"))
            }
        )

        engine.onGeoFixUpdate(
            GeoFix(latitude = -3.1300, longitude = -60.0200, speedMps = 0f, timestampMs = 1_000L)
        )
        engine.onGeoFixUpdate(
            GeoFix(latitude = -3.1305, longitude = -60.0200, speedMps = 5f, timestampMs = 12_000L)
        )

        assertTrue("Must never announce when road name is missing", announcements.isEmpty())
    }

    // Scenario 7: Falha na conexão de dados
    @Test
    fun scenario07_networkConnectionFailure_reportsStatusAndRecoversWhenOnline() = runTest(UnconfinedTestDispatcher()) {
        val announcements = mutableListOf<String>()
        val statuses = mutableListOf<String>()
        var isOnline = false

        val engine = RoadDetectionEngine(
            coroutineScope = backgroundScope,
            onPlaceUpdated = {},
            onRoadAnnounceNeeded = { msg, _ -> announcements.add(msg) },
            onStatusMessage = { statuses.add(it) },
            reverseGeocodeProvider = { _, _ ->
                if (!isOnline) {
                    Result.failure(IOException("Network unreachable"))
                } else {
                    Result.success(Place(road = "Avenida Brasil", neighbourhood = "Compensa"))
                }
            }
        )

        // 1st attempt fails due to no network
        engine.onGeoFixUpdate(
            GeoFix(latitude = -3.1100, longitude = -60.0500, timestampMs = 1_000L)
        )
        assertTrue(announcements.isEmpty())
        assertTrue(statuses.any { it.contains("Sem conexão") })

        // Connection restored 4 seconds later -> recovers and announces street!
        isOnline = true
        engine.onGeoFixUpdate(
            GeoFix(latitude = -3.1100, longitude = -60.0500, timestampMs = 5_000L)
        )
        assertEquals(1, announcements.size)
        assertEquals("Você está na Avenida Brasil, bairro Compensa.", announcements[0])
    }

    // Scenario 8 & 9: Indisponibilidade da voz Microsoft e Funcionamento da voz Android como fallback
    @Test
    fun scenario08_and_09_microsoftVoiceUnavailable_fallsBackToAndroidTts() {
        var primaryAttempted = false
        val fallbackSpoken = mutableListOf<String>()

        val router = SpeechFallbackRouter(
            isVoiceEnabled = true,
            franciscaEndpoint = "https://broker.exemplo.com/tts",
            primarySpeak = { _, _, onUnavailable ->
                primaryAttempted = true
                // Simulate Microsoft Francisca service failure/timeout
                onUnavailable()
            },
            fallbackSpeak = { text ->
                fallbackSpoken.add(text)
            },
            stopAll = {}
        )

        val accepted = router.speak("Você está na Avenida Constantino Nery.")
        assertTrue(accepted)
        assertTrue("Primary Microsoft Francisca voice should be attempted first", primaryAttempted)
        assertEquals("Android TTS fallback must speak the street when Francisca fails", 1, fallbackSpoken.size)
        assertEquals("Você está na Avenida Constantino Nery.", fallbackSpoken[0])
    }

    // Scenario 10: Desativação e reativação da narração
    @Test
    fun scenario10_disableAndReenableNarration_respectsUserPreference() {
        val spokenMessages = mutableListOf<String>()
        var stopCount = 0

        val router = SpeechFallbackRouter(
            isVoiceEnabled = true,
            franciscaEndpoint = null,
            primarySpeak = { _, _, onUnavailable -> onUnavailable() },
            fallbackSpeak = { text -> spokenMessages.add(text) },
            stopAll = { stopCount++ }
        )

        // Disable narration -> stops active speech and suppresses new announcements
        router.setVoiceEnabledState(false)
        assertEquals(1, stopCount)

        val spokenWhenDisabled = router.speak("Você está na Rua Pará.", nowMs = 1_000L)
        assertFalse(spokenWhenDisabled)
        assertTrue(spokenMessages.isEmpty())

        // Re-enable narration -> subsequent announcements are spoken normally
        router.setVoiceEnabledState(true)
        val spokenWhenEnabled = router.speak("Agora você está na Rua Paraíba.", nowMs = 20_000L)
        assertTrue(spokenWhenEnabled)
        assertEquals(1, spokenMessages.size)
        assertEquals("Agora você está na Rua Paraíba.", spokenMessages[0])
    }
}
