package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WoltCaptureSessionTest {
    private fun pending(armedAt: Long, key: String) = PendingOffer(
        packageName = CourierSignals.WOLT_PACKAGE,
        sourceName = "Wolt",
        armedAt = armedAt,
        notificationKey = key,
    )

    @Test
    fun mergesCollapsedCardAndExpandedDropoffSheetForSameOffer() {
        val session = WoltCaptureSession()
        val offer = pending(100L, "offer-a")
        val card = """
            €9.02
            2 stops (12.6 km) 23-36 min
            Vokiečių g. 7, Vilnius, LT-01130
            Multiple drop-offs (2 stops)
            Estimated earnings for the full delivery
            Accept
        """.trimIndent()
        val sheet = """
            Multiple drop-offs
            2 stops
            Versmių gatvė 65-2, Vilnius, 11307
            Žirmūnų g. 54, Vilnius
            Done
        """.trimIndent()

        session.accumulateFrame(offer, card)
        val merged = session.accumulateFrame(offer, sheet)

        assertTrue(merged.contains("€9.02"))
        assertTrue(merged.contains("Versmių gatvė 65-2"))
        assertEquals(card, session.cardFrameText)
        assertEquals(sheet, session.dropoffFrameText)
    }

    @Test
    fun newOfferKeyClearsFramesAndAllRouteSessionCounters() {
        val session = WoltCaptureSession()
        val first = pending(100L, "offer-a")
        val second = pending(200L, "offer-b")
        val firstCard = """
            €7.01
            2 stops (8.0 km) 13-26 min
            Totorių g. 24, Vilnius
            Multiple drop-offs (2 stops)
            Accept
        """.trimIndent()
        val secondCard = """
            €3.96
            2 stops (4.4 km) 7-14 min
            Palangos g. 2, Vilnius
            Multiple drop-offs (2 stops)
            Accept
        """.trimIndent()

        session.accumulateFrame(first, firstCard)
        session.visibleBasePickupAddresses = listOf("Totorių g. 24")
        session.dropoffProbeAttempts = 3
        session.dropoffSemanticProbeAttempts = 2
        session.dropoffResolvedKey = session.offerKey(first)
        session.dropoffResolvedCount = 2
        session.dropoffSheetSettleAttempts = 4
        session.routeOcrRecoveryAttempts = 1
        session.idleHomeKey = "old"
        session.idleHomeFirstSeenAtElapsed = 1234L
        session.idleHomeChecks = 5

        val merged = session.accumulateFrame(second, secondCard)

        assertEquals(secondCard, merged)
        assertFalse(merged.contains("Totorių"))
        assertEquals(emptyList<String>(), session.visibleBasePickupAddresses)
        assertEquals(0, session.dropoffProbeAttempts)
        assertEquals(0, session.dropoffSemanticProbeAttempts)
        assertEquals("", session.dropoffResolvedKey)
        assertEquals(0, session.dropoffResolvedCount)
        assertEquals(0, session.dropoffSheetSettleAttempts)
        assertEquals(0, session.routeOcrRecoveryAttempts)
        assertEquals("", session.idleHomeKey)
        assertEquals(0L, session.idleHomeFirstSeenAtElapsed)
        assertEquals(0, session.idleHomeChecks)
    }

    @Test
    fun nonWoltTextPassesThroughWithoutStartingSession() {
        val session = WoltCaptureSession()
        val bolt = PendingOffer(
            packageName = CourierSignals.BOLT_PACKAGE,
            sourceName = "Bolt",
            armedAt = 100L,
            notificationKey = "bolt-a",
        )

        assertEquals("raw bolt text", session.accumulateFrame(bolt, "raw bolt text"))
        assertEquals("", session.frameKey)
    }
}
