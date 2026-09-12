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
 * A one-stop add-on from the same visible pickup is different. Wolt shows the already accepted
 * customer first and the newly appended customer second, while its `+N km` money/distance is only
 * incremental. In that exact structure the additional travel is therefore drop-off #1 -> #2.
 *
 * Other incremental layouts remain on the historical full-route path until their insertion point
 * can be identified without guessing. The live card can still fall back to Wolt's explicit
 * incremental distance for those layouts.
 */
internal object WoltIncrementalRoutePolicy {
    fun select(parsed: ParsedOffer): WoltRouteScope {
        val allStops = parsed.orderedRouteStops.ifEmpty { fallbackStops(parsed) }
        val dropoffs = allStops.filter { it.kind == ParsedRouteStopKind.DROPOFF }
        val sharedPickupSingleStopAddon = parsed.isIncrementalOffer &&
            parsed.incrementalStopCount == 1 &&
            parsed.pickupAddresses.size == 1 &&
            dropoffs.size == 2

        if (sharedPickupSingleStopAddon) {
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
