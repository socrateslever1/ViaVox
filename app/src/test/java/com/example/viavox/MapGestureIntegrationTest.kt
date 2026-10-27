package com.example.viavox

import com.example.viavox.ui.components.MapGestureCamera
import com.example.viavox.ui.components.MapGestureMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real screen-space contract: map content must follow the fingers, not invert. */
class MapGestureIntegrationTest {
    private val w = 1080.0
    private val h = 1920.0
    private val origin = MapGestureCamera(-3.099, -60.020, 17.5, 0.0)

    private fun move(
        old: MapGestureCamera = origin,
        x: Double = 540.0, y: Double = 960.0,
        dx: Double = 0.0, dy: Double = 0.0,
        zoom: Double = 1.0, rotation: Double = 0.0
    ) = MapGestureMath.transform(
        old=old, width=w, height=h,
        centroidX=x, centroidY=y, panX=dx, panY=dy,
        zoomFactor=zoom, rotationDegrees=rotation,
        minimumZoom=4.0, maximumZoom=19.0
    )

    private fun assertMapPointMoved(
        camera: MapGestureCamera,
        lat: Double = origin.latitude,
        lon: Double = origin.longitude,
        x: Double, y: Double
    ) {
        val at = MapGestureMath.toScreen(camera, lat, lon, w, h)
        assertEquals(x, at.first, 0.02)
        assertEquals(y, at.second, 0.02)
    }

    @Test fun oneFingerRightMovesMapRightAndCameraWest() {
        val next = move(x=660.0, y=960.0, dx=120.0)
        assertTrue(next.longitude < origin.longitude)
        assertMapPointMoved(next, x=660.0, y=960.0)
    }

    @Test fun oneFingerUpMovesMapUpAndCameraSouth() {
        val next=move(x=540.0, y=880.0, dy=-80.0)
        assertTrue(next.latitude < origin.latitude)
        assertMapPointMoved(next,x=540.0,y=880.0)
    }

    @Test fun clockwiseFingerTwistRotatesVisibleMapClockwise() {
        val east = MapGestureMath.atScreen(origin, 700.0, 960.0, w, h)
        val next=move(rotation=90.0)
        assertEquals(270.0,next.bearing,0.00001)
        assertMapPointMoved(next,east.first,east.second,x=540.0,y=1120.0)
    }

    @Test fun counterclockwiseFingerTwistRotatesVisibleMapCounterclockwise() {
        val east=MapGestureMath.atScreen(origin,700.0,960.0,w,h)
        val next=move(rotation=-90.0)
        assertEquals(90.0,next.bearing,0.00001)
        assertMapPointMoved(next,east.first,east.second,x=540.0,y=800.0)
    }

    @Test fun pinchDoesNotSlideMapAwayFromFingerCentroid() {
        val anchor=MapGestureMath.atScreen(origin,780.0,640.0,w,h)
        val next=move(x=780.0,y=640.0,zoom=1.7)
        assertTrue(next.zoom > origin.zoom)
        assertMapPointMoved(next,anchor.first,anchor.second,x=780.0,y=640.0)
    }

    @Test fun simultaneousPanPinchAndTwistKeepsGeographicAnchorAtFingerLocation() {
        // Compose supplies the *new* centroid and pan=current-previous.
        val anchor=MapGestureMath.atScreen(origin,715.0,780.0,w,h)
        val next=move(x=740.0,y=750.0,dx=25.0,dy=-30.0,zoom=1.33,rotation=33.0)
        assertMapPointMoved(next,anchor.first,anchor.second,x=740.0,y=750.0)
        assertTrue(next.zoom > origin.zoom)
        assertEquals(327.0,next.bearing,0.0001)
    }

    @Test fun draggingRotatedMapFollowsFingerAndDoesNotReverse() {
        val tilted=origin.copy(bearing=137.0)
        val next=move(old=tilted,x=610.0,y=1030.0,dx=70.0,dy=70.0)
        assertMapPointMoved(next,x=610.0,y=1030.0)
        assertEquals(137.0,next.bearing,0.0001)
    }
}
