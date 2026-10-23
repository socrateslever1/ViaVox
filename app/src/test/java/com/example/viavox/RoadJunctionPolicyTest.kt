package com.example.viavox

import com.example.viavox.model.NamedRoadSegment
import com.example.viavox.model.RoadGraphNode
import com.example.viavox.model.RoadJunctionPolicy
import com.example.viavox.model.ExternalMapLinks
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class RoadJunctionPolicyTest {
    private val south = RoadGraphNode(1, -3.1205, -60.0100)
    private val junction = RoadGraphNode(2, -3.1194, -60.0100)
    private val north = RoadGraphNode(3, -3.1188, -60.0100)
    private val west = RoadGraphNode(4, -3.1194, -60.0106)
    private val east = RoadGraphNode(5, -3.1194, -60.0094)
    private val road = NamedRoadSegment(10, "Avenida Central", listOf(south, junction, north))
    private val cross = NamedRoadSegment(20, "Rua das Flores", listOf(west, junction, east))

    @Test fun realCommonNodeProducesAheadCrossing() {
        val found = RoadJunctionPolicy.findAhead(
            listOf(road, cross), "Av. Central", -3.1200, -60.0100, 0f, 6f, 150)
        assertEquals(1, found.size)
        assertEquals("Rua das Flores", found.first().otherRoads.single())
        assertTrue(found.first().hasRight)
        assertTrue(found.first().hasLeft)
        assertTrue(found.first().continuesAhead)
        assertTrue(RoadJunctionPolicy.describe(found.first()).contains("cruzamento"))
    }

    @Test fun coincidentCoordinatesWithoutSharedNodeIsNotACrossing() {
        val separate = NamedRoadSegment(21, "Rua das Flores",
            listOf(west, junction.copy(id = 99), east))
        assertTrue(RoadJunctionPolicy.findAhead(
            listOf(road, separate), "Avenida Central",
            -3.1200, -60.0100, 0f, 6f, 150).isEmpty())
    }

    @Test fun noHeadingOrBadGpsSuppressesClaims() {
        assertTrue(RoadJunctionPolicy.findAhead(
            listOf(road, cross), "Avenida Central",
            -3.1200, -60.0100, null, 6f, 150).isEmpty())
        assertTrue(RoadJunctionPolicy.findAhead(
            listOf(road, cross), "Avenida Central",
            -3.1200, -60.0100, 0f, 35f, 150).isEmpty())
    }

    @Test fun offRoadOrHeadingAwaySuppressesClaims() {
        assertTrue(RoadJunctionPolicy.findAhead(
            listOf(road, cross), "Avenida Central",
            -3.1200, -60.0120, 0f, 6f, 150).isEmpty())
        assertTrue(RoadJunctionPolicy.findAhead(
            listOf(road, cross), "Avenida Central",
            -3.1200, -60.0100, 180f, 6f, 150).isEmpty())
    }
}

class ExternalMapLinksTest {
    @Test fun makesLocaleIndependentLinks() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertTrue(ExternalMapLinks.googleMaps(-3.119, -60.022)
                .endsWith("query=-3.119000,-60.022000"))
            assertTrue(ExternalMapLinks.waze(-3.119, -60.022)
                .contains("ll=-3.119000%2C-60.022000&navigate=no"))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test fun doesNotClaimInactiveOrUnknownCoordinates() {
        assertEquals("https://www.google.com/maps", ExternalMapLinks.googleMaps(null, null))
        assertEquals("https://www.waze.com/live-map", ExternalMapLinks.waze(200.0, 100.0))
    }
}
