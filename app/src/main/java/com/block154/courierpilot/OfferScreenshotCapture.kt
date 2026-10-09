package com.block154.courierpilot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.view.Display
import android.view.WindowManager
import java.util.IdentityHashMap
import java.util.concurrent.Executors

/** Ordinary screenshots must not change alpha, including during active gestures. */
internal object OfferOverlayCapturePolicy {
    fun shouldChangeAlpha(cleanFrame: Boolean, dragging: Boolean, swipeExiting: Boolean): Boolean =
        cleanFrame && !dragging && !swipeExiting
}

/** An incomplete Bolt map can request only one clean capture in the same offer session. */
internal class OfferCleanCaptureBudget {
    private var session: String? = null
    private var consumed = false

    fun claim(key: String, missingPins: Boolean, intersectsMap: Boolean): Boolean {
        if (session != key) {
            session = key
            consumed = false
        }
        if (!missingPins || !intersectsMap || consumed) return false
        consumed = true
        return true
    }
}

/** Neutralize CourierPilot's own card before price OCR, map extraction and proof persistence. */
internal object OfferOverlayBitmapMask {
    fun mapRect(rect: Rect?, screenWidth: Int, screenHeight: Int, bitmapWidth: Int, bitmapHeight: Int,
                padding: Int = 0): Rect? {
        if (rect == null || screenWidth <= 0 || screenHeight <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) return null
        val expanded = Rect(rect).apply { inset(-padding, -padding) }
        val result = Rect(
            (expanded.left.toLong() * bitmapWidth / screenWidth).toInt().coerceIn(0, bitmapWidth),
            (expanded.top.toLong() * bitmapHeight / screenHeight).toInt().coerceIn(0, bitmapHeight),
            ((expanded.right.toLong() * bitmapWidth + screenWidth - 1) / screenWidth).toInt().coerceIn(0, bitmapWidth),
            ((expanded.bottom.toLong() * bitmapHeight + screenHeight - 1) / screenHeight).toInt().coerceIn(0, bitmapHeight),
        )
        return result.takeUnless { it.isEmpty }
    }

    fun intersectsMap(rect: Rect?, bitmapHeight: Int): Boolean =
        rect != null && rect.top < (bitmapHeight * 0.72f).toInt() && rect.bottom > 0

    fun paint(bitmap: Bitmap, rect: Rect?): Boolean {
        if (rect == null || rect.isEmpty || !bitmap.isMutable) return false
        val red = ArrayList<Int>()
        val green = ArrayList<Int>()
        val blue = ArrayList<Int>()
        fun sample(x: Int, y: Int) {
            if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) return
            val pixel = bitmap.getPixel(x, y)
            red.add(Color.red(pixel))
            green.add(Color.green(pixel))
            blue.add(Color.blue(pixel))
        }
        // Four-pixel-wide border OUTSIDE the mask, never the €/km text inside it.
        for (offset in 1..4) {
            for (x in rect.left until rect.right step 4) {
                sample(x, rect.top - offset)
                sample(x, rect.bottom - 1 + offset)
            }
            for (y in rect.top until rect.bottom step 4) {
                sample(rect.left - offset, y)
                sample(rect.right - 1 + offset, y)
            }
        }
        fun median(values: ArrayList<Int>): Int {
            if (values.isEmpty()) return 240
            values.sort()
            return values[values.size / 2]
        }
        Canvas(bitmap).drawRect(rect, Paint().apply {
            color = Color.rgb(median(red), median(green), median(blue))
            style = Paint.Style.FILL
        })
        return true
    }
}

internal data class OfferPreparedScreenshot(val bitmap: Bitmap, val needsCleanBoltCapture: Boolean)

/**
 * HardwareBuffer conversion and WS-C pixel marker inspection run on a dedicated serial worker.
 * Only screenshot requests, window rect snapshots and capture-token callbacks touch main.
 */
