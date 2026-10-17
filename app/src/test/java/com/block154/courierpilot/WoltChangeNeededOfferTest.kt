package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WoltChangeNeededOfferTest {
    private val pickup = "Pylimo g. 43-2, Vilnius, LT01136"
    private val dropoff = "Gerosios Vilties gatvė 25, Vilnius, 03143"

    @Test
    fun screenshotWithChangeReminderPreservesActualEarningsAndTwoStops() {
        val parsed = OfferParser.parse(
            """
            10% boost included
            €4.44
            2 stops (3.7 km) • 13–20 min
            Change needed: €4.38
            Aibė (Nemunas)
            $pickup
            Customer drop-off
            $dropoff
            Accept
            """.trimIndent()
        )

        assertEquals(444, parsed.priceCents)
        assertEquals("EUR", parsed.money?.currencyCode)
        assertEquals(3_700, parsed.distanceMeters)
        assertEquals(13, parsed.estimatedMinutesMin)
        assertEquals(20, parsed.estimatedMinutesMax)
        assertEquals(listOf("Aibė (Nemunas)"), parsed.merchantNames)
        assertEquals(listOf(pickup), parsed.pickupAddresses)
        assertEquals(listOf(dropoff), parsed.dropoffAddresses)
        assertEquals(1, parsed.deliveryCount)
        assertEquals(
            listOf(ParsedRouteStopKind.PICKUP, ParsedRouteStopKind.DROPOFF),
            parsed.orderedRouteStops.map { it.kind },
        )
        assertNotNull(AutomaticWoltRouteCoordinator.routeFingerprint(parsed))
    }

    @Test
    fun splitReminderAmountIsNotMistakenForEarnings() {
        val parsed = OfferParser.parse(
            """
            2 stops (3.7 km) • 13–20 min
            Change needed:
            €4.38
            Aibė (Nemunas)
            $pickup
            Customer drop-off
            $dropoff
            Accept
            """.trimIndent()
        )

        assertNull(parsed.priceCents)
        assertEquals(listOf("Aibė (Nemunas)"), parsed.merchantNames)
        assertEquals(listOf(pickup), parsed.pickupAddresses)
        assertEquals(listOf(dropoff), parsed.dropoffAddresses)
    }

    @Test
    fun incompleteAccessibilityCardDoesNotOverridePricedOcrCopy() {
        val parsed = OfferParser.parse(
            """
            2 stops (3.7 km) • 13–20 min
            Change needed: €4.38
            Aibė (Nemunas)
            Customer drop-off
            Accept
            €4.44
            2 stops (3.7 km) • 13–20 min
            Change needed: €4.38
            Aibė (Nemunas)
            $pickup
            Customer drop-off
            $dropoff
            Accept
            """.trimIndent()
        )

        assertEquals(444, parsed.priceCents)
        assertEquals(listOf(pickup), parsed.pickupAddresses)
        assertEquals(listOf(dropoff), parsed.dropoffAddresses)
        assertEquals(listOf("Aibė (Nemunas)"), parsed.merchantNames)
    }

    @Test
    fun changeNeededNeverBecomesMerchantWhenTitleIsMissing() {
        val parsed = OfferParser.parse(
            """
            €4.44
            2 stops (3.7 km) • 13–20 min
            Change needed:
            €4.38
            $pickup
            Customer drop-off
            $dropoff
            Accept
            """.trimIndent()
        )

        assertFalse(parsed.merchantNames.any { it.contains("Change", ignoreCase = true) })
        assertTrue(WoltOfferUiText.isMerchantUiNoise("Change needed: €4.38"))
        assertTrue(WoltOfferUiText.isMerchantUiNoise("CHANGE NEEDED:"))
        assertTrue(WoltOfferUiText.isMerchantUiNoise("Change needed"))
        assertFalse(WoltOfferUiText.isMerchantUiNoise("Aibė (Nemunas)"))
    }

    @Test
    fun spatialOcrRejectsNearbyCashChangeMoneyWhenEarningsAreMissing() {
        val lines = spatialLines(includeEarnings = false)
        assertNull(WoltEarningsSpatialOcr.findMoney(lines, 1536))
    }

    @Test
    fun spatialOcrKeepsActualPriceAboveSummaryWhenReminderIsPresent() {
        val lines = spatialLines(includeEarnings = true)
        val amount = WoltEarningsSpatialOcr.findMoney(lines, 1536)

        assertEquals(444L, amount?.amountMinor)
        assertEquals("EUR", amount?.currencyCode)
    }

    @Test
    fun spatialOcrIgnoresUnsplitCashChangeAmount() {
        val lines = listOf(
            spatialLine(680, 724, "€4.44"),
            spatialLine(740, 765, "2 stops (3.7 km) • 13–20 min"),
            spatialLine(792, 820, "Change needed: €4.38"),
            spatialLine(1210, 1240, "Estimated earnings for the full delivery"),
        )
        assertEquals(444L, WoltEarningsSpatialOcr.findMoney(lines, 1536)?.amountMinor)
    }

    private fun spatialLines(includeEarnings: Boolean): List<OcrSpatialLine> = buildList {
        if (includeEarnings) add(spatialLine(680, 724, "€4.44"))
        add(spatialLine(740, 765, "2 stops (3.7 km) • 13–20 min"))
        add(spatialLine(790, 820, "Change needed:"))
        add(spatialLine(823, 850, "€4.38"))
        add(spatialLine(1210, 1240, "Estimated earnings for the full delivery"))
    }

    private fun spatialLine(top: Int, bottom: Int, text: String): OcrSpatialLine =
        OcrSpatialLine(top = top, bottom = bottom, left = 24, right = 280, text = text)
}
