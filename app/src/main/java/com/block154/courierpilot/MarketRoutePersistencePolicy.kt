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

        // Live scoring may fall back to one successful Valhalla profile so the courier still gets a
        // useful verdict. Long-lived history/market truth is stricter: persist ordinary Wolt route
        // economics only when the walking+cycling pair is complete, so a later restore or adaptive
        // threshold sample does not turn a temporary fallback denominator into canonical history.
        if (platform.equals("Wolt", ignoreCase = true) && comparison != null &&
            (comparison.pedestrian.isFailure || comparison.cycleway.isFailure)
        ) return false

        return true
    }
}
