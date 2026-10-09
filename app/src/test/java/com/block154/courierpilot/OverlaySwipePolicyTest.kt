package com.block154.courierpilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlaySwipePolicyTest {
    @Test
    fun longDistanceEitherDirectionDismisses() {
        assertTrue(OverlaySwipePolicy.shouldDismiss(93f, 0f, 300, 1f))
        assertTrue(OverlaySwipePolicy.shouldDismiss(-93f, 0f, 300, 1f))
        assertFalse(OverlaySwipePolicy.shouldDismiss(55f, 0f, 300, 1f))
    }

    @Test
    fun shortFastDirectionalFlingsDismiss() {
        assertTrue(OverlaySwipePolicy.shouldDismiss(17f, 901f, 300, 1f))
        assertTrue(OverlaySwipePolicy.shouldDismiss(-17f, -901f, 300, 1f))
        assertFalse(OverlaySwipePolicy.shouldDismiss(15f, 2000f, 300, 1f))
        assertFalse(OverlaySwipePolicy.shouldDismiss(17f, 899f, 300, 1f))
    }

    @Test
    fun reversalOrCrossAxisWobbleDoesNotDismiss() {
        assertFalse(OverlaySwipePolicy.shouldDismiss(30f, -1800f, 300, 1f))
        assertFalse(OverlaySwipePolicy.shouldDismiss(-30f, 1800f, 300, 1f))
        assertFalse(OverlaySwipePolicy.shouldDismiss(0f, 1800f, 300, 1f))
    }

    @Test
    fun scalesWithDensityAndCardWidth() {
        assertFalse(OverlaySwipePolicy.shouldDismiss(30f, 1000f, 600, 2f))
        assertTrue(OverlaySwipePolicy.shouldDismiss(33f, 1801f, 600, 2f))
        assertFalse(OverlaySwipePolicy.shouldDismiss(179f, 0f, 700, 2f))
        assertTrue(OverlaySwipePolicy.shouldDismiss(211f, 0f, 700, 2f))
    }
}
