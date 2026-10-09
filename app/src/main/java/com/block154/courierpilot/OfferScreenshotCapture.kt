package com.block154.courierpilot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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

    /**
     * Fills the card area by blending the pixels just outside its four edges (a Coons-style
     * bilinear patch). A flat median fill left a visible pale block on saved proof screenshots
     * over a map; the blend continues the surrounding map colours instead. Only pixels outside
     * the rect are read, so the card's own €/km text can never leak into OCR.
     */
    fun paint(bitmap: Bitmap, rect: Rect?): Boolean {
        if (rect == null || rect.isEmpty || !bitmap.isMutable) return false
        val left = rect.left.coerceIn(0, bitmap.width)
        val top = rect.top.coerceIn(0, bitmap.height)
        val right = rect.right.coerceIn(0, bitmap.width)
        val bottom = rect.bottom.coerceIn(0, bitmap.height)
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return false

        val fallback = medianBorderColor(bitmap, Rect(left, top, right, bottom))
        // Edges are heavily smoothed: a road, pin or notification banner touching one edge pixel
        // must not be dragged across the whole patch as a streak (seen on the 0.17.2 device trace).
        val topEdge = smooth(IntArray(width) { i -> edgeAverage(bitmap, left + i, top - 1, dx = 0, dy = -1, fallback) })
        val bottomEdge = smooth(IntArray(width) { i -> edgeAverage(bitmap, left + i, bottom, dx = 0, dy = 1, fallback) })
        val leftEdge = smooth(IntArray(height) { j -> edgeAverage(bitmap, left - 1, top + j, dx = -1, dy = 0, fallback) })
        val rightEdge = smooth(IntArray(height) { j -> edgeAverage(bitmap, right, top + j, dx = 1, dy = 0, fallback) })

        val row = IntArray(width)
        for (j in 0 until height) {
            val v = (j + 0.5f) / height
            for (i in 0 until width) {
                val u = (i + 0.5f) / width
                row[i] = blend(leftEdge[j], rightEdge[j], u, topEdge[i], bottomEdge[i], v)
            }
            bitmap.setPixels(row, 0, width, left, top + j, width, 1)
        }
        return true
    }

    /** Two passes of a wide box blur (radius ≈ 1/4 of the edge) over packed RGB colours. */
    private fun smooth(colors: IntArray): IntArray {
        if (colors.size < 3) return colors
        val radius = (colors.size / 4).coerceAtLeast(2)
        var current = colors
        repeat(2) {
            val red = LongArray(current.size + 1)
            val green = LongArray(current.size + 1)
            val blue = LongArray(current.size + 1)
            for (i in current.indices) {
                red[i + 1] = red[i] + Color.red(current[i])
                green[i + 1] = green[i] + Color.green(current[i])
                blue[i + 1] = blue[i] + Color.blue(current[i])
            }
            current = IntArray(current.size) { i ->
                val from = (i - radius).coerceAtLeast(0)
                val to = (i + radius + 1).coerceAtMost(current.size)
                val count = to - from
                Color.rgb(
                    ((red[to] - red[from]) / count).toInt(),
                    ((green[to] - green[from]) / count).toInt(),
                    ((blue[to] - blue[from]) / count).toInt(),
                )
            }
        }
        return current
    }

    private fun blend(l: Int, r: Int, u: Float, t: Int, b: Int, v: Float): Int {
        fun channel(shift: Int): Int {
            val horizontal = ((l shr shift) and 0xFF) * (1 - u) + ((r shr shift) and 0xFF) * u
            val vertical = ((t shr shift) and 0xFF) * (1 - v) + ((b shr shift) and 0xFF) * v
            return ((horizontal + vertical) / 2f + 0.5f).toInt().coerceIn(0, 255)
        }
        return Color.rgb(channel(16), channel(8), channel(0))
    }

    /** Mean of up to eight pixels stepping away from the card; off-bitmap edges use [fallback]. */
    private fun edgeAverage(bitmap: Bitmap, x: Int, y: Int, dx: Int, dy: Int, fallback: Int): Int {
        var red = 0
        var green = 0
        var blue = 0
        var count = 0
        for (step in 0 until 8) {
            val px = x + dx * step
            val py = y + dy * step
            if (px !in 0 until bitmap.width || py !in 0 until bitmap.height) break
            val pixel = bitmap.getPixel(px, py)
            red += Color.red(pixel)
            green += Color.green(pixel)
            blue += Color.blue(pixel)
            count++
        }
        if (count == 0) return fallback
        return Color.rgb(red / count, green / count, blue / count)
    }

    private fun medianBorderColor(bitmap: Bitmap, rect: Rect): Int {
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
        return Color.rgb(median(red), median(green), median(blue))
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
