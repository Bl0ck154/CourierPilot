package com.block154.courierpilot

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfferScreenshotMaskingTest {
    class TestService : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
        override fun onInterrupt() = Unit
    }

    @Test
    fun screenToBitmapMappingInflatesAndClampsToPhysicalBounds() {
        assertEquals(
            Rect(35, 145, 210, 235),
            OfferOverlayBitmapMask.mapRect(Rect(80, 310, 400, 440), 800, 1600, 400, 800, 10),
        )
        assertEquals(
            Rect(0, 0, 100, 50),
            OfferOverlayBitmapMask.mapRect(Rect(-20, -30, 200, 100), 800, 1600, 400, 800),
        )
        assertNull(OfferOverlayBitmapMask.mapRect(null, 800, 1600, 400, 800))
        assertNull(OfferOverlayBitmapMask.mapRect(Rect(1, 1, 2, 2), 0, 1600, 400, 800))
    }

    @Test
    fun ownEuroPerKmCardTextIsNotLeftInBitmapForPriceOcrOrProof() {
        val bitmap = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888)
        val surrounding = Color.rgb(233, 234, 235)
        bitmap.eraseColor(surrounding)
        val card = Rect(50, 165, 250, 240)
        Canvas(bitmap).apply {
            drawRect(card, Paint().apply { color = Color.rgb(20, 30, 50) })
            drawText("€9.99/km", 70f, 208f, Paint().apply {
                color = Color.WHITE
                textSize = 32f
            })
        }
        assertEquals(Color.WHITE, Color.WHITE) // Synthetic glyph and dark card exist before masking.
        val mapped = OfferOverlayBitmapMask.mapRect(
            Rect(100, 330, 500, 480), 800, 1600, 400, 800, 12,
        )
        assertNotNull(mapped)
        assertTrue(OfferOverlayBitmapMask.paint(bitmap, mapped))
        // Card, glyph and margin are a single flat neutral fill. Price OCR cannot see its text.
        for (y in 165 until 240 step 7) for (x in 50 until 250 step 9) {
            assertEquals(surrounding, bitmap.getPixel(x, y))
        }
        assertEquals(surrounding, bitmap.getPixel(15, 15))
        bitmap.recycle()
    }

    @Test
    fun overlayOnlyIntersectsMapWhenRectOverlapsMapCrop() {
        assertTrue(OfferOverlayBitmapMask.intersectsMap(Rect(20, 110, 200, 220), 800))
        assertFalse(OfferOverlayBitmapMask.intersectsMap(Rect(20, 650, 200, 720), 800))
        assertFalse(OfferOverlayBitmapMask.intersectsMap(null, 800))
    }

    @Test
    fun cleanCaptureBudgetIsAtMostOnePerOfferAndRequiresMissingPinsAndOverlap() {
        val budget = OfferCleanCaptureBudget()
        assertFalse(budget.claim("first", false, true))
        assertFalse(budget.claim("first", true, false))
        assertTrue(budget.claim("first", true, true))
        assertFalse(budget.claim("first", true, true))
        assertFalse(budget.claim("first", true, true))
        assertTrue(budget.claim("next", true, true))
    }

    @Test
    fun regularCaptureDoesNotChangeOverlayAlphaEvenOnBolt() {
        val service = Robolectric.buildService(TestService::class.java).create().get()
        val view = LiveAdvisorOverlayView(service, {}, {}, { "Bolt" })
        view.ensure()
        val root = LiveAdvisorOverlayView::class.java.getDeclaredField("root").apply { isAccessible = true }
            .get(view) as? android.view.View
        assertNotNull(root)
        val alphaBefore = root!!.alpha
        view.setCaptureSuppressed(true)
        assertEquals(alphaBefore, root.alpha, 0.001f)
        view.setCaptureSuppressed(false)
        assertEquals(alphaBefore, root.alpha, 0.001f)
        assertFalse(OfferOverlayCapturePolicy.shouldChangeAlpha(false, false, false))
        assertFalse(OfferOverlayCapturePolicy.shouldChangeAlpha(true, true, false))
        assertFalse(OfferOverlayCapturePolicy.shouldChangeAlpha(true, false, true))
        assertTrue(OfferOverlayCapturePolicy.shouldChangeAlpha(true, false, false))
        view.detach(animate = false)
    }
}
