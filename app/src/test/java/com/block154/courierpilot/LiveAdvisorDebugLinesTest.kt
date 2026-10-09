package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAdvisorDebugLinesTest {
    @Test fun shortAddressDropsPostcodeAndCity() {
        assertEquals("Vokiečių gatvė 13",
            LiveAdvisorDebugLines.shortAddress("Vokiečių gatvė 13 01130 Vilnius Lithuania"))
    }

    @Test fun boltDebugLinesAreBoundedEvenForMultiDrop() {
        val d = BoltRecoveryDiagnostics(
            scaleMetersPerPixel = 2.4,
            anchorBaselinePx = 180.0,
            anchorBaselineMeters = 420.0,
        )
        val lines = LiveAdvisorDebugLines.bolt(
            listOf("Vokiečių gatvė 13, 01130 Vilnius, Lithuania"),
            listOf("Pilies g. 2, Vilnius", "Gedimino pr. 4, Vilnius"),
            0.62, d, 10, 2300,
        )
        assertEquals(3, lines.size)
        assertTrue(lines[1].contains(" | "))
        assertTrue(lines[2].contains("base 180px/420m"))
    }

    @Test fun woltShowsOnlyRelevantAddresses() {
        val lines = LiveAdvisorDebugLines.wolt(
            listOf("Vilniaus g. 18, Vilnius"), listOf("Šatrijos gatvė 14, Vilnius"),
        )
        assertEquals(2, lines.size)
        assertFalse(lines.any { it.contains("Vilnius") })
    }

    @Test fun timeoutExactlyTwentySecondsOnce() {
        assertFalse(LiveAdvisorTerminalPolicy.expired(1000, 20999, false))
        assertTrue(LiveAdvisorTerminalPolicy.expired(1000, 21000, false))
        assertFalse(LiveAdvisorTerminalPolicy.expired(1000, 25000, true))
    }
}
