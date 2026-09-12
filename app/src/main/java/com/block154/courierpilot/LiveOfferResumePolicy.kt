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

    /**
     * Strong same-transaction anchor for Wolt's collapsed/recomposed cards. Once a batch offer has
     * been enriched with hidden drop-offs, the visible card can become sparse again and merchant
     * semantics can briefly be noisy. Matching price + (distance or delivery count), with no
     * conflicting pickup/core numbers, is enough to keep screen discovery from re-arming it.
     */
    fun hasCompatibleCoreIdentity(expected: ParsedOffer, visible: ParsedOffer): Boolean {
        if (isStrictRouteExtension(expected, visible)) return false

        val priceMatches = expected.priceCents != null && visible.priceCents != null &&
            expected.priceCents == visible.priceCents
        val distanceMatches = expected.distanceMeters != null && visible.distanceMeters != null &&
            expected.distanceMeters == visible.distanceMeters
        val countMatches = expected.deliveryCount != null && visible.deliveryCount != null &&
            expected.deliveryCount == visible.deliveryCount

        if (expected.priceCents != null && visible.priceCents != null && !priceMatches) return false
        if (expected.distanceMeters != null && visible.distanceMeters != null && !distanceMatches) return false
        if (expected.deliveryCount != null && visible.deliveryCount != null && !countMatches) return false
        if (strongSetConflict(expected.pickupAddresses, visible.pickupAddresses)) return false

        return priceMatches && (distanceMatches || countMatches)
    }

    fun definitelyDifferent(expected: ParsedOffer, visible: ParsedOffer): Boolean {
        if (isStrictRouteExtension(expected, visible)) return true

        val expectedPrice = expected.priceCents
        val visiblePrice = visible.priceCents
        if (expectedPrice != null && visiblePrice != null && expectedPrice != visiblePrice) return true

        val expectedDistance = expected.distanceMeters
        val visibleDistance = visible.distanceMeters
        if (expectedDistance != null && visibleDistance != null && expectedDistance != visibleDistance) return true

        val expectedDeliveries = expected.deliveryCount
        val visibleDeliveries = visible.deliveryCount
        if (expectedDeliveries != null && visibleDeliveries != null && expectedDeliveries != visibleDeliveries) return true

        // Price + advertised distance + delivery count are the stable numeric identity of a visible
        // Wolt offer. Compose/ML Kit can temporarily drop or mutate merchant/address text while the
        // card itself has not changed. Never let those text-only differences replace an offer whose
        // complete numeric fingerprint is unchanged. A real notification transaction or changed core
        // number still establishes a new offer through the normal capture path.
        if (hasStableNumericFingerprint(expected, visible)) return false

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

    internal fun hasStableNumericFingerprint(expected: ParsedOffer, visible: ParsedOffer): Boolean {
        val expectedPrice = expected.priceCents ?: return false
        val visiblePrice = visible.priceCents ?: return false
        val expectedDistance = expected.distanceMeters ?: return false
        val visibleDistance = visible.distanceMeters ?: return false
        val expectedDeliveries = expected.deliveryCount ?: return false
        val visibleDeliveries = visible.deliveryCount ?: return false

        return expectedPrice == visiblePrice &&
            expectedDistance == visibleDistance &&
            expectedDeliveries == visibleDeliveries
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
