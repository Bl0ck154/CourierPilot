package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAdvisorDecisionThresholdsTest {
    private val adaptive = OfferDecisionThresholds(0.8, 0.9, 1.1, 1.4)

    @Test
    fun warmedAdaptiveSnapshotWinsWhenLoadedBeforeSession() {
        val background = ArrayDeque<() -> Unit>()
        val main = ArrayDeque<() -> Unit>()
        val thresholds = fixture(background, main)

        thresholds.warmPlatform("Wolt")
        background.removeFirst().invoke()
        main.removeFirst().invoke()
        thresholds.beginOffer("Wolt", 1L)

        val snapshot = thresholds.snapshotFor("EUR")
        assertEquals("adaptive", snapshot.source)
        assertEquals(adaptive, snapshot.thresholds)
    }

    @Test
    fun lateWarmupCannotChangeCurrentSessionEvenBeforeFirstScore() {
        val background = ArrayDeque<() -> Unit>()
        val main = ArrayDeque<() -> Unit>()
        val thresholds = fixture(background, main)

        thresholds.warmPlatform("Wolt")
        thresholds.beginOffer("Wolt", 7L)
        background.removeFirst().invoke()
        main.removeFirst().invoke()
        assertEquals("currency_cold_start_frozen", thresholds.snapshotFor("EUR").source)

        thresholds.clearOffer()
        assertTrue(background.isNotEmpty()) // Refresh only after the session ended.
        thresholds.beginOffer("Wolt", 8L)
        assertEquals("adaptive", thresholds.snapshotFor("EUR").source)
    }

    @Test
    fun activeSessionPrewarmDoesNotQueryDatabaseOrChangeThresholds() {
        val background = ArrayDeque<() -> Unit>()
        val main = ArrayDeque<() -> Unit>()
        val thresholds = fixture(background, main)

        thresholds.beginOffer("Bolt", 11L)
        thresholds.prewarm()
        thresholds.prewarm()
        assertTrue(background.isEmpty())
        val initial = thresholds.snapshotFor("EUR")
        assertEquals("currency_cold_start_frozen", initial.source)
        assertEquals(initial, thresholds.snapshotFor("EUR"))
    }

    @Test
    fun processStoreIsSharedAcrossAdvisorInstances() {
        val background = ArrayDeque<() -> Unit>()
        val main = ArrayDeque<() -> Unit>()
        val shared = mutableMapOf<Pair<String, String>, LiveAdvisorDecisionThresholdSnapshot>()
        val first = fixture(background, main, shared)
        first.warmPlatform("Wolt")
        background.removeFirst().invoke()
        main.removeFirst().invoke()
        val second = fixture(background, main, shared)
        second.beginOffer("Wolt", 3L)
        assertEquals("adaptive", second.snapshotFor("EUR").source)
    }

    private fun fixture(
        background: ArrayDeque<() -> Unit>,
        main: ArrayDeque<() -> Unit>,
        store: MutableMap<Pair<String, String>, LiveAdvisorDecisionThresholdSnapshot> = mutableMapOf(),
    ) = LiveAdvisorDecisionThresholds(
        loadCurrency = { "EUR" },
        loadAdaptiveThresholds = { _, _ -> adaptive },
        executeBackground = { background.addLast(it) },
        postToMain = { main.addLast(it) },
        warmedStore = store,
    )
}
