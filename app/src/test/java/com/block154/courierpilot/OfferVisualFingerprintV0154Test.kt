package com.block154.courierpilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferVisualFingerprintV0154Test {
    @Test
    fun nearVisualFingerprintSuppressesBoltDuplicateEvenWhenOcrDistanceDrifts() {
        val first = OfferRecord(
            capturedAt = 1_000_000L,
            platform = "Bolt",
            packageName = CourierSignals.BOLT_PACKAGE,
            priceCents = 550,
            distanceMeters = 3_200,
            restaurant = null,
            screenshotUri = "content://one",
            screenshotFilename = "one.png",
            rawText = "first frame",
            visualFingerprint = "0000000000000000",
        )
        val second = first.copy(
            capturedAt = first.capturedAt + 8_000L,
            distanceMeters = 7_900,
            rawText = "same card, noisy OCR/map",
            visualFingerprint = "0000000000000003",
        )

        assertTrue(OfferDedupeIdentity.isSameLiveOffer(first, second))
    }

    @Test
    fun nearVisualFingerprintDoesNotCollapseBoltOfferAfterShortBurstWindow() {
        val first = OfferRecord(
            capturedAt = 2_000_000L,
            platform = "Bolt",
            packageName = CourierSignals.BOLT_PACKAGE,
            priceCents = 550,
            distanceMeters = null,
            restaurant = null,
            screenshotUri = "content://one",
            screenshotFilename = "one.png",
            rawText = "first frame",
            visualFingerprint = "0000000000000000",
        )
        val later = first.copy(
            capturedAt = first.capturedAt + 60_000L,
            rawText = "later visually similar card",
            visualFingerprint = "0000000000000003",
        )

        assertFalse(OfferDedupeIdentity.isSameLiveOffer(first, later))
    }

    @Test
    fun veryDifferentVisualFingerprintDoesNotByItselfCollapseDistinctBoltOffer() {
        assertFalse(OfferVisualFingerprint.isNear("0000000000000000", "ffffffffffffffff"))
    }
}
