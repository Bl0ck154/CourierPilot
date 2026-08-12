package com.block154.couriernotificationlistener

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.Locale

class OfferAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var armedAtBeingHandled = 0L
    private var captureInFlight = false

    private val attemptRunnable = Runnable { attemptCapture() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventPackage = event?.packageName?.toString() ?: return
        val pending = OfferState.pending(this) ?: return
        if (eventPackage != pending.packageName) return

        if (armedAtBeingHandled != pending.armedAt) {
            armedAtBeingHandled = pending.armedAt
            captureInFlight = false
            handler.removeCallbacks(attemptRunnable)
            // Let the offer screen render instead of taking a screenshot of the transition.
            handler.postDelayed(attemptRunnable, 450L)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun attemptCapture() {
        if (captureInFlight) return
        val pending = OfferState.pending(this) ?: return
        if (pending.armedAt != armedAtBeingHandled) return

        val root = rootInActiveWindow
        val activePackage = root?.packageName?.toString()
        if (activePackage != pending.packageName) {
            retrySoon(pending)
            return
        }

        val uiText = collectVisibleText(root)
        if (uiText.isNotBlank()) OfferState.saveUiText(this, uiText)

        val age = System.currentTimeMillis() - pending.armedAt
        // Prefer waiting until price/distance appears. If the app does not expose its
        // text to Accessibility (common with some custom/Compose views), fall back to
        // a timed capture so we still get the visual offer.
        if (!looksLikeRenderedOffer(uiText) && age < 2_800L) {
            retrySoon(pending)
            return
        }

        captureInFlight = true
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val buffer = screenshot.hardwareBuffer
                        val hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            ?: error("Could not wrap screenshot HardwareBuffer")
                        val bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
                            ?: error("Could not copy screenshot bitmap")
                        try {
                            val filename = ScreenshotStore.save(
                                this@OfferAccessibilityService,
                                bitmap,
                                pending.sourceName,
                            )
                            OfferState.markCapture(this@OfferAccessibilityService, filename)
                            OfferState.clear(this@OfferAccessibilityService)
                            armedAtBeingHandled = 0L
                        } finally {
                            bitmap.recycle()
                        }
                    } catch (t: Throwable) {
                        OfferState.markError(
                            this@OfferAccessibilityService,
                            "Screenshot save failed: ${t.message ?: t.javaClass.simpleName}",
                        )
                        OfferState.clear(this@OfferAccessibilityService)
                        armedAtBeingHandled = 0L
                    } finally {
                        screenshot.hardwareBuffer.close()
                        captureInFlight = false
                    }
                }

                override fun onFailure(errorCode: Int) {
                    captureInFlight = false
                    if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                        handler.postDelayed(attemptRunnable, 450L)
                        return
                    }
                    val reason = when (errorCode) {
                        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "no accessibility access"
                        ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "invalid display"
                        ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "internal Android error"
                        else -> "Android error $errorCode (secure window is possible)"
                    }
                    OfferState.markError(
                        this@OfferAccessibilityService,
                        "Screenshot failed: $reason",
                    )
                    OfferState.clear(this@OfferAccessibilityService)
                    armedAtBeingHandled = 0L
                }
            },
        )
    }

    private fun retrySoon(pending: PendingOffer) {
        if (System.currentTimeMillis() - pending.armedAt < 19_000L) {
            handler.removeCallbacks(attemptRunnable)
            handler.postDelayed(attemptRunnable, 300L)
        }
    }

    private fun collectVisibleText(root: AccessibilityNodeInfo): String {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val pieces = LinkedHashSet<String>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < 300) {
            val node = queue.removeFirst()
            visited++

            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(pieces::add)
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(pieces::add)

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
        }
        return pieces.joinToString("\n")
    }

    private fun looksLikeRenderedOffer(text: String): Boolean {
        val normalized = text.lowercase(Locale.ROOT)
        val money = Regex("(?:€|eur)\\s*\\d|\\d[\\d.,]*\\s*(?:€|eur)")
        val distance = Regex("\\b\\d+(?:[.,]\\d+)?\\s*(?:km|m)\\b")
        return normalized.length >= 20 && (money.containsMatchIn(normalized) || distance.containsMatchIn(normalized))
    }
}
