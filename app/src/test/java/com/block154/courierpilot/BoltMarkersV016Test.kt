package com.block154.courierpilot

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BoltMarkersV016Test {
    private val cyan = Color.rgb(60, 177, 224)
    private val blue = Color.rgb(94, 105, 235)
    private val green = Color.rgb(34, 147, 93)

    @Test fun greenParkCircleDoesNotMasqueradeAsCustomerPin() {
        val bitmap = blank()
        disk(bitmap, 110, 150, 18, cyan)
        disk(bitmap, 210, 480, 36, blue)
        disk(bitmap, 450, 600, 11, green)
        assertNull(BoltScreenshotMarkerExtractor.extract(bitmap))
        bitmap.recycle()
    }

    @Test fun screenshotMapBoundaryCanExtendBelowLegacyCrop() {
        val bitmap = blank()
        disk(bitmap, 110, 150, 18, cyan)
        disk(bitmap, 210, 480, 36, blue)
        disk(bitmap, 450, 1180, 36, green)
        assertNull(BoltScreenshotMarkerExtractor.extract(bitmap))
        val markers = BoltScreenshotMarkerExtractor.extract(bitmap, mapBottomPx = 1350)
        assertNotNull(markers)
        assertEquals(1, markers!!.dropoffs.size)
        bitmap.recycle()
    }

    @Test fun overlayExcludeRectsRemoveSpuriousMarkersCompletely() {
        val bitmap = blank()
        disk(bitmap, 110, 150, 18, cyan)
        disk(bitmap, 210, 480, 36, blue)
        disk(bitmap, 420, 560, 36, green)
        disk(bitmap, 570, 400, 36, green)
        val markers = BoltScreenshotMarkerExtractor.extract(
            bitmap, excludeRects = listOf(Rect(520, 350, 620, 450))
        )
        assertNotNull(markers)
        assertEquals(1, markers!!.dropoffs.size)
        assertTrue(markers.dropoffs.single().screenCenter.x in 400.0..440.0)
        bitmap.recycle()
    }

    @Test fun cyanDotOverBluePickupStillFindsItsTip() {
        val bitmap = blank()
        disk(bitmap, 200, 410, 36, blue)
        disk(bitmap, 200, 410, 18, cyan)
        disk(bitmap, 440, 720, 36, green)
        val markers = BoltScreenshotMarkerExtractor.extract(bitmap)
        assertNotNull(markers)
        assertEquals(1, markers!!.pickups.size)
        assertTrue(markers.pickups.single().screenCenter.x in 185.0..215.0)
        assertNotNull(markers.currentLocation)
        bitmap.recycle()
    }

    @Test fun shopIconWithWhiteGlyphIsNeverTakenForTheCourierDot() {
        val bitmap = blank()
        disk(bitmap, 120, 700, 24, cyan) // Mapbox shop/station POI next to the customer
        disk(bitmap, 120, 700, 9, Color.WHITE)
        disk(bitmap, 560, 420, 14, cyan) // solid courier puck at the restaurant
        disk(bitmap, 560, 380, 36, blue)
        disk(bitmap, 160, 640, 36, green)
        val markers = BoltScreenshotMarkerExtractor.extract(bitmap)
        assertNotNull(markers)
        val dot = markers!!.currentLocation
        assertNotNull(dot)
        assertTrue(dot!!.screenCenter.x > 400.0)
        bitmap.recycle()
    }

    @Test fun onlyPoiIconsMeansNoCourierDotButPinsSurvive() {
        val bitmap = blank()
        disk(bitmap, 120, 700, 24, cyan)
        disk(bitmap, 120, 700, 9, Color.WHITE)
        disk(bitmap, 560, 380, 36, blue)
        disk(bitmap, 160, 640, 36, green)
        val markers = BoltScreenshotMarkerExtractor.extract(bitmap)
        assertNotNull(markers)
        assertNull(markers!!.currentLocation)
        assertEquals(1, markers.pickups.size)
        assertEquals(1, markers.dropoffs.size)
        bitmap.recycle()
    }

    private fun blank() = Bitmap.createBitmap(700, 1500, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.rgb(245, 245, 245))
    }
    private fun disk(bitmap: Bitmap, cx: Int, cy: Int, radius: Int, color: Int) {
        for (y in (cy - radius).coerceAtLeast(0)..(cy + radius).coerceAtMost(bitmap.height - 1)) {
            for (x in (cx - radius).coerceAtLeast(0)..(cx + radius).coerceAtMost(bitmap.width - 1)) {
                val dx = x - cx
                val dy = y - cy
                if (dx * dx + dy * dy <= radius * radius) bitmap.setPixel(x, y, color)
            }
        }
    }
}
