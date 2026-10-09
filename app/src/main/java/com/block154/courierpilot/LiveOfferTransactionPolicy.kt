package com.block154.courierpilot

/**
 * Small, deterministic identity rules for transitions between live offer transactions.
 *
 * Android may keep the same courier package/window alive while a new incoming-task notification
 * replaces the previous offer. Package identity alone is therefore not enough to decide that a
 * cached route belongs to the currently visible offer.
 */
internal object LiveOfferTransactionPolicy {
    fun startsNewCapture(result: ArmResult): Boolean = when (result) {
        ArmResult.ARMED,
        ArmResult.REPLACED_SAME_PLATFORM,
        ArmResult.PREEMPTED_STALE_OTHER_PLATFORM -> true
        ArmResult.DUPLICATE_UPDATE,
        ArmResult.QUEUED_OTHER_PLATFORM -> false
    }

    /**
     * Evidence that an incoming capture still belongs to the live card despite a new notification key.
     *
     * Bolt re-posts a ringing offer under fresh keys and the first OCR frame of each re-capture is
     * often sparse (no price, no merchant yet). Real 0.16.0 traces showed every such frame restarting
     * the card at "Route…" and recomputing the same verdict. For Bolt, a frame that contradicts
     * nothing is therefore the same offer; a different price/merchant still replaces the card.
     */
    fun keyChurnCompatible(packageName: String, expected: ParsedOffer, incoming: ParsedOffer): Boolean {
        if (LiveOfferResumePolicy.definitelyDifferent(expected, incoming)) return false
        if (LiveOfferResumePolicy.hasCompatibleCoreIdentity(expected, incoming) ||
            LiveOfferResumePolicy.hasMatchingIdentity(expected, incoming)
        ) return true
        return packageName == CourierSignals.BOLT_PACKAGE
    }

    fun isSameSurface(
        dismissed: Boolean,
        hasCurrentOffer: Boolean,
        expectedPackageName: String,
        currentNotificationKey: String,
        incomingPackageName: String,
        incomingNotificationKey: String,
        compatibleOfferEvidence: Boolean = false,
    ): Boolean {
        if (dismissed || !hasCurrentOffer || expectedPackageName != incomingPackageName) return false

        // A changed notification token is normally a transaction boundary, but Wolt can repost the
        // same ringing offer under a fresh key. When the visible card itself still matches the
        // current offer, the key is only a refreshed lifetime anchor and must not reset the card.
        if (currentNotificationKey.isNotBlank() && incomingNotificationKey.isNotBlank() &&
            currentNotificationKey != incomingNotificationKey
        ) {
            return compatibleOfferEvidence
        }
        return true
    }

    /**
     * Persistence can finish a few milliseconds after Wolt rotates the ringing notification key.
     * A key mismatch alone must not throw away the just-captured offer when the visible/preview
     * screen still proves it is the same card; otherwise the advisor is left forever in loading
     * state and the persisted route is never started.
     */
    fun shouldIgnorePersistedOffer(
        activePackageName: String?,
        activeNotificationKey: String?,
        persistedPackageName: String,
        persistedCaptureKey: String,
        compatibleOfferEvidence: Boolean,
    ): Boolean {
        if (activePackageName.isNullOrBlank() || activePackageName != persistedPackageName) return false
        if (activeNotificationKey.isNullOrBlank() || persistedCaptureKey.isBlank()) return false
        if (activeNotificationKey == persistedCaptureKey) return false
        return !compatibleOfferEvidence
    }
}
