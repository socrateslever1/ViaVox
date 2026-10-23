package com.example.viavox

import com.example.viavox.model.GeoReminder
import com.example.viavox.model.GeoReminderPolicy
import org.junit.Assert.*
import org.junit.Test

class GeoReminderPolicyTest {
    private val home = GeoReminder("1", "Comprar pão", -3.12, -60.02, 100)

    @Test fun createdAtLocationOnlyFiresAfterLeavingAndReturning() {
        val here = GeoReminderPolicy.evaluate(listOf(home), -3.12, -60.02, 6f)
        assertTrue(here.triggered.isEmpty())
        assertFalse(here.items.single().armed)

        val away = GeoReminderPolicy.evaluate(here.items, -3.1230, -60.02, 6f)
        assertTrue(away.triggered.isEmpty())
        assertTrue(away.items.single().armed)

        val back = GeoReminderPolicy.evaluate(away.items, -3.12, -60.02, 6f)
        assertEquals(listOf("1"), back.triggered.map { it.id })
        assertTrue(back.items.single().completed)

        val again = GeoReminderPolicy.evaluate(back.items, -3.12, -60.02, 6f)
        assertTrue(again.triggered.isEmpty())
    }

    @Test fun noisyGpsCannotArmOrTriggerReminder() {
        val noisyAway = GeoReminderPolicy.evaluate(listOf(home),
            -3.1230, -60.02, 100f)
        assertFalse(noisyAway.items.single().armed)
        val armed = home.copy(armed = true)
        assertTrue(GeoReminderPolicy.evaluate(listOf(armed),
            -3.12, -60.02, 90f).triggered.isEmpty())
    }

    @Test fun uncertaintyNearBoundaryDoesNotTrigger() {
        val armed = home.copy(armed = true)
        val result = GeoReminderPolicy.evaluate(listOf(armed),
            -3.11912, -60.02, 15f)
        assertTrue(result.triggered.isEmpty())
    }
}
