package com.block154.courierpilot

/**
 * Identity of the live courier offer that the user explicitly removed from the screen.
 *
 * It intentionally ignores merchant text: Wolt can expose the merchant only after OCR settles, so
 * adding a title must not resurrect an offer the courier already dismissed.
 */
internal data class LiveOfferDismissalIdentity(
    val packageName: String,
    val priceCents: Int?,
    val distanceMeters: Int?,
    val deliveryCount: Int?,
    val routeFingerprint: String?,
)

internal object LiveOfferUserDismissalPolicy {
    fun identity(packageName: String, parsed: ParsedOffer): LiveOfferDismissalIdentity =
        LiveOfferDismissalIdentity(
            packageName = packageName,
            priceCents = parsed.priceCents,
            distanceMeters = parsed.distanceMeters,
            deliveryCount = parsed.deliveryCount,
            routeFingerprint = if (packageName == CourierSignals.WOLT_PACKAGE) {
                AutomaticWoltRouteCoordinator.routeFingerprint(parsed)
            } else {
                null
            },
        )

    fun isSameOffer(dismissed: LiveOfferDismissalIdentity, incoming: LiveOfferDismissalIdentity): Boolean {
        if (dismissed.packageName != incoming.packageName) return false
        if (!sameWhenKnown(dismissed.priceCents, incoming.priceCents)) return false
        if (!sameWhenKnown(dismissed.distanceMeters, incoming.distanceMeters)) return false
        if (!sameWhenKnown(dismissed.deliveryCount, incoming.deliveryCount)) return false

        val dismissedRoute = dismissed.routeFingerprint
        val incomingRoute = incoming.routeFingerprint
        if (dismissedRoute != null && incomingRoute != null && dismissedRoute != incomingRoute) return false

        // Require at least two stable offer fields, or a verified route identity. This keeps sparse
        // transitional frames from suppressing an unrelated future offer merely because one number
        // happens to match.
        val comparableFields = listOf(
            dismissed.priceCents to incoming.priceCents,
            dismissed.distanceMeters to incoming.distanceMeters,
            dismissed.deliveryCount to incoming.deliveryCount,
        ).count { (first, second) -> first != null && second != null }
        return (dismissedRoute != null && incomingRoute != null) || comparableFields >= 2
    }

    private fun <T> sameWhenKnown(first: T?, second: T?): Boolean =
        first == null || second == null || first == second
}
