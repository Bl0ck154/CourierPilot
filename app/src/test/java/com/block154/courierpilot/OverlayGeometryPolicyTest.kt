package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayGeometryPolicyTest {
    @Test
    fun widthIsNarrowerAndCenteredAtCommonPhoneSizes() {
        assertEquals(286, OverlayGeometryPolicy.widthPx(360, 1f))
        assertEquals(314, OverlayGeometryPolicy.widthPx(393, 1f))
        assertEquals(330, OverlayGeometryPolicy.widthPx(412, 1f))
        assertEquals(572, OverlayGeometryPolicy.widthPx(720, 2f))
        assertEquals(800, OverlayGeometryPolicy.widthPx(2000, 2f))
    }

    @Test
    fun widthNeverExceedsSmallDisplay() {
        assertEquals(216, OverlayGeometryPolicy.widthPx(240, 1f))
        assertEquals(1, OverlayGeometryPolicy.widthPx(1, 1f))
    }

    @Test
    fun placementAccountsForControlsAcrossPhoneAspectRatios() {
        for (height in listOf(640, 800, 840, 915, 927)) {
            assertEquals(140, OverlayGeometryPolicy.defaultYPx(132, 24, height, 1f))
            assertTrue(OverlayGeometryPolicy.defaultYPx(null, 24, height, 1f) > 48)
        }
    }

    @Test
    fun placementUsesCutoutInsetAndClampsDeepObstacles() {
        assertEquals(48, OverlayGeometryPolicy.defaultYPx(40, 44, 840, 1f))
        assertEquals(294, OverlayGeometryPolicy.defaultYPx(400, 44, 840, 1f))
        assertEquals(48, OverlayGeometryPolicy.defaultYPx(null, 40, 100, 1f))
    }

    @Test
    fun obstacleWinsOverFallbackButCannotCrossBottomBoundary() {
        assertEquals(90, OverlayGeometryPolicy.defaultYPx(82, 24, 800, 1f))
        assertEquals(88, OverlayGeometryPolicy.defaultYPx(null, 24, 800, 1f))
        assertEquals(280, OverlayGeometryPolicy.defaultYPx(700, 24, 800, 1f))
    }
}
