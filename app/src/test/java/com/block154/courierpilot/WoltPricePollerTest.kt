package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WoltPricePollerTest {
    @Test
    fun newOfferSchedulesImmediatelyAndDuplicateOnlyRepostsWhenExpedited() {
        val scheduler = FakeScheduler()
        val pending = woltPending()
        val poller = fixture(scheduler, pendingProvider = { pending })

        poller.ensure(pending)
        assertEquals(1, scheduler.immediate.size)

        poller.ensure(pending)
        assertEquals(1, scheduler.immediate.size)

        poller.ensure(pending, expedite = true)
        assertEquals(1, scheduler.immediate.size)
        assertEquals(2, scheduler.removeCalls)
    }

    @Test
    fun dragDefersPollWithoutTouchingPriceProbe() {
        val scheduler = FakeScheduler()
        val pending = woltPending()
        var dragging = true
        var probeCalls = 0
        val poller = fixture(
            scheduler,
            pendingProvider = { pending },
            overlayGestureActive = { dragging },
            probePrice = { probeCalls += 1; false },
        )

        poller.ensure(pending)
        scheduler.runImmediate()

        assertEquals(0, probeCalls)
        assertEquals(listOf(120L), scheduler.delayed.map { it.second })
        dragging = false
    }

    @Test
    fun eventThrottleSurvivesExpediteAndThenReturnsToHotCadence() {
        val scheduler = FakeScheduler()
        val pending = woltPending()
        var now = 1_000L
        var probeCalls = 0
        val poller = fixture(
            scheduler,
            pendingProvider = { pending },
            nowElapsed = { now },
            probePrice = { probeCalls += 1; false },
        )

        poller.ensure(pending)
        scheduler.runImmediate()
        assertEquals(1, probeCalls)
        assertEquals(listOf(220L), scheduler.delayed.map { it.second })

        now = 1_040L
        poller.ensure(pending, expedite = true)
        scheduler.runImmediate()
        assertEquals(1, probeCalls)
        assertEquals(listOf(50L), scheduler.delayed.map { it.second })

        now = 1_090L
        scheduler.runDelayed()
        assertEquals(2, probeCalls)
        assertEquals(listOf(220L), scheduler.delayed.map { it.second })
    }

    @Test
    fun successfulProbeEndsLoopAndSameOfferCanBeEnsuredAgain() {
        val scheduler = FakeScheduler()
        val pending = woltPending()
        var probeCalls = 0
        val poller = fixture(
            scheduler,
            pendingProvider = { pending },
            probePrice = { probeCalls += 1; true },
        )

        poller.ensure(pending)
        scheduler.runImmediate()
        assertEquals(1, probeCalls)
        assertTrue(scheduler.delayed.isEmpty())

        poller.ensure(pending)
        assertEquals(1, scheduler.immediate.size)
    }

    private fun fixture(
        scheduler: FakeScheduler,
        pendingProvider: () -> PendingOffer?,
        overlayGestureActive: () -> Boolean = { false },
        probePrice: () -> Boolean = { false },
        nowElapsed: () -> Long = { 1_000L },
    ) = WoltPricePoller(
        pendingProvider = pendingProvider,
        overlayGestureActive = overlayGestureActive,
        probePrice = probePrice,
        nowElapsed = nowElapsed,
        postNow = scheduler::postNow,
        postDelayed = scheduler::postDelayed,
        removeCallbacks = scheduler::remove,
        dragDeferMs = 120L,
    )

    private fun woltPending() = PendingOffer(
        packageName = CourierSignals.WOLT_PACKAGE,
        sourceName = "test",
        armedAt = 123L,
        notificationKey = "notification-1",
    )

    private class FakeScheduler {
        val immediate = mutableListOf<Runnable>()
        val delayed = mutableListOf<Pair<Runnable, Long>>()
        var removeCalls = 0

        fun postNow(runnable: Runnable) {
            immediate += runnable
        }

        fun postDelayed(runnable: Runnable, delayMs: Long) {
            delayed += runnable to delayMs
        }

        fun remove(runnable: Runnable) {
            removeCalls += 1
            immediate.removeAll { it === runnable }
            delayed.removeAll { it.first === runnable }
        }

        fun runImmediate() {
            val runnable = immediate.removeAt(0)
            runnable.run()
        }

        fun runDelayed() {
            val runnable = delayed.removeAt(0).first
            runnable.run()
        }
    }
}
