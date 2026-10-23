package com.example.viavox.service

/**
 * Manual requests must remain available when automatic geographic narration
 * is disabled. Tracking, however, always requires explicit activation.
 */
object VoiceAnnouncementPolicy {
    fun mayAnnounce(
        trackingActive: Boolean,
        automaticNarrationEnabled: Boolean,
        kind: SpeechKind
    ): Boolean =
        trackingActive && (kind == SpeechKind.USER_REQUEST || automaticNarrationEnabled)

    /** A queued automatic event must be rechecked before playback. */
    fun mayPlay(
        trackingActive: Boolean,
        automaticNarrationEnabled: Boolean,
        kind: SpeechKind
    ): Boolean = mayAnnounce(trackingActive, automaticNarrationEnabled, kind)
}
