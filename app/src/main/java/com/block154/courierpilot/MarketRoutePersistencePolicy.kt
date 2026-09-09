package com.block154.courierpilot

/** Keeps market/history economics semantically aligned with the money shown on the offer. */
internal object MarketRoutePersistencePolicy {
    fun shouldPersistFullRoute(
        platform: String,
        parsed: ParsedOffer,
        comparison: RouteComparison? = null,
    ): Boolean {
        // Wolt add-ons expose incremental money + incremental distance while CourierPilot resolves
        // the full remaining route through already-accepted and newly offered stops. Persisting that
        // full chain would create a false denominator and poison personal/city market samples.
        if (platform.equals("Wolt", ignoreCase = true) && parsed.isIncrementalOffer) return false

        // Ordinary Wolt economics are defined from the walking+cycling pair. If one profile remains
        // unavailable even after the comparison retry, do not persist the surviving leg as trusted
        // history/market truth; a later restore must not resurrect a different denominator.
        if (platform.equals("Wolt", ignoreCase = true) && comparison != null &&
            (comparison.pedestrian.isFailure || comparison.cycleway.isFailure)
        ) return false

        return true
    }
}
