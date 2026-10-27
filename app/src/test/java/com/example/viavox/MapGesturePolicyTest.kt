package com.example.viavox

import com.example.viavox.ui.components.MapGesturePolicy
import org.junit.Assert.*
import org.junit.Test

/**
 * Contract: when users twist their fingers clockwise, the visible map
 * (drawn with a -bearing rotation) must rotate clockwise too.
 */
class MapGesturePolicyTest {
    @Test fun clockwiseTwistMakesVisibleMapRotateClockwise() {
        val before = 30f
        val after = MapGesturePolicy.rotateCameraBearing(before, 40f)
        assertEquals(350f, after, 0.0001f)
        // The native canvas uses -bearing: -30 -> -350 (= +10).
        // That is a clockwise +40-degree change on screen.
        assertEquals(40f, ((-after + before + 720f) % 360f), 0.0001f)
    }

    @Test fun counterClockwiseTwistMakesVisibleMapRotateCounterClockwise() {
        val after = MapGesturePolicy.rotateCameraBearing(30f, -40f)
        assertEquals(70f, after, 0.0001f)
        assertEquals(320f, ((-after + 30f + 720f) % 360f), 0.0001f)
    }

    @Test fun pureRotationReleasesCameraFromGpsFollow() {
        assertTrue(MapGesturePolicy.isManualTransform(0f,0f,1f,4f))
        assertTrue(MapGesturePolicy.isManualTransform(0f,0f,1.2f,0f))
        assertTrue(MapGesturePolicy.isManualTransform(9f,0f,1f,0f))
        assertFalse(MapGesturePolicy.isManualTransform(0f,0f,1f,0f))
    }

    @Test fun pinchOutZoomsInAndPinchInZoomsOut() {
        assertTrue(MapGesturePolicy.zoomLevel(15.0,2f,3.0,19.0) > 15.0)
        assertTrue(MapGesturePolicy.zoomLevel(15.0,0.5f,3.0,19.0) < 15.0)
        assertEquals(19.0,MapGesturePolicy.zoomLevel(19.0,2f,3.0,19.0),0.0001)
    }

    @Test fun gesturesWrapAroundWithoutNegativeCameraBearings() {
        assertEquals(350f,MapGesturePolicy.rotateCameraBearing(10f,20f),0.0001f)
        assertEquals(10f,MapGesturePolicy.rotateCameraBearing(350f,-20f),0.0001f)
        assertEquals(0f,MapGesturePolicy.rotateCameraBearing(0f,360f),0.0001f)
    }
}
