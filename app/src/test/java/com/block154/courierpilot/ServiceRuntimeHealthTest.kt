package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceRuntimeHealthTest {
    @Test
    fun ageDescription_handlesNeverAndRecentValues() {
        val now = 1_000_000L
        assertEquals("never", ServiceRuntimeHealth.ageDescription(0L, now))
        assertEquals("just now", ServiceRuntimeHealth.ageDescription(now - 30_000L, now))
        assertEquals("2 min ago", ServiceRuntimeHealth.ageDescription(now - 120_000L, now))
    }
}
