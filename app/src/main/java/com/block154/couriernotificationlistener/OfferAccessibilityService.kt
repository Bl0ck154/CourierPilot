package com.block154.couriernotificationlistener

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.ArrayDeque

class OfferAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var captureInFlight = false
    private var lastHandledArmedAt = 0L

    private val attemptRunnable = Runnable { attemptCapture() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Lightweight watchdog: SharedPreferences can be armed by NotificationListener even
        // when no useful Accessibility event follows (screen locked, shade interaction, OEM quirks).
        handler.removeCallbacks(attemptRunnable)
        handler.post(attemptRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pending = OfferState.pending(this) ?: return
        val eventPackage = event?.packageName?.toString().orEmpty()
        if (eventPackage == pending.packageName || eventPackage == "com.android.systemui") {
            scheduleAttempt(100L)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recognizer.close()
        super.onDestroy()
    }

    private fun attemptCapture() {
        if (captureInFlight) return
        val pending = OfferState.pending(this)
        if (pending == null) {
            scheduleAttempt(IDLE_WATCHDOG_MS)
            return
        }

        if (pending.armedAt != lastHandledArmedAt) {
            lastHandledArmedAt = pending.armedAt
            OfferState.markError(this, "")
        }

        val target = findCourierWindow(pending)
        if (target == null) {
            scheduleAttempt(WINDOW_RETRY_MS)
            return
        }

        val uiText = collectVisibleText(target.root)
        if (uiText.isNotBlank()) OfferState.saveUiText(this, uiText)
        val parsed = OfferParser.parse(uiText)

        if (parsed.priceCents != null) {
            captureCurrentFrameAndPersist(pending, target.windowId, uiText, parsed)
        } else {
            captureCurrentFrameForOcr(pending, target.windowId, uiText)
        }
    }

    private data class CourierWindow(val root: AccessibilityNodeInfo, val windowId: Int)

    private fun findCourierWindow(pending: PendingOffer): CourierWindow? {
        val active = rootInActiveWindow
        if (active?.packageName?.toString() == pending.packageName) {
            return CourierWindow(active, active.windowId)
        }

        // If the notification shade / another system surface is on top, the courier window can
        // still remain in the interactive-windows list. This is especially useful on Android 14+.
        windows.forEach { window ->
            val root = runCatching { window.root }.getOrNull() ?: return@forEach
            if (root.packageName?.toString() == pending.packageName) {
                return CourierWindow(root, window.id)
            }
        }
        return null
    }

    private fun captureCurrentFrameForOcr(
        pending: PendingOffer,
        windowId: Int,
        accessibilityText: String,
    ) {
        captureInFlight = true
        takeTargetScreenshot(
            windowId,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = screenshotToBitmap(screenshot)
                    if (bitmap == null) {
                        captureInFlight = false
                        scheduleAttempt(adaptiveOcrDelay(pending))
                        return
                    }

                    val image = InputImage.fromBitmap(bitmap, 0)
                    recognizer.process(image)
                        .addOnSuccessListener { result ->
                            val combined = mergeText(accessibilityText, result.text)
                            if (combined.isNotBlank()) OfferState.saveUiText(this@OfferAccessibilityService, combined)
                            val parsed = OfferParser.parse(combined)
                            if (parsed.priceCents != null) {
                                persistOffer(bitmap, pending, combined, parsed)
                            } else {
                                bitmap.recycle()
                                captureInFlight = false
                                scheduleAttempt(adaptiveOcrDelay(pending))
                            }
                        }
                        .addOnFailureListener { error ->
                            bitmap.recycle()
                            captureInFlight = false
                            OfferState.markError(
                                this@OfferAccessibilityService,
                                "Waiting for price; OCR not ready: ${error.message ?: error.javaClass.simpleName}",
                            )
                            scheduleAttempt(adaptiveOcrDelay(pending).coerceAtLeast(1_500L))
                        }
                }

                override fun onFailure(errorCode: Int) {
                    captureInFlight = false
                    handleScreenshotFailure(errorCode, retry = true, pending = pending)
                }
            },
        )
    }

    private fun captureCurrentFrameAndPersist(
        pending: PendingOffer,
        windowId: Int,
        text: String,
        parsed: ParsedOffer,
    ) {
        captureInFlight = true
        takeTargetScreenshot(
            windowId,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = screenshotToBitmap(screenshot)
                    if (bitmap == null) {
                        captureInFlight = false
                        scheduleAttempt(500L)
                        return
                    }
                    persistOffer(bitmap, pending, text, parsed)
                }

                override fun onFailure(errorCode: Int) {
                    captureInFlight = false
                    handleScreenshotFailure(errorCode, retry = true, pending = pending)
                }
            },
        )
    }

    private fun takeTargetScreenshot(windowId: Int, callback: TakeScreenshotCallback) {
        if (Build.VERSION.SDK_INT >= 34) {
            takeScreenshotOfWindow(windowId, mainExecutor, callback)
        } else {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, callback)
        }
    }

    private fun persistOffer(
        bitmap: Bitmap,
        pending: PendingOffer,
        rawText: String,
        parsed: ParsedOffer,
    ) {
        val priceCents = parsed.priceCents
        if (priceCents == null) {
            bitmap.recycle()
            captureInFlight = false
            scheduleAttempt(adaptiveOcrDelay(pending))
            return
        }

        try {
            val saved = ScreenshotStore.save(this, bitmap, pending.sourceName)
            OfferDatabase.get(this).insert(
                OfferRecord(
                    capturedAt = pending.armedAt,
                    platform = OfferParser.platformName(pending.packageName, pending.sourceName),
                    packageName = pending.packageName,
                    priceCents = priceCents,
                    distanceMeters = parsed.distanceMeters,
                    restaurant = parsed.restaurant,
                    screenshotUri = saved.uri.toString(),
                    screenshotFilename = saved.filename,
                    rawText = rawText,
                    merchantNames = parsed.merchantNames,
                    pickupAddresses = parsed.pickupAddresses,
                    customerNames = parsed.customerNames,
                    dropoffAddresses = parsed.dropoffAddresses,
                    deliveryCount = parsed.deliveryCount,
                    estimatedMinutesMin = parsed.estimatedMinutesMin,
                    estimatedMinutesMax = parsed.estimatedMinutesMax,
                )
            )
            OfferState.markCapture(this, saved.filename)
            OfferState.clear(this)
            lastHandledArmedAt = 0L
        } catch (t: Throwable) {
            OfferState.markError(this, "Offer save failed: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            bitmap.recycle()
            captureInFlight = false
            scheduleAttempt(IDLE_WATCHDOG_MS)
        }
    }

    private fun screenshotToBitmap(screenshot: ScreenshotResult): Bitmap? {
        val buffer = screenshot.hardwareBuffer
        return try {
            val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace) ?: return null
            try {
                hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
            } finally {
                hardwareBitmap.recycle()
            }
        } catch (_: Throwable) {
            null
        } finally {
            buffer.close()
        }
    }

    private fun handleScreenshotFailure(errorCode: Int, retry: Boolean, pending: PendingOffer) {
        if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
            if (retry) scheduleAttempt(700L)
            return
        }
        val reason = when (errorCode) {
            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "no accessibility access"
            ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "invalid display"
            ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "internal Android error"
            else -> if (Build.VERSION.SDK_INT >= 34 && errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
                "secure courier window"
            } else {
                "Android error $errorCode"
            }
        }
        OfferState.markError(this, "Screenshot failed: $reason")
        if (retry) scheduleAttempt(adaptiveOcrDelay(pending).coerceAtLeast(1_200L))
    }

    private fun adaptiveOcrDelay(pending: PendingOffer): Long {
        val age = System.currentTimeMillis() - pending.armedAt
        return when {
            age < 15_000L -> 1_200L
            age < 60_000L -> 2_500L
            else -> 5_000L
        }
    }

    private fun scheduleAttempt(delayMs: Long) {
        handler.removeCallbacks(attemptRunnable)
        handler.postDelayed(attemptRunnable, delayMs)
    }

    private fun collectVisibleText(root: AccessibilityNodeInfo): String {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val pieces = LinkedHashSet<String>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < 700) {
            val node = queue.removeFirst()
            visited++
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(pieces::add)
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(pieces::add)
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return pieces.joinToString("\n")
    }

    private fun mergeText(accessibilityText: String, ocrText: String): String {
        return listOf(accessibilityText.trim(), ocrText.trim())
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
    }

    companion object {
        private const val IDLE_WATCHDOG_MS = 2_000L
        private const val WINDOW_RETRY_MS = 350L
    }
}
