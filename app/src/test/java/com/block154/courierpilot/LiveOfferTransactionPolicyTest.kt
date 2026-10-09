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

    private val boltOffer = OfferParser.parse(
        """
        Hesburger (Vokiečių str.)
        Vokiečių g. 12, Vilnius, 01130 Vilniaus m. sav.
        ~4 min
        ~14 min
        18 min, 3,41 €
        Decline
        """.trimIndent()
    )

    @Test
    fun sparseBoltReCaptureAfterNotificationRepostKeepsTheSameCard() {
        val sparseFrame = OfferParser.parse("Decline")
        assertTrue(LiveOfferTransactionPolicy.keyChurnCompatible(CourierSignals.BOLT_PACKAGE, boltOffer, sparseFrame))
        assertTrue(LiveOfferTransactionPolicy.keyChurnCompatible(CourierSignals.BOLT_PACKAGE, boltOffer, boltOffer))
    }

    @Test
    fun differentBoltPriceOrMerchantStillReplacesTheCard() {
        val otherPrice = OfferParser.parse(
            """
            Hesburger (Vokiečių str.)
            Vokiečių g. 12, Vilnius, 01130 Vilniaus m. sav.
            ~4 min
            ~9 min
            12 min, 2,55 €
            Decline
            """.trimIndent()
        )
        assertFalse(LiveOfferTransactionPolicy.keyChurnCompatible(CourierSignals.BOLT_PACKAGE, boltOffer, otherPrice))
    }

    @Test
    fun sparseWoltFrameIsNotEnoughToSurviveANewNotificationKey() {
        val sparseFrame = OfferParser.parse("Decline")
        assertFalse(LiveOfferTransactionPolicy.keyChurnCompatible(CourierSignals.WOLT_PACKAGE, boltOffer, sparseFrame))
    }
}
