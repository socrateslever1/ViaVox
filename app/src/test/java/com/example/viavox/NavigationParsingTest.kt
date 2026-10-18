package com.example.viavox

import com.example.viavox.data.NavigationNetworkService
import com.example.viavox.model.MapPlace
import com.example.viavox.model.NavigationMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class NavigationParsingTest {
    @Test
    fun parsesRealPhotonFeaturesWithLonLatOrder() {
        val data = """
            {"features":[{"properties":{"name":"Posto Gaspetrô","city":"Manaus","street":"BR-174"},
              "geometry":{"type":"Point","coordinates":[-59.98,-3.09]}}]}
        """.trimIndent()
        val places = NavigationNetworkService.parsePhoton(data)
        assertEquals(1,places.size)
        assertEquals("Posto Gaspetrô",places.first().name)
        assertEquals(-3.09,places.first().latitude,0.00001)
        assertEquals(-59.98,places.first().longitude,0.00001)
    }

    @Test
    fun parsesRoadPolylineAndTurnInstructionsWithoutDrawingStraightLine() {
        val body = """
        {"code":"Ok","routes":[{
          "distance":1920.0,"duration":305.0,
          "geometry":{"type":"LineString","coordinates":[[-60.01,-3.10],[-60.00,-3.11],[-59.99,-3.12]]},
          "legs":[{"steps":[
            {"name":"Rua Pará","distance":200,"maneuver":{"type":"depart","location":[-60.01,-3.10]}},
            {"name":"Avenida Brasil","distance":480,"maneuver":{"type":"turn","modifier":"right","location":[-60.00,-3.11]}},
            {"name":"","distance":0,"maneuver":{"type":"arrive","location":[-59.99,-3.12]}}
          ]}]
        }]}
        """.trimIndent()
        val target = MapPlace("Destino",-3.12,-59.99)
        val route = NavigationNetworkService.parseOsrm(body,target)
        assertEquals(3,route.coordinates.size)
        assertEquals(-3.11,route.coordinates[1].first,0.00001)
        assertEquals("Vire à direita na Avenida Brasil",route.steps[1].instruction)
        assertEquals("Você chegou ao destino",route.steps.last().instruction)
    }

    @Test
    fun failureCannotInventRoute() {
        var failed = false
        try {
            NavigationNetworkService.parseOsrm("""{"code":"NoRoute","routes":[]}""",MapPlace("X",-3.1,-60.0))
        } catch (_: IllegalArgumentException) { failed = true }
        assertTrue(failed)
    }

    @Test
    fun distanceToPolylineDetectsOffRoutePositions() {
        val poly = listOf(-3.100 to -60.000,-3.110 to -60.000,-3.120 to -60.000)
        assertTrue(NavigationMath.distanceToPolylineMeters(-3.105,-60.000,poly) < 5)
        assertTrue(NavigationMath.distanceToPolylineMeters(-3.105,-59.995,poly) > 450)
        assertFalse(NavigationMath.meters(-3.10,-60.0,-3.11,-60.0) < 100)
    }

    @Test
    fun directionsHavePortugueseLabels() {
        assertEquals("Entre na rotatória na Av. Brasil",
            NavigationNetworkService.maneuverText("roundabout","right","Av. Brasil"))
        assertEquals("Faça o retorno",
            NavigationNetworkService.maneuverText("turn","uturn",""))
    }
}
