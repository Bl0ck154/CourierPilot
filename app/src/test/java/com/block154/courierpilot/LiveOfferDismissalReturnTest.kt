package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveOfferDismissalReturnTest {
    private val wolt = CourierSignals.WOLT_PACKAGE
    private val own = "com.block154.courierpilot"

    private fun transition(foreground: String, left: Boolean) =
        LiveOfferUserDismissalPolicy.foregroundTransition(wolt, foreground, own, left)

    @Test
    fun launcherOrOtherAppMeansTheCourierLeftWolt() {
        assertEquals(DismissalForegroundTransition.LEFT, transition("com.android.launcher", left = false))
        assertEquals(DismissalForegroundTransition.LEFT, transition("org.telegram.messenger", left = false))
    }

    @Test
    fun shadeScreenshotsAndOwnOverlayAreNotLeaving() {
        assertEquals(DismissalForegroundTransition.NONE, transition("com.android.systemui", left = false))
        assertEquals(DismissalForegroundTransition.NONE, transition("com.oplus.screenshot", left = false))
        assertEquals(DismissalForegroundTransition.NONE, transition(own, left = false))
    }

    @Test
    fun returningAfterLeavingEndsTheDismissal() {
        assertEquals(DismissalForegroundTransition.RETURNED, transition(wolt, left = true))
    }

    @Test
    fun stayingInWoltKeepsTheDismissal() {
        assertEquals(DismissalForegroundTransition.NONE, transition(wolt, left = false))
    }
}
