package com.example.viavox

import com.example.viavox.model.ViaVoxConfig
import com.example.viavox.service.ModeSpeechPolicy
import com.example.viavox.service.SpeechKind
import com.example.viavox.service.VoiceAnnouncementPolicy
import org.junit.Assert.*
import org.junit.Test

class ModeSpeechPolicyTest {
    @Test fun changingModeSuppressesQueuedTourAndPoi() {
        val accessibility = ViaVoxConfig(assistanceMode = "acessivel", tourEnabled = true)
        val explorer = accessibility.copy(assistanceMode = "explorador")
        assertTrue(ModeSpeechPolicy.canPlay(SpeechKind.TOUR, "tour:historical", accessibility))
        assertFalse(ModeSpeechPolicy.canPlay(SpeechKind.TOUR, "tour:historical", explorer))
        assertFalse(ModeSpeechPolicy.canPlay(SpeechKind.TOUR, "junction:42", explorer))
        assertFalse(ModeSpeechPolicy.canPlay(SpeechKind.POI, "poi:school", explorer))
    }

    @Test fun disabledTourSuppressesTourOnlyWithoutDisablingJunctions() {
        val config = ViaVoxConfig(assistanceMode = "acessivel", tourEnabled = false)
        assertFalse(ModeSpeechPolicy.canPlay(SpeechKind.TOUR, "tour:building", config))
        assertTrue(ModeSpeechPolicy.canPlay(SpeechKind.TOUR, "junction:42", config))
        assertTrue(ModeSpeechPolicy.canPlay(SpeechKind.POI, "poi:hospital", config))
    }

    @Test fun strictAutomaticPoiRespectsTourAndPoiSwitch() {
        val base = ViaVoxConfig(assistanceMode = "acessivel", narratePoiEnabled = true)
        assertTrue(ModeSpeechPolicy.canPlay(SpeechKind.POI, "poi:1", base))
        assertFalse(ModeSpeechPolicy.canPlay(SpeechKind.POI, "poi:1",
            base.copy(narratePoiEnabled = false)))
        assertFalse(ModeSpeechPolicy.canPlay(SpeechKind.POI, "poi:1",
            base.copy(tourEnabled = true)))
    }

    @Test fun geofenceVoiceIsAutomaticButManualSpeechIsNot() {
        assertTrue(VoiceAnnouncementPolicy.mayAnnounce(true,false,SpeechKind.USER_REQUEST))
        assertFalse(VoiceAnnouncementPolicy.mayAnnounce(true,false,SpeechKind.REMINDER))
        assertTrue(VoiceAnnouncementPolicy.mayAnnounce(true,true,SpeechKind.REMINDER))
    }
}
