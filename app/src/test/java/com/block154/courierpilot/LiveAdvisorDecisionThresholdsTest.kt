package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveAdvisorDecisionThresholdsTest {
    private val adaptive = OfferDecisionThresholds(0.8, 0.9, 1.1, 1.4)

    @Test
    fun warmedAdaptiveSnapshotWinsWhenItFinishesBeforeScoring() {
        val background = ArrayDeque<() -> Unit>()
        val main = ArrayDeque<() -> Unit>()
        val thresholds = fixture(background, main)

        thresholds.beginOffer("Wolt", 1L)
        thresholds.prewarm()
        background.removeFirst().invoke()
        main.removeFirst().invoke()

        val snapshot = thresholds.snapshotFor("EUR")
        assertEquals("adaptive", snapshot.source)
        assertEquals(adaptive, snapshot.thresholds)
    }

    @Test
    fun firstScoringSnapshotStaysFrozenAgainstLateWarmup() {
        val background = ArrayDeque<() -> Unit>()
        val main = ArrayDeque<() -> Unit>()
        val thresholds = fixture(background, main)

        thresholds.beginOffer("Wolt", 7L)
        thresholds.prewarm()
        val frozen = thresholds.snapshotFor("EUR")
        assertEquals("currency_cold_start_frozen", frozen.source)

        background.removeFirst().invoke()
        main.removeFirst().invoke()

        val afterWarmup = thresholds.snapshotFor("EUR")
        assertEquals(frozen, afterWarmup)
    }

    @Test
    fun callbackFromPreviousOfferCannotPopulateNewOffer() {
        val background = ArrayDeque<() -> Unit>()
        val main = ArrayDeque<() -> Unit>()
        val thresholds = fixture(background, main)

        thresholds.beginOffer("Wolt", 11L)
        thresholds.prewarm()
        background.removeFirst().invoke()

        thresholds.beginOffer("Wolt", 12L)
        main.removeFirst().invoke()

        val snapshot = thresholds.snapshotFor("EUR")
        assertEquals("currency_cold_start_frozen", snapshot.source)
    }

    private fun fixture(
        background: ArrayDeque<() -> Unit>,
        main: ArrayDeque<() -> Unit>,
    ) = LiveAdvisorDecisionThresholds(
        loadCurrency = { "EUR" },
        loadAdaptiveThresholds = { _, _ -> adaptive },
        executeBackground = { background.addLast(it) },
        postToMain = { main.addLast(it) },
    )
}
