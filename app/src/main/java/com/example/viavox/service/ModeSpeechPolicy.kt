package com.example.viavox.service

import com.example.viavox.model.AssistanceMode
import com.example.viavox.model.ViaVoxConfig

/**
 * Checks the active mode again when the queued speech actually begins.
 * Changes to mode, POI preferences or Tour cannot leak old announcements.
 */
object ModeSpeechPolicy {
    fun canPlay(kind: SpeechKind, eventKey: String, config: ViaVoxConfig): Boolean {
        val mode = AssistanceMode.from(config.assistanceMode)
        return when (kind) {
            SpeechKind.POI -> mode == AssistanceMode.ACCESSIBILITY &&
                config.narratePoiEnabled && !config.tourEnabled
            SpeechKind.TOUR -> mode == AssistanceMode.ACCESSIBILITY &&
                (!eventKey.startsWith("tour:") || config.tourEnabled)
            else -> true
        }
    }
}
