package com.block154.courierpilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WoltOfferUiTextTest {
    @Test
    fun groupedOrderHandoverLabelIsNotAMerchantOrSecondDestination() {
        assertTrue(WoltOfferUiText.groupedDropoffOrdersRegex.matches("Drop off 2 orders"))
        assertTrue(WoltOfferUiText.groupedDropoffOrdersRegex.matches("Drop-off 2 orders"))
        assertTrue(WoltOfferUiText.isMerchantUiNoise("Drop off 2 orders"))
        assertFalse(WoltOfferUiText.groupedDropoffOrdersRegex.matches("Customer drop-off"))
    }

    @Test
    fun splitCollapsedDisclosureIsNotMistakenForOpenedSheet() {
        assertFalse(
            WoltOfferUiText.hasExpandedMultipleDropoffSheet(
                "Multiple drop-offs\n5 stops"
            )
        )
    }

    @Test
    fun openedSheetRequiresTerminalDoneControl() {
        assertTrue(
            WoltOfferUiText.hasExpandedMultipleDropoffSheet(
                "Multiple drop-offs\n5 stops\nArklių g. 36\nŽirmūnų g. 54\nDone"
            )
        )
    }

    @Test
    fun recognizesIncrementalAddonRouteSummary() {
        assertTrue(WoltOfferUiText.modernRouteSummaryRegex.matches("+2 stops (2.3 km) • 5–12 min extra"))
    }
}
