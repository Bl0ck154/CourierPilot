package com.block154.courierpilot

internal enum class WoltRouteFailureAction {
    PRESERVE_LAST_GOOD,
    RETRY,
    RETAIN_UNAVAILABLE,
    DISMISS,
}

/**
 * Keeps transient/full-route failures from degrading a useful live card. A verified route for the
 * current offer is always stronger than a later failure callback. Without a verified route, one
 * transient retry is allowed. Deterministic incomplete-text failures retain one stable owner so the
 * same visible Wolt card cannot enter a hide → screen-rearm loop.
 */
internal object LiveAdvisorRouteFailurePolicy {
    const val MAX_WOLT_RETRIES = 1
    const val WOLT_RETRY_DELAY_MS = 1_200L

    fun decideWoltFinalFailure(
        hasResolvedRoute: Boolean,
        retryCount: Int,
        reason: String?,
    ): WoltRouteFailureAction {
        if (hasResolvedRoute) return WoltRouteFailureAction.PRESERVE_LAST_GOOD
        val normalized = reason.orEmpty().lowercase()
        if (normalized.contains("incomplete textual wolt route")) {
            // This failure means the visible offer itself is still alive but one route role was
            // misclassified/missing. Hiding the advisor lets screen discovery immediately re-arm
            // the exact same card, producing the observed hide/show loop. Keep one stable owner
            // instead; a later richer Accessibility frame can still update the same offer.
            return WoltRouteFailureAction.RETAIN_UNAVAILABLE
        }
        if (retryCount < MAX_WOLT_RETRIES && isRetryableWoltFailure(reason)) {
            return WoltRouteFailureAction.RETRY
        }
        return WoltRouteFailureAction.DISMISS
    }

    internal fun isRetryableWoltFailure(reason: String?): Boolean {
        val normalized = reason.orEmpty().lowercase()
        if (normalized.isBlank()) return true
        return listOf(
            "route endpoint disabled",
            "location permission missing",
            "incomplete textual wolt route",
            "resolved stop coordinates are inconsistent with wolt distance",
        ).none(normalized::contains)
    }
}
