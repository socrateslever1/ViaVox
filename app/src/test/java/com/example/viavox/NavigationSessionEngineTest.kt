package com.example.viavox

import com.example.viavox.model.MapPlace
import com.example.viavox.model.NavigationRoute
import com.example.viavox.model.RouteStep
import com.example.viavox.service.NavigationSessionEngine
import org.junit.Assert.*
import org.junit.Test

class NavigationSessionEngineTest {
    private fun route() = NavigationRoute(
        destination = MapPlace("Hospital",-3.1040,-60.0000),
        coordinates = listOf(
            -3.1000 to -60.0000,
            -3.1020 to -60.0000,
            -3.1040 to -60.0000
        ),
        distanceMeters = 445.0,
        durationSeconds = 65.0,
        steps = listOf(
            RouteStep(-3.1000,-60.0000,"Siga em frente",200.0),
            RouteStep(-3.1020,-60.0000,"Vire à esquerda",220.0),
            RouteStep(-3.1040,-60.0000,"Você chegou ao destino",0.0)
        )
    )

    @Test fun routeAnnouncementsAdvanceWithoutDoubleSpeaking() {
        val session = NavigationSessionEngine(route())
        val first = session.update(-3.1000,-60.0000,9f,26f,10_000L)!!
        assertTrue(first.state.navigating)
        assertEquals(1,first.speech.size)
        assertTrue(first.speech.single().startsWith("Em "))
        val repeat = session.update(-3.1000,-60.0000,9f,26f,11_000L)!!
        assertTrue(repeat.speech.isEmpty())
        val turn = session.update(-3.1017,-60.0000,9f,26f,20_000L)!!
        assertEquals(1,turn.speech.size)
        assertTrue(turn.speech.single().contains("Vire à esquerda"))
        val again = session.update(-3.1017,-60.0000,9f,26f,22_000L)!!
        assertTrue(again.speech.isEmpty())
    }

    @Test fun badGpsDoesNotMoveInstructionsOrTriggerReroute() {
        val session = NavigationSessionEngine(route())
        val untrusted = session.update(-3.1000,-59.9970,70f,20f,5_000L)
        assertNull(untrusted)
        val valid = session.update(-3.1000,-60.0000,7f,20f,6_000L)!!
        assertFalse(valid.requestReroute)
    }

    @Test fun reroutesOnlyAfterThreeBadFixesWithCooldown() {
        val session = NavigationSessionEngine(route())
        val lon = -59.9988
        val a = session.update(-3.1000,lon,8f,20f,10_000L)!!
        val b = session.update(-3.1001,lon,8f,20f,14_000L)!!
        val c = session.update(-3.1002,lon,8f,20f,18_000L)!!
        assertFalse(a.requestReroute)
        assertFalse(b.requestReroute)
        assertTrue(c.requestReroute)
        val d = session.update(-3.1003,lon,8f,20f,22_000L)!!
        assertFalse(d.requestReroute)
    }

    @Test fun arrivalStopsNavigationAndOnlySpeaksOnce() {
        val session = NavigationSessionEngine(route())
        val close = session.update(-3.10388,-60.0000,8f,12f,30_000L)!!
        assertTrue(close.arrived)
        assertFalse(close.state.navigating)
        assertEquals(listOf("Você chegou ao destino."),close.speech)
        assertNull(session.update(-3.10390,-60.0000,8f,10f,31_000L))
    }

    @Test fun replacingRouteResetsInstructionState() {
        val session = NavigationSessionEngine(route())
        session.update(-3.1000,-60.0000,9f,26f,10_000L)
        session.replaceRoute(route())
        val restarted = session.update(-3.1000,-60.0000,9f,26f,20_000L)!!
        assertEquals(1,restarted.speech.size)
    }
}
