package com.block154.courierpilot

import com.block154.courierpilot.ui.RateGrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfferRowRatePolicyTest {
    private fun record(cents: Int, meters: Int?, currency: String = "EUR") = OfferRecord(
        capturedAt = 1L,
        platform = "Wolt",
        packageName = CourierSignals.WOLT_PACKAGE,
        priceCents = cents,
        currencyCode = currency,
        distanceMeters = meters,
        restaurant = null,
        screenshotUri = "",
        screenshotFilename = "",
        rawText = "",
    )

    @Test
    fun gradesFollowLiveCardColdStartBands() {
        assertEquals(RateGrade.FIRE, OfferRowRatePolicy.rate(record(723, 4_100))!!.grade)
        assertEquals(RateGrade.GOOD, OfferRowRatePolicy.rate(record(276, 2_500))!!.grade)
        assertEquals(RateGrade.OK, OfferRowRatePolicy.rate(record(800, 8_000))!!.grade)
        assertEquals(RateGrade.BAD, OfferRowRatePolicy.rate(record(311, 3_700))!!.grade)
        assertEquals(RateGrade.TERRIBLE, OfferRowRatePolicy.rate(record(502, 7_400))!!.grade)
    }

    @Test
    fun formatsValueWithoutUnit() {
        val rate = OfferRowRatePolicy.rate(record(723, 4_100))!!
        assertEquals("€1.76", rate.value)
        assertEquals("/km", rate.unit)
    }

    @Test
    fun noDistanceMeansNoRate() {
        assertNull(OfferRowRatePolicy.rate(record(500, null)))
    }

    @Test
    fun uncalibratedCurrencyStaysNeutral() {
        assertEquals(RateGrade.UNKNOWN, OfferRowRatePolicy.rate(record(5_000, 4_000, "PLN"))!!.grade)
    }
}
