package com.block154.courierpilot

import java.util.Locale

/** Conservative identity guard used only when restoring a temporarily hidden live card. */
internal object LiveOfferResumePolicy {
    fun hasMatchingIdentity(expected: ParsedOffer, visible: ParsedOffer): Boolean {
        if (definitelyDifferent(expected, visible)) return false

        val expectedPrice = expected.priceCents
        val visiblePrice = visible.priceCents
        if (expectedPrice != null && visiblePrice != null && expectedPrice == visiblePrice) return true

        val expectedRestaurant = normalize(expected.restaurant)
        val visibleRestaurant = normalize(visible.restaurant)
        if (expectedRestaurant != null && visibleRestaurant != null && looselyMatches(expectedRestaurant, visibleRestaurant)) {
            return true
        }

        if (setOverlaps(expected.pickupAddresses, visible.pickupAddresses)) return true
        if (setOverlaps(expected.dropoffAddresses, visible.dropoffAddresses)) return true
        if (setOverlaps(expected.merchantNames, visible.merchantNames)) return true
        return false
    }

    fun definitelyDifferent(expected: ParsedOffer, visible: ParsedOffer): Boolean {
        if (isStrictRouteExtension(expected, visible)) return true

        val expectedPrice = expected.priceCents
        val visiblePrice = visible.priceCents
        if (expectedPrice != null && visiblePrice != null && expectedPrice != visiblePrice) return true

        // Accessibility/OCR can briefly produce a bogus merchant title while Wolt recomposes the
        // same card. A matching pickup/drop-off is stronger identity evidence than that noisy text.
        if (setOverlaps(expected.pickupAddresses, visible.pickupAddresses)) return false
        if (setOverlaps(expected.dropoffAddresses, visible.dropoffAddresses)) return false

        val expectedRestaurant = normalize(expected.restaurant)
        val visibleRestaurant = normalize(visible.restaurant)
        if (expectedRestaurant != null && visibleRestaurant != null && !looselyMatches(expectedRestaurant, visibleRestaurant)) return true

        if (strongSetConflict(expected.pickupAddresses, visible.pickupAddresses)) return true
        if (strongSetConflict(expected.merchantNames, visible.merchantNames)) return true
        return false
    }

    /**
     * An add-on offer can keep every old customer address and append one or many new deliveries.
     * Shared addresses are therefore not sufficient to call it the same live screen. Only classify
     * a strict extension when both sides know their delivery counts, all old drop-offs survive, at
     * least one new drop-off appears, and the pickup/merchant identity does not conflict.
     */
    internal fun isStrictRouteExtension(expected: ParsedOffer, visible: ParsedOffer): Boolean {
        val expectedCount = expected.deliveryCount ?: return false
        val visibleCount = visible.deliveryCount ?: return false
        if (visibleCount <= expectedCount) return false

        val expectedDropoffs = expected.dropoffAddresses.mapNotNull(::addressIdentity).distinct()
        val visibleDropoffs = visible.dropoffAddresses.mapNotNull(::addressIdentity).distinct()
        if (expectedDropoffs.isEmpty() || visibleDropoffs.size <= expectedDropoffs.size) return false
        if (!expectedDropoffs.all(visibleDropoffs::contains)) return false

        val pickupsCompatible = expected.pickupAddresses.isEmpty() || visible.pickupAddresses.isEmpty() ||
            addressSetsOverlap(expected.pickupAddresses, visible.pickupAddresses)
        val merchantsCompatible = expected.merchantNames.isEmpty() || visible.merchantNames.isEmpty() ||
            setOverlaps(expected.merchantNames, visible.merchantNames)
        return pickupsCompatible && merchantsCompatible
    }

    private fun addressSetsOverlap(expected: List<String>, visible: List<String>): Boolean {
        val left = expected.mapNotNull(::addressIdentity).toSet()
        val right = visible.mapNotNull(::addressIdentity).toSet()
        return left.isNotEmpty() && right.isNotEmpty() && left.any(right::contains)
    }

    private fun addressIdentity(value: String): String? =
        DeliveryAddressNormalizer.key(value) ?: normalize(value)

    private fun setOverlaps(expected: List<String>, visible: List<String>): Boolean {
        val left = expected.mapNotNull(::normalize).toSet()
        val right = visible.mapNotNull(::normalize).toSet()
        if (left.isEmpty() || right.isEmpty()) return false
        return left.any { expectedValue -> right.any { visibleValue -> looselyMatches(expectedValue, visibleValue) } }
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
