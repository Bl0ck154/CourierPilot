package com.block154.courierpilot

internal enum class WoltRouteFailureAction {
    PRESERVE_LAST_GOOD,
    RETRY,
    DISMISS,
}

/**
 * Keeps transient/full-route failures from degrading a useful live card into a sticky `—/km` shell.
 * A verified route for the current offer is always stronger than a later failure callback. Without a
 * verified route, one transient retry is allowed; deterministic failures are dismissed immediately.
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
