package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WoltEarningsSpatialOcrTest {
    @Test
    fun choosesMoneyPhysicallyNearestEarningsLabelRegardlessOfTextOrder() {
        val lines = listOf(
            OcrSpatialLine(100, 130, 10, 180, "Account €28.00"),
            OcrSpatialLine(720, 760, 60, 250, "€6.42"),
            OcrSpatialLine(770, 805, 60, 500, "Expected earnings for the full delivery"),
            OcrSpatialLine(900, 930, 10, 180, "€12.00"),
        )

        assertEquals(
            MoneyAmount(642, "EUR", 2),
            WoltEarningsSpatialOcr.findMoney(lines, imageHeight = 1200),
        )
    }

    @Test
    fun ignoresUnrelatedMoneyWhenNoEarningsAnchorExists() {
        val lines = listOf(
            OcrSpatialLine(100, 130, 10, 180, "€28.00"),
            OcrSpatialLine(720, 760, 60, 250, "€6.42"),
        )
        assertNull(WoltEarningsSpatialOcr.findMoney(lines, imageHeight = 1200))
    }

    @Test
    fun blockAnchorCanRecoverSplitEarningsLabel() {
        val lines = listOf(
            OcrSpatialLine(710, 750, 60, 250, "€4.18"),
            OcrSpatialLine(770, 790, 60, 300, "Expected earnings for"),
            OcrSpatialLine(792, 812, 60, 300, "the full delivery"),
        )
        val anchors = listOf(
            OcrSpatialLine(770, 812, 60, 500, "Expected earnings for the full delivery"),
        )
        assertEquals(
            MoneyAmount(418, "EUR", 2),
            WoltEarningsSpatialOcr.findMoney(lines, imageHeight = 1200, extraAnchors = anchors),
        )
    }
}
