package com.block154.courierpilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureFlightGuardTest {
    @Test
    fun timedOutCapture_isInvalidatedAndNewCaptureCanStart() {
        val guard = CaptureFlightGuard(timeoutMs = 5_000L)
        val first = guard.begin(1_000L, "ocr", "Wolt")

        assertTrue(guard.isCurrent(first))
        assertNull(guard.recoverIfTimedOut(5_999L))

        val timeout = guard.recoverIfTimedOut(6_000L)
        assertNotNull(timeout)
        assertFalse(guard.isCurrent(first))

        val second = guard.begin(6_100L, "screenshot", "Bolt")
        assertTrue(guard.isCurrent(second))
        assertFalse(guard.finish(first))
        assertTrue(guard.isCurrent(second))
        assertTrue(guard.finish(second))
        assertFalse(guard.isCurrent(second))
    }
}
