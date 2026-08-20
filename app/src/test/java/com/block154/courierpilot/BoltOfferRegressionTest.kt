package com.block154.courierpilot

import android.graphics.Bitmap
import android.graphics.Color
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
        bitmap.eraseColor(Color.rgb(245, 245, 245))

        val green = Color.rgb(34, 147, 93)
        val blue = Color.rgb(94, 105, 235)
        val cyan = Color.rgb(60, 177, 224)

        // Write exact pixels instead of relying on Robolectric Canvas rasterization. Thin route
        // lines remain present so the density search must choose the marker bodies, not the routes.
        drawThinLine(bitmap, 400, 150, 300, 900, green)
        drawThinLine(bitmap, 300, 900, 300, 160, blue)
        drawDisk(bitmap, 400, 120, 36, green)
        drawDisk(bitmap, 300, 900, 36, blue)
        drawDisk(bitmap, 300, 160, 18, cyan)

        val markers = BoltScreenshotMarkerExtractor.extract(bitmap)
        assertNotNull(markers)
        markers!!

        val current = requireNotNull(markers.currentLocation).screenCenter
        val pickup = requireNotNull(markers.pickup).screenCenter
        val dropoff = requireNotNull(markers.dropoff).screenCenter
        assertTrue(current.x in 290.0..310.0)
        assertTrue(current.y in 150.0..170.0)
        assertTrue(pickup.x in 288.0..312.0)
        assertTrue(pickup.y in 920.0..945.0)
        assertTrue(dropoff.x in 388.0..412.0)
        assertTrue(dropoff.y in 140.0..165.0)

        bitmap.recycle()
    }

    private fun drawDisk(bitmap: Bitmap, centerX: Int, centerY: Int, radius: Int, color: Int) {
        val radiusSquared = radius * radius
        for (y in (centerY - radius).coerceAtLeast(0)..(centerY + radius).coerceAtMost(bitmap.height - 1)) {
            for (x in (centerX - radius).coerceAtLeast(0)..(centerX + radius).coerceAtMost(bitmap.width - 1)) {
                val dx = x - centerX
                val dy = y - centerY
                if (dx * dx + dy * dy <= radiusSquared) bitmap.setPixel(x, y, color)
            }
        }
    }

    private fun drawThinLine(
        bitmap: Bitmap,
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        color: Int,
    ) {
        val steps = maxOf(kotlin.math.abs(endX - startX), kotlin.math.abs(endY - startY)).coerceAtLeast(1)
        for (step in 0..steps) {
            val fraction = step.toDouble() / steps
            val x = (startX + (endX - startX) * fraction).toInt()
            val y = (startY + (endY - startY) * fraction).toInt()
            for (offsetX in -1..1) {
                for (offsetY in -1..1) {
                    val px = x + offsetX
                    val py = y + offsetY
                    if (px in 0 until bitmap.width && py in 0 until bitmap.height) {
                        bitmap.setPixel(px, py, color)
                    }
                }
            }
        }
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
