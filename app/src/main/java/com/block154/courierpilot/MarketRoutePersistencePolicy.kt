package com.block154.courierpilot

/** Keeps market/history economics semantically aligned with the money shown on the offer. */
internal object MarketRoutePersistencePolicy {
    fun shouldPersistFullRoute(
        platform: String,
        parsed: ParsedOffer,
        comparison: RouteComparison? = null,
    ): Boolean {
        // Wolt add-ons are a different economic product from ordinary full offers. Exact one-stop
        // layouts may now resolve a valid incremental dropoff-to-dropoff tail, while ambiguous
        // layouts can still use Wolt's +distance live fallback. Keep every add-on out of canonical
        // personal/city market samples so incremental money never trains full-offer thresholds.
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
