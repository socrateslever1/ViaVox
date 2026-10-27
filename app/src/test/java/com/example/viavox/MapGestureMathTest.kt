package com.example.viavox

import com.example.viavox.ui.components.MapGestureCamera
import com.example.viavox.ui.components.MapGestureMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Regression tests for physically intuitive gestures on the internal map. */
class MapGestureMathTest {
    private val original = MapGestureCamera(
        latitude = -3.094000, longitude = -60.028000, zoom = 17.0, bearing = 0.0
    )
    private val w = 1080.0
    private val h = 1920.0

    private fun swipe(
        panX: Double = 0.0, panY: Double = 0.0, factor: Double = 1.0,
        rotation: Double = 0.0, centroidX: Double = w / 2,
        centroidY: Double = h / 2, camera: MapGestureCamera = original
    ): MapGestureCamera = MapGestureMath.transform(
        camera, w, h, centroidX, centroidY,
        panX, panY, factor, rotation, 3.0, 19.0
    )

    private fun pinStaysUnderFingers(
        camera: MapGestureCamera, centroidX: Double, centroidY: Double,
        panX: Double, panY: Double, factor: Double, rotation: Double
    ) {
        val anchor = MapGestureMath.atScreen(
            camera, centroidX - panX, centroidY - panY, w, h
        )
        val changed = swipe(panX, panY, factor, rotation, centroidX, centroidY,
            camera)
        val projection = MapGestureMath.toScreen(
            changed, anchor.first, anchor.second, w, h
        )
        assertEquals("Original map pixel must remain under moving fingers",
            centroidX, projection.first, 0.03)
        assertEquals("Original map pixel must remain under moving fingers",
            centroidY, projection.second, 0.03)
    }

    @Test fun dragRightMovesMapRightNotLeft() {
        val changed = swipe(panX = 150.0)
        assertTrue("Dragging right must move camera west", changed.longitude < original.longitude)
        assertEquals(original.latitude, changed.latitude, 1e-7)
        pinStaysUnderFingers(original, w/2 + 150, h/2, 150.0, 0.0, 1.0, 0.0)
    }

    @Test fun dragUpMovesMapUpNotDown() {
        val changed = swipe(panY = -170.0)
        assertTrue("Dragging up must move camera south",changed.latitude < original.latitude)
        pinStaysUnderFingers(original, w/2, h/2 - 170.0, 0.0, -170.0, 1.0, 0.0)
    }

    @Test fun pinchOutIncreasesZoomAndPinchInDecreasesZoom() {
        assertEquals(18.0, swipe(factor = 2.0).zoom, 1e-10)
        assertEquals(16.0, swipe(factor = 0.5).zoom, 1e-10)
        assertEquals(19.0, swipe(factor = 100.0).zoom, 1e-10)
        assertEquals(3.0, swipe(factor = 0.000001).zoom, 1e-10)
    }

    @Test fun pinchAtOffCentreStaysAnchoredToGeographicPoint() {
        pinStaysUnderFingers(original, 810.0, 450.0, 0.0, 0.0, 1.4, 0.0)
        pinStaysUnderFingers(original, 260.0, 1390.0, 0.0, 0.0, 0.68, 0.0)
    }

    @Test fun clockwiseTwistRotatesMapClockwiseRatherThanOpposite() {
        val changed = swipe(rotation = 90.0)
        assertEquals(270.0,changed.bearing,1e-7)
        val pointEast = MapGestureMath.atScreen(original,w/2+180,h/2,w,h)
        val displayed = MapGestureMath.toScreen(
            changed, pointEast.first, pointEast.second,w,h
        )
        assertEquals(w/2,displayed.first,0.03)
        assertTrue("Map feature east of centre must rotate down",
            displayed.second > h/2 + 179.9)
        pinStaysUnderFingers(original,w/2,h/2,0.0,0.0,1.0,90.0)
    }

    @Test fun headingUpCameraStillFollowsDragFingers() {
        val rotated = original.copy(bearing=125.0)
        pinStaysUnderFingers(rotated,800.0,600.0,105.0,-75.0,1.0,0.0)
        pinStaysUnderFingers(rotated,740.0,590.0,0.0,0.0,1.2,-26.0)
    }

    @Test fun simultaneousPanPinchAndTwistPreserveExactFocalPoint() {
        pinStaysUnderFingers(original,710.0,470.0,72.0,-55.0,1.33,18.0)
        pinStaysUnderFingers(original.copy(bearing=240.0),350.0,1440.0,
            -59.0,98.0,0.79,-45.0)
    }

    @Test fun invalidMotionNeverCorruptsCameraState() {
        assertEquals(original,swipe(factor=Double.NaN))
        assertEquals(original,swipe(factor=0.0))
        assertEquals(original,swipe(panX=Double.POSITIVE_INFINITY))
        assertTrue(abs(swipe(rotation=380.0).bearing-340.0)<1e-7)
    }

    @Test fun successiveSmallGesturesDoNotFlipMapDirection() {
        var camera = original
        repeat(15) {
            val before = camera
            camera = swipe(panX=8.0,panY=3.0,camera=camera)
            assertTrue(camera.longitude < before.longitude)
            assertTrue(camera.latitude > before.latitude)
        }
    }
}
