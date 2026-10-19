package com.example.viavox

import com.example.viavox.service.BackgroundSessionPolicy
import org.junit.Assert.*
import org.junit.Test

class BackgroundSessionPolicyTest {
    @Test fun navigationOrRecordingMaySurviveHiddenActivity() {
        assertTrue(BackgroundSessionPolicy.keepAlive(true,false,false))
        assertTrue(BackgroundSessionPolicy.keepAlive(false,true,false))
        assertTrue(BackgroundSessionPolicy.keepAlive(false,false,true))
        assertFalse(BackgroundSessionPolicy.keepAlive(false,false,false))
    }

    @Test fun restartRejectsOrdinaryMapOnlyOrManuallyPausedGps() {
        assertFalse(BackgroundSessionPolicy.mayRestore(false,false,false,false))
        assertFalse(BackgroundSessionPolicy.mayRestore(true,true,true,true))
        assertTrue(BackgroundSessionPolicy.mayRestore(true,false,false,false))
        assertTrue(BackgroundSessionPolicy.mayRestore(false,true,false,false))
    }
}
