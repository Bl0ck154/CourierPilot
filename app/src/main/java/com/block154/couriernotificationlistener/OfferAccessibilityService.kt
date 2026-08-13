package com.block154.couriernotificationlistener

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
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
            handler.postDelayed(attemptRunnable, 350L)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::recognizer.isInitializedCompat()) recognizer.close()
        super.onDestroy()
    }

    private fun attemptCapture() {
        if (captureInFlight) return
        val pending = OfferState.pending(this) ?: return
        if (pending.armedAt != armedAtBeingHandled) return

        val root = rootInActiveWindow
        if (root?.packageName?.toString() != pending.packageName) {
            retrySoon()
            return
        }

        val uiText = collectVisibleText(root)
        if (uiText.isNotBlank()) OfferState.saveUiText(this, uiText)
        val parsed = OfferParser.parse(uiText)

        if (parsed.priceCents != null) {
            captureCurrentFrameAndPersist(pending, uiText, parsed)
        } else {
            captureCurrentFrameForOcr(pending, uiText)
        }
    }

    private fun captureCurrentFrameForOcr(pending: PendingOffer, accessibilityText: String) {
        captureInFlight = true
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = screenshotToBitmap(screenshot)
                    if (bitmap == null) {
                        captureInFlight = false
                        retrySoon(OCR_RETRY_MS)
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
                                retrySoon(OCR_RETRY_MS)
                            }
                        }
                        .addOnFailureListener { error ->
                            bitmap.recycle()
                            captureInFlight = false
                            OfferState.markError(
                                this@OfferAccessibilityService,
                                "Waiting for price; OCR not ready: ${error.message ?: error.javaClass.simpleName}",
                            )
                            retrySoon(1_500L)
                        }
                }

                override fun onFailure(errorCode: Int) {
                    captureInFlight = false
                    handleScreenshotFailure(errorCode, retry = true)
                }
            },
        )
    }

    private fun captureCurrentFrameAndPersist(
        pending: PendingOffer,
        text: String,
        parsed: ParsedOffer,
    ) {
        captureInFlight = true
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = screenshotToBitmap(screenshot)
                    if (bitmap == null) {
                        captureInFlight = false
                        retrySoon()
                        return
                    }
                    persistOffer(bitmap, pending, text, parsed)
                }

                override fun onFailure(errorCode: Int) {
                    captureInFlight = false
                    handleScreenshotFailure(errorCode, retry = true)
                }
            },
        )
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
            retrySoon()
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
                )
            )
            OfferState.markCapture(this, saved.filename)
            OfferState.clear(this)
            armedAtBeingHandled = 0L
        } catch (t: Throwable) {
            OfferState.markError(this, "Offer save failed: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            bitmap.recycle()
            captureInFlight = false
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

    private fun handleScreenshotFailure(errorCode: Int, retry: Boolean) {
        if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
            if (retry) retrySoon(600L)
            return
        }
        val reason = when (errorCode) {
            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "no accessibility access"
            ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "invalid display"
            ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "internal Android error"
            else -> "Android error $errorCode (secure window is possible)"
        }
        OfferState.markError(this, "Screenshot failed: $reason")
        if (retry) retrySoon(1_200L)
    }

    private fun retrySoon(delayMs: Long = 400L) {
        if (OfferState.pending(this) != null) {
            handler.removeCallbacks(attemptRunnable)
            handler.postDelayed(attemptRunnable, delayMs)
        }
    }

    private fun collectVisibleText(root: AccessibilityNodeInfo): String {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val pieces = LinkedHashSet<String>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < 400) {
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

    private fun <T> Lazy<T>.isInitializedCompat(): Boolean = isInitialized()

    companion object {
        private const val OCR_RETRY_MS = 1_100L
    }
}
