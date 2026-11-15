package com.example.viavox

import com.example.viavox.data.NavigationNetworkService
import com.example.viavox.model.MapPlace
import com.example.viavox.model.NavigationRoute
import com.example.viavox.model.RouteStep
import com.example.viavox.service.NavigationSessionEngine
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class NavigationGuidanceTest {
    /** Straight north-south road, 111 m per 0.001 degree of latitude. */
    private fun route(steps:List<RouteStep>, destination:MapPlace =
        MapPlace("Destino",-3.1100,-60.0000)) = NavigationRoute(
        destination = destination,
        coordinates = listOf(-3.1000 to -60.0000,-3.1050 to -60.0000,-3.1100 to -60.0000),
        distanceMeters = 1112.0, durationSeconds = 120.0, steps = steps
    )

    private val twoTurns = listOf(
        RouteStep(-3.1000,-60.0000,"Siga em frente",550.0),
        RouteStep(-3.1050,-60.0000,"Vire à direita na Rua A",80.0),
        RouteStep(-3.1057,-60.0000,"Vire à esquerda na Rua B",480.0),
        RouteStep(-3.1100,-60.0000,"Você chegou ao destino",0.0)
    )

    @Test fun fastTrafficIsWarnedEarlierThanSlowTraffic() {
        // 556 m before the turn: too early at 30 km/h, on time at 90 km/h.
        val slow = NavigationSessionEngine(route(twoTurns))
        assertTrue(slow.update(-3.1000,-60.0000,6f,30f,1_000L)!!.speech.isEmpty())
        val fast = NavigationSessionEngine(route(twoTurns))
        val speech = fast.update(-3.1000,-60.0000,6f,90f,1_000L)!!.speech
        assertEquals(1,speech.size)
        assertTrue(speech.single().startsWith("Em "))
    }

    @Test fun closeManeuversAreAnnouncedTogether() {
        val session = NavigationSessionEngine(route(twoTurns))
        session.update(-3.1040,-60.0000,6f,30f,1_000L)
        val turn = session.update(-3.1047,-60.0000,6f,30f,10_000L)!!.speech
        assertEquals(1,turn.size)
        assertTrue(turn.single().contains("Vire à direita na Rua A"))
        assertTrue(turn.single().contains("Em seguida, vire à esquerda na Rua B"))
    }

    @Test fun walkingGetsShortDistancesAndCloserArrival() {
        val session = NavigationSessionEngine(route(twoTurns),walking = true)
        // 100 m before the turn at walking pace: nothing yet.
        assertTrue(session.update(-3.1041,-60.0000,6f,4.5f,1_000L)!!.speech.isEmpty())
        val ahead = session.update(-3.1045,-60.0000,6f,4.5f,40_000L)!!.speech
        assertEquals(1,ahead.size)
        assertTrue(ahead.single().startsWith("Em "))
        val now = session.update(-3.1049,-60.0000,6f,4.5f,80_000L)!!.speech
        assertTrue(now.single().startsWith("Vire à direita na Rua A"))
        // 30 m from the destination is arrival by car, not yet on foot.
        val car = NavigationSessionEngine(route(twoTurns))
        assertTrue(car.update(-3.10973,-60.0000,6f,10f,1_000L)!!.arrived)
        val foot = NavigationSessionEngine(route(twoTurns),walking = true)
        assertFalse(foot.update(-3.10973,-60.0000,6f,4f,1_000L)!!.arrived)
    }

    @Test fun arrivalSaysWhichSideTheDestinationIsOn() {
        // Travelling south: a point to the west is on the right-hand side.
        val right = NavigationSessionEngine(route(twoTurns,
            MapPlace("Loja",-3.1100,-60.00015)))
        assertEquals(listOf("Você chegou ao destino, à direita."),
            right.update(-3.1099,-60.0000,6f,10f,1_000L)!!.speech)
        val left = NavigationSessionEngine(route(twoTurns,
            MapPlace("Loja",-3.1100,-59.99985)))
        assertEquals(listOf("Você chegou ao destino, à esquerda."),
            left.update(-3.1099,-60.0000,6f,10f,1_000L)!!.speech)
    }

    @Test fun leavingTheRouteTriggersRerouteWithinThreeFixes() {
        val session = NavigationSessionEngine(route(twoTurns))
        val lon = -59.9993 // about 78 m off the route
        assertFalse(session.update(-3.1010,lon,8f,30f,1_000L)!!.requestReroute)
        assertFalse(session.update(-3.1011,lon,8f,30f,2_500L)!!.requestReroute)
        assertTrue(session.update(-3.1012,lon,8f,30f,4_000L)!!.requestReroute)
    }

    private val osrmBody = """{"code":"Ok","routes":[{"distance":120.0,"duration":90.0,
        "geometry":{"coordinates":[[-60.0,-3.1],[-60.0,-3.101]]},
        "legs":[{"steps":[{"name":"Rua A","distance":120.0,
        "maneuver":{"type":"depart","location":[-60.0,-3.1]}}]}]}]}"""

    private fun service(requests:MutableList<String>, failHost:String? = null) =
        NavigationNetworkService(http = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url
            requests += url.toString()
            val failed = failHost != null && url.host == failHost
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(if (failed) 503 else 200).message(if (failed) "Down" else "OK")
                .body((if (failed) "{}" else osrmBody).toResponseBody()).build()
        }.build())

    @Test fun walkingRoutesNeverUseTheCarProfile() = runBlocking {
        val requests = mutableListOf<String>()
        val place = MapPlace("Destino",-3.101,-60.0)
        service(requests).route(-3.1,-60.0,place,walking = true).getOrThrow()
        assertEquals(1,requests.size)
        assertTrue(requests.single().contains("routed-foot/route/v1/foot"))
        requests.clear()
        service(requests).route(-3.1,-60.0,place).getOrThrow()
        assertTrue(requests.single().contains("/route/v1/driving"))
        assertFalse(requests.single().contains("foot"))
    }

    @Test fun carRoutingFallsBackToSecondServer() = runBlocking {
        val requests = mutableListOf<String>()
        val result = service(requests,"router.project-osrm.org")
            .route(-3.1,-60.0,MapPlace("Destino",-3.101,-60.0))
        assertTrue(result.isSuccess)
        assertEquals(2,requests.size)
        assertTrue(requests.last().contains("routed-car"))
    }
}
