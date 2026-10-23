package com.example.viavox

import com.example.viavox.service.SpeechKind
import com.example.viavox.service.VoiceAnnouncementPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAnnouncementPolicyTest {
    @Test fun manualVoiceRemainsAvailableWhenAutomationMuted() {
        assertTrue(VoiceAnnouncementPolicy.mayAnnounce(true, false, SpeechKind.USER_REQUEST))
        assertTrue(VoiceAnnouncementPolicy.mayPlay(true, false, SpeechKind.USER_REQUEST))
    }

    @Test fun mutedAutomationSuppressesAllUnsolicitedMessages() {
        val categories = listOf(SpeechKind.GEOGRAPHY, SpeechKind.TOUR,
            SpeechKind.POI, SpeechKind.NAVIGATION, SpeechKind.SAFETY)
        categories.forEach { kind ->
            assertFalse(VoiceAnnouncementPolicy.mayAnnounce(true, false, kind))
            assertFalse(VoiceAnnouncementPolicy.mayPlay(true, false, kind))
            assertTrue(VoiceAnnouncementPolicy.mayAnnounce(true, true, kind))
        }
    }

    @Test fun stoppedGpsDoesNotGenerateManualOrAutomaticSpeech() {
        SpeechKind.entries.forEach { kind ->
            assertFalse(VoiceAnnouncementPolicy.mayAnnounce(false, true, kind))
            assertFalse(VoiceAnnouncementPolicy.mayAnnounce(false, false, kind))
        }
    }
}
