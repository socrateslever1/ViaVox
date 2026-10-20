package com.example.viavox

import com.example.viavox.model.MapPlace
import com.example.viavox.model.NavigationCameraMath
import com.example.viavox.model.NavigationMath
import com.example.viavox.model.NavigationRoute
import com.example.viavox.model.RouteProgressEngine
import com.example.viavox.model.RouteStep
import org.junit.Assert.*
import org.junit.Test

class NavigationCameraAndProgressTest {
    @Test fun headingNorthLooksAheadAndMovesPuckBelowCenter() {
        val target = NavigationCameraMath.cameraTarget(-3.10,-60.00,0.0,80.0)
        assertTrue(target.first > -3.10)
        assertEquals(-60.00,target.second,0.000001)
        assertEquals(18.35,NavigationCameraMath.followZoom(16f),0.001)
    }

    @Test fun headingEastLooksAheadInLongitudeAndWrapsCourseSmoothly() {
        val target = NavigationCameraMath.cameraTarget(-3.10,-60.0,90.0,85.0)
        assertTrue(target.second > -60.0)
        assertEquals(-3.10,target.first,0.000001)
        assertEquals(20.0,NavigationCameraMath.angularDelta(350.0,10.0),0.00001)
        assertEquals(-20.0,NavigationCameraMath.angularDelta(10.0,350.0),0.00001)
        assertEquals(1.0,NavigationCameraMath.smoothBearing(350.0,10.0,0.55),0.00001)
    }

    @Test fun closeGuidanceDoesNotLeaveVehicleAtCityWideZoom() {
        assertEquals(18.35,NavigationCameraMath.followZoom(0f),0.001)
        assertEquals(18.05,NavigationCameraMath.followZoom(35f),0.001)
        assertEquals(17.65,NavigationCameraMath.followZoom(65f),0.001)
        assertEquals(17.3,NavigationCameraMath.followZoom(105f),0.001)
        assertEquals(17.9,NavigationCameraMath.closeAfterPreview(13.6,false),0.001)
        assertEquals(17.9,NavigationCameraMath.closeAfterPreview(15.0,true),0.001)
        assertEquals(17.55,NavigationCameraMath.closeAfterPreview(17.55,false),0.001)
        assertTrue(NavigationCameraMath.lookAheadMeters(20f) < 55.0)
    }

    private fun sampleRoute() = NavigationRoute(
        destination = MapPlace("Destino",-3.104,-60.002),
        coordinates = listOf(
            -3.100 to -60.000,
            -3.101 to -60.000,
            -3.102 to -60.000,
            -3.102 to -60.001,
            -3.103 to -60.001,
            -3.104 to -60.002
        ),
        distanceMeters = 720.0,
        durationSeconds = 90.0,
        steps = listOf(
            RouteStep(-3.100,-60.000,"Siga em frente",100.0),
            RouteStep(-3.102,-60.000,"Vire à direita na Rua A",200.0),
            RouteStep(-3.103,-60.001,"Vire à esquerda na Rua B",180.0),
            RouteStep(-3.104,-60.002,"Você chegou ao destino",0.0)
        )
    )

    @Test fun guidanceProgressMovesAlongRouteAndNeverRegresses() {
        val engine = RouteProgressEngine(sampleRoute())
        val start = engine.update(-3.100,-60.000,20f,10000L)!!
        assertEquals(1,start.nextStepIndex)
        assertTrue(start.metersToStep > 180.0)
        val middle = engine.update(-3.1018,-60.000,30f,20000L)!!
        assertEquals(1,middle.nextStepIndex)
        assertTrue(middle.metersToStep < start.metersToStep)
        val afterTurn = engine.update(-3.102,-60.0009,30f,30000L)!!
        assertEquals(2,afterTurn.nextStepIndex)
        assertTrue(afterTurn.traveledMeters > middle.traveledMeters)
        val noisyBacktrack = engine.update(-3.1019,-60.0000,30f,31000L)!!
        assertEquals(2,noisyBacktrack.nextStepIndex)
    }

    @Test fun routeProgressIsNotStraightLineDistanceToNextManeuver() {
        val route = sampleRoute()
        val engine = RouteProgressEngine(route)
        val current = engine.update(-3.102,-60.000,18f,10000L)!!
        assertEquals(1,current.nextStepIndex)
        assertTrue(current.metersToStep < 15)
        val advanced = engine.update(-3.102,-60.0007,18f,20000L)!!
        assertEquals(2,advanced.nextStepIndex)
        assertTrue(advanced.metersToStep > 100)
        assertTrue(advanced.metersRemaining > 0)
    }
}
