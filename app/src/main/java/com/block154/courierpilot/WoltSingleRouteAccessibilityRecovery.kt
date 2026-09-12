package com.block154.courierpilot

/**
 * Last-mile recovery for an ordinary Wolt offer whose visible Compose/OCR projection exposes the
 * stable numeric card identity but temporarily drops both route rows.
 *
 * The full Accessibility tree may contain stale Compose semantics, so it is never trusted for
 * offer lifetime or identity. It may enrich route fields only when its own parsed price and Wolt
 * distance exactly match the already-visible offer and it contains exactly one pickup + one
 * customer drop-off. Batch/add-on offers stay on their dedicated recovery path.
 */
internal object WoltSingleRouteAccessibilityRecovery {
    fun recover(visible: ParsedOffer, fullTreeText: String): ParsedOffer? {
        if (fullTreeText.isBlank()) return null
        if (visible.isIncrementalOffer) return null
        val visiblePrice = visible.priceCents ?: return null
        val visibleDistance = visible.distanceMeters ?: return null
        if (AutomaticWoltRouteCoordinator.routeFingerprint(visible) != null) return null

        val hidden = OfferParser.parse(fullTreeText)
        if (hidden.isIncrementalOffer) return null
        if (hidden.priceCents != visiblePrice || hidden.distanceMeters != visibleDistance) return null
        if (hidden.pickupAddresses.size != 1 || hidden.dropoffAddresses.size != 1) return null
        if ((hidden.deliveryCount ?: 1) != 1) return null
        if (AutomaticWoltRouteCoordinator.routeFingerprint(hidden) == null) return null

        return visible.copy(
            restaurant = visible.restaurant ?: hidden.restaurant,
            merchantNames = visible.merchantNames.ifEmpty { hidden.merchantNames },
            pickupAddresses = hidden.pickupAddresses,
            customerNames = hidden.customerNames,
            dropoffAddresses = hidden.dropoffAddresses,
            deliveryCount = visible.deliveryCount ?: hidden.deliveryCount ?: 1,
            orderedRouteStops = hidden.orderedRouteStops,
        )
    }
}
