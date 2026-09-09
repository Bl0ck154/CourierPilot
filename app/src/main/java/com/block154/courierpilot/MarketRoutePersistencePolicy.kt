package com.block154.courierpilot

/** Keeps market/history economics semantically aligned with the money shown on the offer. */
internal object MarketRoutePersistencePolicy {
    fun shouldPersistFullRoute(platform: String, parsed: ParsedOffer): Boolean {
        // Wolt add-ons expose incremental money + incremental distance while CourierPilot resolves
        // the full remaining route through already-accepted and newly offered stops. Persisting that
        // full chain would create a false denominator and poison personal/city market samples.
        return !(platform.equals("Wolt", ignoreCase = true) && parsed.isIncrementalOffer)
    }
}
