package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoltTerminalPresentationPolicyTest {
    @Test fun pickupOnlyWithEtaShowsProvisionalRateWithoutVerdict() {
        val result = BoltTerminalPresentationPolicy.present(
            MoneyAmount(241, "EUR", 2), 2300, 10,
        )
        assertTrue(result.rateLine.contains("€1.05/km"))
        assertTrue(result.rateLine.contains("⏳"))
        assertEquals("🕒 ~10 min ≈ 2.3 km", result.routeLine)
    }

    @Test fun failureWithoutEtaNeverShowsAFalseRoute() {
        val result = BoltTerminalPresentationPolicy.present(MoneyAmount(241, "EUR", 2), null, null)
        assertEquals("?/km", result.rateLine)
        assertEquals("⚠️ Route unavailable", result.routeLine)
    }
}
