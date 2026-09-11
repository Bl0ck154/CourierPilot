package com.block154.courierpilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveOfferTransactionPolicyTest {
    @Test
    fun replacedSamePlatformNotificationStartsNewCapture() {
        assertTrue(LiveOfferTransactionPolicy.startsNewCapture(ArmResult.REPLACED_SAME_PLATFORM))
        assertTrue(LiveOfferTransactionPolicy.startsNewCapture(ArmResult.ARMED))
        assertTrue(LiveOfferTransactionPolicy.startsNewCapture(ArmResult.PREEMPTED_STALE_OTHER_PLATFORM))
        assertFalse(LiveOfferTransactionPolicy.startsNewCapture(ArmResult.DUPLICATE_UPDATE))
        assertFalse(LiveOfferTransactionPolicy.startsNewCapture(ArmResult.QUEUED_OTHER_PLATFORM))
    }

    @Test
    fun changedWoltNotificationKeyWithoutScreenEvidenceIsOfferBoundary() {
        assertFalse(
            LiveOfferTransactionPolicy.isSameSurface(
                dismissed = false,
                hasCurrentOffer = true,
                expectedPackageName = CourierSignals.WOLT_PACKAGE,
                currentNotificationKey = "old-offer-key",
                incomingPackageName = CourierSignals.WOLT_PACKAGE,
                incomingNotificationKey = "new-offer-key",
            ),
        )
    }

    @Test
    fun changedWoltNotificationKeyKeepsSurfaceWhenVisibleOfferIdentityMatches() {
        assertTrue(
            LiveOfferTransactionPolicy.isSameSurface(
                dismissed = false,
                hasCurrentOffer = true,
                expectedPackageName = CourierSignals.WOLT_PACKAGE,
                currentNotificationKey = "old-offer-key",
                incomingPackageName = CourierSignals.WOLT_PACKAGE,
                incomingNotificationKey = "refreshed-key",
                compatibleOfferEvidence = true,
            ),
        )
    }

    @Test
    fun persistedOfferSurvivesNotificationKeyRotationWhenScreenIdentityMatches() {
        assertFalse(
            LiveOfferTransactionPolicy.shouldIgnorePersistedOffer(
                activePackageName = CourierSignals.WOLT_PACKAGE,
                activeNotificationKey = "new-key",
                persistedPackageName = CourierSignals.WOLT_PACKAGE,
                persistedCaptureKey = "old-key",
                compatibleOfferEvidence = true,
            ),
        )
    }

    @Test
    fun persistedOfferIsIgnoredWhenNotificationKeyRotatedToDifferentVisibleOffer() {
        assertTrue(
            LiveOfferTransactionPolicy.shouldIgnorePersistedOffer(
                activePackageName = CourierSignals.WOLT_PACKAGE,
                activeNotificationKey = "new-key",
                persistedPackageName = CourierSignals.WOLT_PACKAGE,
                persistedCaptureKey = "old-key",
                compatibleOfferEvidence = false,
            ),
        )
    }

    @Test
    fun repeatedUpdateOfExactNotificationKeepsSameSurface() {
        assertTrue(
            LiveOfferTransactionPolicy.isSameSurface(
                dismissed = false,
                hasCurrentOffer = true,
                expectedPackageName = CourierSignals.WOLT_PACKAGE,
                currentNotificationKey = "same-offer-key",
                incomingPackageName = CourierSignals.WOLT_PACKAGE,
                incomingNotificationKey = "same-offer-key",
            ),
        )
    }
}
