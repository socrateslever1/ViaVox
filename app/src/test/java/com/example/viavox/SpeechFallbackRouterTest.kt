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
            localSpeak = { spoken.add(it) },
            stopAll = {}
        )
        assertTrue(router.speak("Você está na Rua Pará.", nowMs = 1_000L))
        assertEquals(listOf("Você está na Rua Pará."), spoken)
    }

    @Test
    fun disabledVoiceIsSilentAndForcedRepeatWorks() {
        var spoken = 0
        val router = SpeechFallbackRouter(
            localSpeak = { spoken++ },
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
