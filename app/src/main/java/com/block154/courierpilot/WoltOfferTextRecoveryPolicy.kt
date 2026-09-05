package com.block154.courierpilot

/**
 * Decides whether a redesigned Wolt offer still needs one OCR pass before it is archived.
 *
 * Accessibility can expose enough addresses to build a route while omitting the visible merchant
 * title. Persisting immediately in that state produces correct €/km but a generic "Wolt" history
 * row. One OCR pass is cheap and lets the spatially ordered Wolt OCR copy fill that metadata.
 */
internal object WoltOfferTextRecoveryPolicy {
    fun needsOcrBeforePersist(parsed: ParsedOffer, automaticRouting: Boolean): Boolean {
        val routeIncomplete = automaticRouting && AutomaticWoltRouteCoordinator.routeFingerprint(parsed) == null
        val merchantMissing = parsed.merchantNames.isEmpty() && parsed.restaurant.isNullOrBlank()
        return routeIncomplete || merchantMissing
    }
}
