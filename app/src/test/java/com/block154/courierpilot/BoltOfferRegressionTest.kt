package com.block154.courierpilot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BoltOfferRegressionTest {

    @Test
    fun merchantOcrDriftDoesNotDuplicateOneBoltOffer() {
        val first = boltRecord(
            capturedAt = 1_000_000L,
            merchant = "AYLIMO.",
        )
        val second = boltRecord(
            capturedAt = 1_008_000L,
            merchant = "Do",
        )
        val correct = boltRecord(
            capturedAt = 1_015_000L,
            merchant = "Kebab Station (Sodų str.)",
        )

        assertTrue(OfferDedupeIdentity.isSameLiveOffer(first, second))
        assertTrue(OfferDedupeIdentity.isSameLiveOffer(first, correct))
        assertEquals(
            OfferDedupeIdentity.burstFingerprint(first),
            OfferDedupeIdentity.burstFingerprint(second),
        )
        assertEquals(
            OfferDedupeIdentity.burstFingerprint(first),
            OfferDedupeIdentity.burstFingerprint(correct),
        )
    }

    @Test
    fun screenshotDetectorRecoversCurrentPickupAndCustomerMarkers() {
        val bitmap = Bitmap.createBitmap(700, 1_500, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(245, 245, 245))

        val green = Paint().apply {
            color = Color.rgb(34, 147, 93)
            strokeWidth = 3f
        }
        val blue = Paint().apply {
            color = Color.rgb(94, 105, 235)
            strokeWidth = 3f
        }
        val cyan = Paint().apply { color = Color.rgb(60, 177, 224) }

        // Thin route lines should not beat the dense marker bodies.
        canvas.drawLine(400f, 150f, 300f, 900f, green)
        canvas.drawLine(300f, 900f, 300f, 160f, blue)
        canvas.drawCircle(400f, 120f, 36f, green)
        canvas.drawCircle(300f, 900f, 36f, blue)
        canvas.drawCircle(300f, 160f, 18f, cyan)

        val markers = BoltScreenshotMarkerExtractor.extract(bitmap)
        assertNotNull(markers)
        markers!!

        val current = requireNotNull(markers.currentLocation).screenCenter
        val pickup = requireNotNull(markers.pickup).screenCenter
        val dropoff = requireNotNull(markers.dropoff).screenCenter
        assertTrue(current.x in 290.0..310.0)
        assertTrue(current.y in 150.0..170.0)
        assertTrue(pickup.x in 288.0..312.0)
        assertTrue(pickup.y in 920.0..940.0)
        assertTrue(dropoff.x in 388.0..412.0)
        assertTrue(dropoff.y in 140.0..160.0)

        bitmap.recycle()
    }

    private fun boltRecord(capturedAt: Long, merchant: String) = OfferRecord(
        capturedAt = capturedAt,
        platform = "Bolt",
        packageName = CourierSignals.BOLT_PACKAGE,
        priceCents = 259,
        distanceMeters = null,
        restaurant = merchant,
        screenshotUri = "",
        screenshotFilename = "",
        rawText = "",
        merchantNames = listOf(merchant),
        pickupAddresses = listOf("Sodų g. 20B Vilnius LT-03211"),
        deliveryCount = 1,
        estimatedMinutesMin = 19,
        estimatedMinutesMax = 19,
    )
}
