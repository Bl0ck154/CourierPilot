package com.block154.courierpilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class OfferCaptureRuntimeTest {
    @Test
    fun beginAndFinishOwnBusyStateWithTokenValidation() {
        val runtime = OfferCaptureRuntime(timeoutMs = 5_000L)

        val token = runtime.begin(1_000L, "ocr", "Wolt")

        assertTrue(runtime.isBusy)
        assertTrue(runtime.isCurrent(token))
        assertFalse(runtime.finish(token + 1L))
        assertTrue(runtime.isBusy)
        assertTrue(runtime.finish(token))
        assertFalse(runtime.isBusy)
        assertFalse(runtime.isCurrent(token))
    }

    @Test
    fun timeoutRecoveryClearsBusyAndInvalidatesTimedOutToken() {
        val runtime = OfferCaptureRuntime(timeoutMs = 5_000L)
        val token = runtime.begin(1_000L, "screenshot", "Wolt")

        val timedOut = runtime.recoverIfTimedOut(6_100L)

        assertNotNull(timedOut)
        assertEquals(token, timedOut!!.token)
        assertFalse(runtime.isBusy)
        assertFalse(runtime.isCurrent(token))
    }

    @Test
    fun cancelInvalidatesLateCallbackAndAllowsFreshFlight() {
        val runtime = OfferCaptureRuntime(timeoutMs = 5_000L)
        val oldToken = runtime.begin(1_000L, "ocr", "Bolt")

        runtime.cancel()
        val newToken = runtime.begin(2_000L, "ocr", "Bolt")

        assertFalse(runtime.isCurrent(oldToken))
        assertTrue(runtime.isCurrent(newToken))
        assertTrue(newToken > oldToken)
    }

    @Test
    fun releaseBusyPreservesHistoricalNonCancellingSemantics() {
        val runtime = OfferCaptureRuntime(timeoutMs = 5_000L)
        val oldToken = runtime.begin(1_000L, "ocr", "Wolt")

        runtime.releaseBusy()

        assertFalse(runtime.isBusy)
        assertFalse(runtime.isCurrent(oldToken))
        val newToken = runtime.begin(2_000L, "screenshot", "Wolt")
        assertTrue(newToken > oldToken)
        assertTrue(runtime.isCurrent(newToken))
    }

    @Test
    fun screenshotFailuresResetForNewOfferAndExplicitReset() {
        val runtime = OfferCaptureRuntime(timeoutMs = 5_000L)

        assertEquals(1, runtime.recordScreenshotFailure("offer-a"))
        assertEquals(2, runtime.recordScreenshotFailure("offer-a"))
        runtime.resetScreenshotFailures("offer-a")
        assertEquals(1, runtime.recordScreenshotFailure("offer-a"))
        assertEquals(1, runtime.recordScreenshotFailure("offer-b"))
    }
}
