package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlayGestureAxisPolicyTest {
    @Test
    fun belowTouchSlopDoesNotLockAnAxis() {
        assertNull(OverlayGestureAxisPolicy.classify(dx = 7f, dy = 8f, touchSlop = 8))
    }

    @Test
    fun nearDiagonalStartPrefersMovingCardVertically() {
        assertEquals(
            OverlayGestureAxis.VERTICAL,
            OverlayGestureAxisPolicy.classify(dx = 9f, dy = 8f, touchSlop = 8),
        )
        assertEquals(
            OverlayGestureAxis.VERTICAL,
            OverlayGestureAxisPolicy.classify(dx = 20f, dy = 15f, touchSlop = 8),
        )
    }

    @Test
    fun clearlyHorizontalMotionStillSelectsDismissSwipe() {
        assertEquals(
            OverlayGestureAxis.HORIZONTAL,
            OverlayGestureAxisPolicy.classify(dx = 24f, dy = 6f, touchSlop = 8),
        )
    }

    @Test
    fun verticalMotionSelectsDrag() {
        assertEquals(
            OverlayGestureAxis.VERTICAL,
            OverlayGestureAxisPolicy.classify(dx = 5f, dy = 24f, touchSlop = 8),
        )
    }
}