internal class OfferScreenshotCapture(
    private val service: AccessibilityService,
    private val handler: Handler,
) {
    private enum class Source { DISPLAY, WINDOW }
    private val sourceByResult = IdentityHashMap<ScreenshotResult, Source>()

    fun takeTargetScreenshot(windowId: Int, callback: TakeScreenshotCallback, preferDisplay: Boolean = false) {
        if (Build.VERSION.SDK_INT < 34 || preferDisplay) {
            requestDisplayScreenshot(callback, fallback = false)
            return
        }
        val scoped = object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                sourceByResult[screenshot] = Source.WINDOW
                callback.onSuccess(screenshot)
            }
            override fun onFailure(errorCode: Int) {
                if (!shouldFallbackToDisplayScreenshot(errorCode)) {
                    callback.onFailure(errorCode)
                    return
                }
                val delay = if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT)
                    RATE_LIMIT_RETRY_MS else DISPLAY_FALLBACK_DELAY_MS
                CaptureEventLog.append(service, "screenshot_window_fallback",
                    "Window screenshot failed with Android error $errorCode; retrying as display capture",
                    dedupeWindowMs = 2_000L)
                handler.postDelayed({ requestDisplayScreenshot(callback, fallback = true) }, delay)
            }
        }
        runCatching { service.takeScreenshotOfWindow(windowId, service.mainExecutor, scoped) }
            .onFailure {
                CaptureEventLog.append(service, "screenshot_window_exception",
                    "Window capture threw; trying display capture", dedupeWindowMs = 3_000L)
                handler.postDelayed({ requestDisplayScreenshot(callback, fallback = true) }, DISPLAY_FALLBACK_DELAY_MS)
            }
    }

    /** Recovery-only: hide for one frame, request a display screenshot, then reveal unconditionally. */
    fun takeCleanDisplayScreenshot(callback: TakeScreenshotCallback) {
        LiveAdvisorHub.setCaptureSuppressed(service, true, cleanFrame = true)
        handler.postDelayed({ requestDisplayScreenshot(callback, fallback = false) }, ONE_FRAME_MS)
        handler.postDelayed({
            LiveAdvisorHub.setCaptureSuppressed(service, false, cleanFrame = true)
        }, ONE_FRAME_MS * 2)
    }

    fun discard(screenshot: ScreenshotResult) {
        sourceByResult.remove(screenshot)
        runCatching { screenshot.hardwareBuffer.close() }
    }

    fun convertOffMain(
        screenshot: ScreenshotResult,
        isCurrent: () -> Boolean,
        expectedBoltCustomers: Int? = null,
        onReady: (OfferPreparedScreenshot?) -> Unit,
    ) {
        val source = sourceByResult.remove(screenshot) ?: Source.DISPLAY
        val dimensions = screenDimensions()
        val screenRect = if (source == Source.DISPLAY) LiveAdvisorHub.overlayScreenRect() else null
        val padding = (12 * service.resources.displayMetrics.density + 0.5f).toInt()
        val job = Runnable {
            var bitmap: Bitmap? = null
            var result: OfferPreparedScreenshot? = null
            try {
                bitmap = toBitmap(screenshot)
                if (bitmap != null) {
                    val rect = if (source == Source.DISPLAY) OfferOverlayBitmapMask.mapRect(
                        screenRect, dimensions.x, dimensions.y, bitmap.width, bitmap.height, padding
                    ) else null
                    val intersects = OfferOverlayBitmapMask.intersectsMap(rect, bitmap.height)
                    if (rect != null) OfferOverlayBitmapMask.paint(bitmap, rect)
                    val missing = expectedBoltCustomers != null && expectedBoltCustomers > 0 && intersects &&
                        (BoltScreenshotMarkerExtractor.extract(bitmap, excludeRects = listOfNotNull(rect))
                            ?.dropoffs?.size ?: 0) < expectedBoltCustomers
                    result = OfferPreparedScreenshot(bitmap, missing)
                }
            } catch (_: Throwable) {
                if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
            }
            val finished = result
            handler.post {
                if (!isCurrent()) finished?.bitmap?.recycle() else onReady(finished)
            }
        }
        runCatching { WORKER.execute(job) }.onFailure {
            discard(screenshot)
            handler.post { if (isCurrent()) onReady(null) }
        }
    }

    private fun toBitmap(screenshot: ScreenshotResult): Bitmap? {
        val buffer = screenshot.hardwareBuffer
        return try {
            val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace) ?: return null
            try { hardware.copy(Bitmap.Config.ARGB_8888, true) }
            finally { hardware.recycle() }
        } catch (_: Throwable) {
            null
        } finally {
            buffer.close()
        }
    }

    private fun screenDimensions(): Point {
        val size = Point()
        @Suppress("DEPRECATION")
        val display = (service.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        @Suppress("DEPRECATION")
        runCatching { display.getRealSize(size) }
        if (size.x <= 0 || size.y <= 0)
            size.set(service.resources.displayMetrics.widthPixels, service.resources.displayMetrics.heightPixels)
        return size
    }

    private fun requestDisplayScreenshot(callback: TakeScreenshotCallback, fallback: Boolean) {
        val displayCallback = object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                sourceByResult[screenshot] = Source.DISPLAY
                CaptureEventLog.append(service,
                    if (fallback) "screenshot_display_fallback_ok" else "screenshot_display_direct_ok",
                    if (fallback) "Display screenshot fallback succeeded" else "Direct display screenshot succeeded",
                    dedupeWindowMs = 3_000L)
                callback.onSuccess(screenshot)
            }
            override fun onFailure(errorCode: Int) {
                CaptureEventLog.append(service,
                    if (fallback) "screenshot_display_fallback_failed" else "screenshot_display_direct_failed",
                    "Display screenshot failed with Android error $errorCode", dedupeWindowMs = 3_000L)
                callback.onFailure(errorCode)
            }
        }
        runCatching { service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, displayCallback) }
            .onFailure {
                CaptureEventLog.append(service, "screenshot_display_exception",
                    "Display capture threw", dedupeWindowMs = 3_000L)
                callback.onFailure(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)
            }
    }

    private fun shouldFallbackToDisplayScreenshot(code: Int): Boolean {
        if (code == AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS) return false
        if (Build.VERSION.SDK_INT >= 34 && code == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) return false
        return true
    }

    companion object {
        const val RATE_LIMIT_RETRY_MS = 750L
        private const val DISPLAY_FALLBACK_DELAY_MS = 120L
        private const val ONE_FRAME_MS = 16L
        private val WORKER = Executors.newSingleThreadExecutor { task ->
            Thread(task, "CourierPilot-CaptureBitmap").apply { isDaemon = true }
        }
    }
}
