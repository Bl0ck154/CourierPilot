package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DeliveryTimelineTest {

    @Test
    fun derivesObservedPickupWaitAndTotalDeliveryTime() {
        val events = listOf(
            DeliveryTimelineEvent(DeliveryEventType.ACCEPTED, 1_000L, source = "screen"),
            DeliveryTimelineEvent(DeliveryEventType.ARRIVED_PICKUP, 301_000L, stopKey = "venue-a", source = "gps+screen"),
            DeliveryTimelineEvent(DeliveryEventType.PICKED_UP, 601_000L, stopKey = "venue-a", source = "screen"),
            DeliveryTimelineEvent(DeliveryEventType.DELIVERED, 1_501_000L, source = "screen"),
        )

        val metrics = DeliveryTimelineAnalyzer.analyze(events)

        assertEquals(1500, metrics.acceptedToCompleteSeconds)
        assertEquals(300, metrics.pickupWaits.single().waitSeconds)
        assertFalse(metrics.cancelled)
    }

    @Test
    fun neverInventsCompletionFromPartialTimeline() {
        val metrics = DeliveryTimelineAnalyzer.analyze(
            listOf(
                DeliveryTimelineEvent(DeliveryEventType.ACCEPTED, 1_000L, source = "screen"),
                DeliveryTimelineEvent(DeliveryEventType.PICKED_UP, 200_000L, stopKey = "venue-a", source = "screen"),
            )
        )

        assertNull(metrics.completedAtMillis)
        assertNull(metrics.acceptedToCompleteSeconds)
        assertEquals(0, metrics.pickupWaits.size)
    }
}
