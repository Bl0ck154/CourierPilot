package com.block154.courierpilot

import kotlin.math.abs

/**
 * Lightweight structural snapshot of the courier app window while a live offer is definitely shown.
 * It intentionally avoids offer text so it still works on Bolt builds where the card is mostly
 * invisible to Accessibility.
 */
internal data class LiveOfferSurfaceSnapshot(
    val windowId: Int,
    val nodeCount: Int,
    val leafCount: Int,
    val bottomNodeCount: Int,
    val interactiveSlots: Set<String>,
    val stableLines: Set<String>,
)

internal object LiveOfferSurfaceEvidence {
    fun normalizeStableLines(lines: Collection<String>): Set<String> = lines.asSequence()
        .map { line ->
            line.trim()
                .lowercase()
                .replace(NUMBER, "#")
                .replace(WHITESPACE, " ")
        }
        .filter { it.length >= 3 }
        .toSet()

    /**
     * Returns true only for a material surface transition. A single metric changing is not enough:
     * this avoids hiding the advisor because a timer ticked, a map panned slightly, or Compose
     * rebuilt one node while the offer is still visible.
     */
    fun materiallyChanged(baseline: LiveOfferSurfaceSnapshot, current: LiveOfferSurfaceSnapshot): Boolean {
        val windowChanged = baseline.windowId != current.windowId
        val nodeDelta = abs(baseline.nodeCount - current.nodeCount)
        val leafDelta = abs(baseline.leafCount - current.leafCount)
        val bottomDelta = abs(baseline.bottomNodeCount - current.bottomNodeCount)

        val structuralShift =
            nodeDelta >= maxOf(4, baseline.nodeCount / 10) ||
                leafDelta >= maxOf(3, baseline.leafCount / 10)
        val bottomShift = bottomDelta >= maxOf(3, baseline.bottomNodeCount / 5)

        val interactiveRetention = retention(baseline.interactiveSlots, current.interactiveSlots)
        val interactiveShift = baseline.interactiveSlots.size >= 2 && interactiveRetention < 0.60

        val lineRetention = retention(baseline.stableLines, current.stableLines)
        val textShift = baseline.stableLines.size >= 2 && lineRetention < 0.45

        return when {
            bottomShift && interactiveShift -> true
            interactiveShift && (structuralShift || textShift || windowChanged) -> true
            textShift && structuralShift -> true
            windowChanged && (interactiveShift || textShift) -> true
            else -> false
        }
    }

    private fun retention(baseline: Set<String>, current: Set<String>): Double {
        if (baseline.isEmpty()) return 1.0
        return baseline.count(current::contains).toDouble() / baseline.size
    }

    private val NUMBER = Regex("\\d+(?:[.,]\\d+)?")
    private val WHITESPACE = Regex("\\s+")
}
