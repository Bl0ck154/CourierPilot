package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferDiscoveryOcrPolicyTest {

    @Test
    fun activeBoltCanProbeWithoutRecentAccessibilityEvent() {
        val plan = OfferDiscoveryOcrPolicy.plan(
            packageName = CourierSignals.BOLT_PACKAGE,
            activeCourierWindow = true,
            nowElapsed = 50_000L,
            lastCourierEventPackage = CourierSignals.BOLT_PACKAGE,
            lastCourierEventAtElapsed = 10_000L,
            lastDiscoveryOcrAtElapsed = 40_000L,
        )

        assertTrue(plan.runNow)
        assertNull(plan.retryAfterMs)
    }

    @Test
    fun boltCooldownReturnsExactRetryInsteadOfFallingIntoEightSecondWatchdog() {
        val plan = OfferDiscoveryOcrPolicy.plan(
            packageName = CourierSignals.BOLT_PACKAGE,
            activeCourierWindow = true,
            nowElapsed = 10_500L,
            lastCourierEventPackage = CourierSignals.BOLT_PACKAGE,
            lastCourierEventAtElapsed = 10_450L,
            lastDiscoveryOcrAtElapsed = 10_000L,
        )

        assertFalse(plan.runNow)
        assertEquals(1_300L, plan.retryAfterMs)
    }

    @Test
    fun staleBackgroundBoltWindowCannotStartPassiveScreenshotLoop() {
        val plan = OfferDiscoveryOcrPolicy.plan(
            packageName = CourierSignals.BOLT_PACKAGE,
            activeCourierWindow = false,
            nowElapsed = 50_000L,
            lastCourierEventPackage = CourierSignals.BOLT_PACKAGE,
            lastCourierEventAtElapsed = 10_000L,
            lastDiscoveryOcrAtElapsed = 0L,
        )

        assertFalse(plan.runNow)
        assertNull(plan.retryAfterMs)
        assertNull(OfferDiscoveryOcrPolicy.passiveRescanDelayMs(CourierSignals.BOLT_PACKAGE, false))
    }

    @Test
    fun activeBoltGetsLowCadencePassiveRescan() {
        assertEquals(
            OfferDiscoveryOcrPolicy.BOLT_ACTIVE_RESCAN_MS,
            OfferDiscoveryOcrPolicy.passiveRescanDelayMs(CourierSignals.BOLT_PACKAGE, true),
        )
        assertNull(OfferDiscoveryOcrPolicy.passiveRescanDelayMs(CourierSignals.WOLT_PACKAGE, true))
    }
}
