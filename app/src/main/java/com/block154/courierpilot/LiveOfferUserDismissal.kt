package com.block154.courierpilot

import java.util.Locale

/**
 * Screen-only Bolt discovery rarely provides distance or delivery count. Keep the pickup/merchant
 * identity when the courier hides the card; Wolt retains its original numeric/route guard.
 */
internal data class LiveOfferDismissalIdentity(
    val packageName: String,
    val priceCents: Int?,
    val distanceMeters: Int?,
    val deliveryCount: Int?,
    val routeFingerprint: String?,
    val pickupKey: String? = null,
    val estimatedMinutesMin: Int? = null,
    val merchantKey: String? = null,
)

internal enum class DismissalForegroundTransition { NONE, LEFT, RETURNED }

internal object LiveOfferUserDismissalPolicy {
    private val TRANSIENT_PACKAGES = setOf(
        "com.android.systemui", "com.oplus.screenshot", "com.coloros.screenshot",
    )

    /**
     * Foreground changes after a manual swipe. System UI (shade, screenshot) is not "leaving";
     * a launcher or any other app is. Returning to the dismissed courier app after leaving it ends
     * the dismissal.
     */
    fun foregroundTransition(
        dismissedPackage: String,
        foregroundPackage: String,
        ownPackage: String,
        leftCourierApp: Boolean,
    ): DismissalForegroundTransition = when {
        foregroundPackage.isBlank() -> DismissalForegroundTransition.NONE
        foregroundPackage == dismissedPackage ->
            if (leftCourierApp) DismissalForegroundTransition.RETURNED else DismissalForegroundTransition.NONE
        foregroundPackage == ownPackage || foregroundPackage in TRANSIENT_PACKAGES -> DismissalForegroundTransition.NONE
        else -> DismissalForegroundTransition.LEFT
    }

    fun identity(packageName: String, parsed: ParsedOffer): LiveOfferDismissalIdentity {
        val merchantKey = merchantIdentity(parsed.merchantNames.firstOrNull() ?: parsed.restaurant)
        return LiveOfferDismissalIdentity(
            packageName = packageName,
            priceCents = parsed.priceCents,
            distanceMeters = parsed.distanceMeters,
            deliveryCount = parsed.deliveryCount,
            routeFingerprint = if (packageName == CourierSignals.WOLT_PACKAGE) {
                AutomaticWoltRouteCoordinator.routeFingerprint(parsed)
            } else {
                null
            },
            pickupKey = parsed.pickupAddresses.firstOrNull()
                ?.let { DeliveryAddressNormalizer.identity(it)?.key } ?: merchantKey,
            estimatedMinutesMin = parsed.estimatedMinutesMin,
            merchantKey = merchantKey,
        )
    }

    fun isSameOffer(dismissed: LiveOfferDismissalIdentity, incoming: LiveOfferDismissalIdentity): Boolean {
        if (dismissed.packageName != incoming.packageName) return false
        if (!sameWhenKnown(dismissed.priceCents, incoming.priceCents)) return false
        if (!sameWhenKnown(dismissed.deliveryCount, incoming.deliveryCount)) return false

        if (dismissed.packageName == CourierSignals.BOLT_PACKAGE) {
            if (!sameWhenKnown(dismissed.estimatedMinutesMin, incoming.estimatedMinutesMin)) return false
            val samePickup = dismissed.pickupKey != null && dismissed.pickupKey == incoming.pickupKey
            val sameMerchant = dismissed.merchantKey != null && dismissed.merchantKey == incoming.merchantKey
            return samePickup || sameMerchant
        }

        // Preserve Wolt's existing strict numeric/route fingerprint guard.
        if (!sameWhenKnown(dismissed.distanceMeters, incoming.distanceMeters)) return false
        val dismissedRoute = dismissed.routeFingerprint
        val incomingRoute = incoming.routeFingerprint
        if (dismissedRoute != null && incomingRoute != null && dismissedRoute != incomingRoute) return false
        val comparableFields = listOf(
            dismissed.priceCents to incoming.priceCents,
            dismissed.distanceMeters to incoming.distanceMeters,
            dismissed.deliveryCount to incoming.deliveryCount,
        ).count { (first, second) -> first != null && second != null }
        return (dismissedRoute != null && incomingRoute != null) || comparableFields >= 2
    }

    internal fun merchantIdentity(raw: String?): String? = raw
        ?.lowercase(Locale.ROOT)
        ?.replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    private fun <T> sameWhenKnown(first: T?, second: T?): Boolean =
        first == null || second == null || first == second
}
