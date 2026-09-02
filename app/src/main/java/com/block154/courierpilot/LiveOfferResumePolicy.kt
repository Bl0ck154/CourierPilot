package com.block154.courierpilot

import java.util.Locale

/** Conservative identity guard used only when restoring a temporarily hidden live card. */
internal object LiveOfferResumePolicy {
    fun definitelyDifferent(expected: ParsedOffer, visible: ParsedOffer): Boolean {
        val expectedPrice = expected.priceCents
        val visiblePrice = visible.priceCents
        if (expectedPrice != null && visiblePrice != null && expectedPrice != visiblePrice) return true

        val expectedRestaurant = normalize(expected.restaurant)
        val visibleRestaurant = normalize(visible.restaurant)
        if (expectedRestaurant != null && visibleRestaurant != null && !looselyMatches(expectedRestaurant, visibleRestaurant)) return true

        if (strongSetConflict(expected.pickupAddresses, visible.pickupAddresses)) return true
        if (strongSetConflict(expected.merchantNames, visible.merchantNames)) return true
        return false
    }

    private fun strongSetConflict(expected: List<String>, visible: List<String>): Boolean {
        val left = expected.mapNotNull(::normalize).toSet()
        val right = visible.mapNotNull(::normalize).toSet()
        if (left.isEmpty() || right.isEmpty()) return false
        return left.none { expectedValue -> right.any { visibleValue -> looselyMatches(expectedValue, visibleValue) } }
    }

    private fun looselyMatches(left: String, right: String): Boolean =
        left == right || left.contains(right) || right.contains(left)

    private fun normalize(value: String?): String? = value
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        ?.replace(Regex("""\s+"""), " ")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}
