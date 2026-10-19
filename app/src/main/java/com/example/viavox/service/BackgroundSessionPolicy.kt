package com.example.viavox.service

/**
 * Only user-requested journeys may outlive the visible activity.
 * Foreground service restarts remain subject to Android/OEM restrictions.
 */
object BackgroundSessionPolicy {
    fun keepAlive(navigation: Boolean, recording: Boolean, backgroundVoice: Boolean): Boolean =
        navigation || recording || backgroundVoice

    fun mayRestore(
        hasSavedNavigation: Boolean,
        hasUnfinishedRecording: Boolean,
        backgroundVoice: Boolean,
        manuallyPaused: Boolean
    ): Boolean = !manuallyPaused && keepAlive(
        hasSavedNavigation,hasUnfinishedRecording,backgroundVoice
    )
}
