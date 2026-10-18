package com.example.viavox

import com.example.viavox.service.SpeechFallbackRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechFallbackRouterTest {
    @Test
    fun androidVoiceIsDefaultWhenNoOnlineEndpointIsConfigured() {
        val spoken = mutableListOf<String>()
        val router = SpeechFallbackRouter(
            primarySpeak = { _, _, _ -> error("Cloud voice must be opt-in") },
            fallbackSpeak = { spoken.add(it) },
            stopAll = {}
        )
        assertTrue(router.speak("Você está na Rua Pará.", nowMs = 1_000L))
        assertEquals(listOf("Você está na Rua Pará."), spoken)
    }

    @Test
    fun franciscaBrokerFailureFallsBackToInstalledAndroidVoice() {
        val events = mutableListOf<String>()
        val router = SpeechFallbackRouter(
            franciscaEndpoint = "https://example.org/tts",
            primarySpeak = { _, endpoint, onUnavailable ->
                assertEquals("https://example.org/tts", endpoint)
                events.add("online")
                onUnavailable()
            },
            fallbackSpeak = { events.add("android") },
            stopAll = {}
        )
        assertTrue(router.speak("Agora você está na Rua Pará.", nowMs = 20_000L))
        assertEquals(listOf("online", "android"), events)
    }

    @Test
    fun disabledVoiceIsSilentAndForcedRepeatWorks() {
        var spoken = 0
        val router = SpeechFallbackRouter(
            primarySpeak = { _, _, _ -> error("unexpected") },
            fallbackSpeak = { spoken++ },
            stopAll = {}
        )
        assertTrue(router.speak("Rua Pará", nowMs = 12_000L))
        assertFalse(router.speak("Rua Pará", nowMs = 14_000L))
        assertTrue(router.speak("Rua Pará", forceRepeat = true, nowMs = 14_001L))
        router.setVoiceEnabledState(false)
        assertFalse(router.speak("Rua Nova", nowMs = 19_000L))
        assertEquals(2, spoken)
    }
}
