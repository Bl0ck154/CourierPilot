package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArrivalAccessHintTimingPolicyTest {
    @Test
    fun parsesDeliveryScreenRangeAndSchedulesBeforeEarliestEta() {
        val eta = ArrivalAccessHintTimingPolicy.fromScreen(
            """
            Dropoff to
            Customer
            Laumenų gatvė 4, Vilnius
            6–13 min
            Order details
            """.trimIndent()
        )

        assertEquals(6, eta?.minMinutes)
        assertEquals(13, eta?.maxMinutes)
        assertEquals("delivery-screen-range", eta?.source)
        assertEquals(270_000L, ArrivalAccessHintTimingPolicy.notificationDelayMs(eta))
    }

    @Test
    fun parsesSingleDeliveryEtaButIgnoresRestaurantReadyTimeAndAddonExtraEta() {
        val eta = ArrivalAccessHintTimingPolicy.fromScreen("Dropoff to\n8 min\nOrder details")
        assertEquals(8, eta?.minMinutes)
        assertEquals(8, eta?.maxMinutes)

        assertNull(ArrivalAccessHintTimingPolicy.fromScreen("Ready in 1 min\nPickup from\nHoly Donut"))
        assertNull(ArrivalAccessHintTimingPolicy.fromScreen("+1 stop (3.8 km) • 6–13 min extra"))
    }

    @Test
    fun activeOfferEtaShrinksByElapsedTime() {
        val now = 1_000_000L
        val record = OfferRecord(
            capturedAt = now - 4L * 60L * 1000L,
            platform = "Wolt",
            packageName = CourierSignals.WOLT_PACKAGE,
            priceCents = 500,
            distanceMeters = 4_000,
            restaurant = "Test",
            screenshotUri = "",
            screenshotFilename = "",
            rawText = "",
            estimatedMinutesMin = 12,
            estimatedMinutesMax = 18,
        )

        val eta = ArrivalAccessHintTimingPolicy.fromOffer(record, now)

        assertEquals(8, eta?.minMinutes)
        assertEquals(14, eta?.maxMinutes)
        assertEquals("active-offer", eta?.source)
    }

    @Test
    fun veryNearEtaNeverSchedulesLessThanThirtySecondsAndNoEtaWaitsSixMinutes() {
        assertEquals(
            ArrivalAccessHintTimingPolicy.MIN_DELAY_MS,
            ArrivalAccessHintTimingPolicy.notificationDelayMs(ArrivalEtaWindow(1, 2, "test")),
        )
        assertEquals(
            ArrivalAccessHintTimingPolicy.DEFAULT_DELAY_MS,
            ArrivalAccessHintTimingPolicy.notificationDelayMs(null),
        )
    }
}
