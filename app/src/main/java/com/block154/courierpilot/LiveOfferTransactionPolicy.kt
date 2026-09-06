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

    fun isSameSurface(
        dismissed: Boolean,
        hasCurrentOffer: Boolean,
        expectedPackageName: String,
        currentNotificationKey: String,
        incomingPackageName: String,
        incomingNotificationKey: String,
    ): Boolean {
        if (dismissed || !hasCurrentOffer || expectedPackageName != incomingPackageName) return false

        // When both sides have a concrete offer token, a changed token is a hard transaction
        // boundary even if Wolt/Bolt keeps exactly the same Activity and Compose window alive.
        if (currentNotificationKey.isNotBlank() && incomingNotificationKey.isNotBlank() &&
            currentNotificationKey != incomingNotificationKey
        ) {
            return false
        }
        return true
    }
}
