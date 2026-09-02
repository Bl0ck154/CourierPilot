package com.block154.courierpilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveOfferSurfaceEvidenceTest {
    @Test
    fun ignoresSmallLiveOfferRecompositions() {
        val baseline = snapshot(
            nodes = 90,
            leaves = 42,
            bottom = 18,
            slots = setOf("button:3:15", "button:16:15", "map:18:7"),
            lines = setOf("hesburger", "accept", "decline", "# min"),
        )
        val current = snapshot(
            nodes = 92,
            leaves = 43,
            bottom = 19,
            slots = setOf("button:3:15", "button:16:15", "map:18:7"),
            lines = setOf("hesburger", "accept", "decline", "# min"),
        )

        assertFalse(LiveOfferSurfaceEvidence.materiallyChanged(baseline, current))
    }

    @Test
    fun detectsOfferCardBeingReplacedByIdleSurface() {
        val baseline = snapshot(
            nodes = 96,
            leaves = 48,
            bottom = 22,
            slots = setOf("button:3:15", "button:16:15", "map:18:7", "card:10:14"),
            lines = setOf("hesburger", "accept", "decline", "vokieciu g. #"),
        )
        val current = snapshot(
            nodes = 76,
            leaves = 35,
            bottom = 12,
            slots = setOf("nav:4:18", "nav:10:18", "nav:16:18"),
            lines = setOf("you are online", "searching for orders"),
        )

        assertTrue(LiveOfferSurfaceEvidence.materiallyChanged(baseline, current))
    }

    @Test
    fun normalizesChangingNumbersSoTimersDoNotLookLikeNewScreens() {
        val before = LiveOfferSurfaceEvidence.normalizeStableLines(listOf("11 min", "2,49 €", "Vokiečių g. 12"))
        val after = LiveOfferSurfaceEvidence.normalizeStableLines(listOf("10 min", "2,49 €", "Vokiečių g. 12"))

        assertEquals(before, after)
        assertTrue(before.contains("# min"))
    }

    private fun snapshot(
        nodes: Int,
        leaves: Int,
        bottom: Int,
        slots: Set<String>,
        lines: Set<String>,
    ) = LiveOfferSurfaceSnapshot(
        windowId = 5,
        nodeCount = nodes,
        leafCount = leaves,
        bottomNodeCount = bottom,
        interactiveSlots = slots,
        stableLines = lines,
    )
}
