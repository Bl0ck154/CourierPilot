package com.block154.courierpilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WoltOfferUiTextTest {
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
}
