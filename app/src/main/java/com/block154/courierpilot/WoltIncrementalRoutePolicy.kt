package com.block154.courierpilot

internal enum class WoltRouteScopeKind {
    FULL_REMAINING,
    INCREMENTAL_DROPOFF_TAIL,
}

internal data class WoltRouteScope(
    val kind: WoltRouteScopeKind,
    val stops: List<ParsedRouteStop>,
    val requiresCurrentLocation: Boolean,
    val platformDistanceComparable: Boolean,
)

/**
 * Chooses the geometry whose distance is used for Wolt economics.
 *
 * Ordinary offers are scored on the full remaining route: current -> pickup(s) -> drop-off(s).
 * An add-on can reuse an already accepted pickup/customer and append one new customer. Wolt shows
 * the existing customer first and the appended customer second, while its `+N km` money/distance is
 * incremental. A `+1 stop` card with that exact shape is directly identifiable. A `+2 stops` card
 * is promoted to the same tail only when the previously accepted offer proves that the pickup and
 * first drop-off are unchanged; this avoids confusing a genuinely new pickup + drop-off with reuse.
 *
 * Other incremental layouts remain on the historical full-route path as route context until their
 * insertion point can be identified without guessing. That full chain must never become the
 * denominator for incremental money; the live card can fall back to Wolt's explicit +distance.
 */
internal object WoltIncrementalRoutePolicy {
    fun select(parsed: ParsedOffer, acceptedBaseline: ParsedOffer? = null): WoltRouteScope {
        val allStops = parsed.orderedRouteStops.ifEmpty { fallbackStops(parsed) }
        val dropoffs = allStops.filter { it.kind == ParsedRouteStopKind.DROPOFF }
        val sharedPickupSingleStopAddon = parsed.isIncrementalOffer &&
            parsed.incrementalStopCount == 1 &&
            parsed.pickupAddresses.size == 1 &&
            dropoffs.size == 2
        val confirmedSamePickupTwoStopAddon = parsed.isIncrementalOffer &&
            parsed.incrementalStopCount == 2 &&
            parsed.pickupAddresses.size == 1 &&
            dropoffs.size == 2 &&
            acceptedBaseline?.let { baseline ->
                val baselinePickup = baseline.pickupAddresses.singleOrNull() ?: return@let false
                val baselineDropoff = baseline.dropoffAddresses.singleOrNull() ?: return@let false
                sameAddress(baselinePickup, parsed.pickupAddresses.single()) &&
                    sameAddress(baselineDropoff, dropoffs[0].address) &&
                    !sameAddress(baselineDropoff, dropoffs[1].address)
            } == true

        if (sharedPickupSingleStopAddon || confirmedSamePickupTwoStopAddon) {
            return WoltRouteScope(
                kind = WoltRouteScopeKind.INCREMENTAL_DROPOFF_TAIL,
                stops = dropoffs,
                requiresCurrentLocation = false,
                platformDistanceComparable = true,
            )
        }

        return WoltRouteScope(
            kind = WoltRouteScopeKind.FULL_REMAINING,
            stops = allStops,
            requiresCurrentLocation = true,
            platformDistanceComparable = !parsed.isIncrementalOffer,
        )
    }

    /** A full remaining route is context only for add-ons; only a verified tail is a valid denominator. */
    fun canScoreResolvedRoute(parsed: ParsedOffer, scope: WoltRouteScopeKind): Boolean =
        !parsed.isIncrementalOffer || scope == WoltRouteScopeKind.INCREMENTAL_DROPOFF_TAIL

    private fun sameAddress(first: String, second: String): Boolean {
        val firstKey = DeliveryAddressNormalizer.key(first)
        val secondKey = DeliveryAddressNormalizer.key(second)
        if (firstKey != null && secondKey != null) return firstKey == secondKey
        return normalizeAddress(first) == normalizeAddress(second)
    }

    private fun normalizeAddress(value: String): String = value
        .lowercase()
        .replace(Regex("[^\p{L}\p{N}]+"), " ")
        .trim()

    private fun fallbackStops(parsed: ParsedOffer): List<ParsedRouteStop> = buildList {
        parsed.pickupAddresses.forEachIndexed { index, address ->
            add(
                ParsedRouteStop(
                    kind = ParsedRouteStopKind.PICKUP,
                    name = parsed.merchantNames.getOrNull(index) ?: parsed.restaurant,
                    address = address,
                )
            )
        }
        parsed.dropoffAddresses.forEachIndexed { index, address ->
            add(
                ParsedRouteStop(
                    kind = ParsedRouteStopKind.DROPOFF,
                    name = parsed.customerNames.getOrNull(index),
                    address = address,
                )
            )
        }
    }
}
