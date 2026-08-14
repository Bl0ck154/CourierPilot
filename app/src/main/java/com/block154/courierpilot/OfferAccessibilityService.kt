package com.block154.courierpilot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    private var unlockReceiverRegistered = false

    private val attemptRunnable = Runnable { attemptCapture() }

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    CaptureEventLog.append(
                        this@OfferAccessibilityService,
                        stage = "screen_on",
                        message = "Screen became interactive while capture service is running",
                        dedupeWindowMs = 5_000L,
                    )
                    scheduleAttempt(100L)
                }
                Intent.ACTION_USER_PRESENT -> {
                    val pending = OfferState.pending(this@OfferAccessibilityService)
                    val platform: String = pending?.let { OfferState.platformLabel(it.packageName) } ?: ""
                    CaptureEventLog.append(
                        this@OfferAccessibilityService,
                        stage = "unlocked",
                        platform = platform,
                        message = if (pending == null) "Device unlocked; no pending offer" else "Device unlocked; resuming pending capture",
                    )
                    if (pending != null && OfferState.autoOpen(this@OfferAccessibilityService)) {
                        reopenCourierAfterUnlock(pending)
                    }
                    scheduleAttempt(50L)
                }
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        registerUnlockReceiver()
        CaptureEventLog.append(this, "accessibility", "Accessibility capture service connected", dedupeWindowMs = 30_000L)
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

    override fun onInterrupt() {
        CaptureEventLog.append(this, "accessibility", "Accessibility service interrupted", dedupeWindowMs = 10_000L)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (unlockReceiverRegistered) {
            runCatching { unregisterReceiver(unlockReceiver) }
            unlockReceiverRegistered = false
        }
        recognizer.close()
        CaptureEventLog.append(this, "accessibility", "Accessibility capture service destroyed")
        super.onDestroy()
    }

    private fun registerUnlockReceiver() {
        if (unlockReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(unlockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(unlockReceiver, filter)
        }
        unlockReceiverRegistered = true
    }

    private fun reopenCourierAfterUnlock(pending: PendingOffer) {
        val platform = OfferState.platformLabel(pending.packageName)
        runCatching {
            val launch = packageManager.getLaunchIntentForPackage(pending.packageName)
                ?: error("No launcher activity")
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(launch)
        }.onSuccess {
            CaptureEventLog.append(this, "unlock_open", "Requested courier app after unlock", platform)
        }.onFailure {
            CaptureEventLog.append(this, "unlock_open_failed", it.javaClass.simpleName, platform)
        }
    }

    private fun attemptCapture() {
        if (captureInFlight) return
        val pending = OfferState.pending(this)
        if (pending == null) {
            // Accessibility/window events wake us immediately when UI changes. This slow watchdog is
            // only a fallback, so there is no reason to poll every two seconds all day.
            scheduleAttempt(IDLE_WATCHDOG_MS)
            return
        }
        val platform = OfferState.platformLabel(pending.packageName)

        if (pending.armedAt != lastHandledArmedAt) {
            lastHandledArmedAt = pending.armedAt
            OfferState.markError(this, "")
            CaptureEventLog.append(this, "watching", "Started watching for courier offer window", platform)
        }

        val target = findCourierWindow(pending)
        if (target == null) {
            CaptureEventLog.append(
                this,
                stage = "window_missing",
                platform = platform,
                message = "Courier offer window is not currently available",
                dedupeWindowMs = 5_000L,
            )
            scheduleAttempt(adaptiveWindowDelay(pending))
            return
        }

        val uiText = collectVisibleText(target.root)
        if (uiText.isNotBlank()) OfferState.saveUiText(this, uiText)
        val parsed = OfferParser.parse(uiText)

        if (parsed.priceCents != null) {
            CaptureEventLog.append(
                this,
                stage = "price_accessibility",
                platform = platform,
                message = "Price detected in Accessibility tree",
                dedupeWindowMs = 3_000L,
            )
            captureCurrentFrameAndPersist(pending, target.windowId, uiText, parsed)
        } else {
            CaptureEventLog.append(
                this,
                stage = "price_wait",
                platform = platform,
                message = "Price not exposed yet; checking current frame with OCR",
                dedupeWindowMs = 5_000L,
            )
            captureCurrentFrameForOcr(pending, target.windowId, uiText)
        }
    }

    private data class CourierWindow(val root: AccessibilityNodeInfo, val windowId: Int)

    private fun findCourierWindow(pending: PendingOffer): CourierWindow? {
        val active = rootInActiveWindow
        if (active?.packageName?.toString() == pending.packageName) {
            return CourierWindow(active, active.windowId)
        }

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
                        CaptureEventLog.append(
                            this@OfferAccessibilityService,
                            "bitmap_failed",
                            "Android screenshot buffer could not be converted",
                            OfferState.platformLabel(pending.packageName),
                            dedupeWindowMs = 5_000L,
                        )
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
                                CaptureEventLog.append(
                                    this@OfferAccessibilityService,
                                    "price_ocr",
                                    "Price detected by OCR fallback",
                                    OfferState.platformLabel(pending.packageName),
                                )
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
                            CaptureEventLog.append(
                                this@OfferAccessibilityService,
                                "ocr_failed",
                                error.javaClass.simpleName,
                                OfferState.platformLabel(pending.packageName),
                                dedupeWindowMs = 5_000L,
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
                        CaptureEventLog.append(
                            this@OfferAccessibilityService,
                            "bitmap_failed",
                            "Final screenshot buffer could not be converted",
                            OfferState.platformLabel(pending.packageName),
                            dedupeWindowMs = 5_000L,
                        )
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
        val platform = OfferState.platformLabel(pending.packageName)
        val current = OfferState.pending(this)
        val stillCurrent = current != null &&
            current.packageName == pending.packageName &&
            current.armedAt == pending.armedAt &&
            (pending.notificationKey.isBlank() || current.notificationKey == pending.notificationKey)
        if (!stillCurrent) {
            bitmap.recycle()
            captureInFlight = false
            CaptureEventLog.append(this, "stale_callback", "Discarded screenshot from superseded offer", platform)
            scheduleAttempt(100L)
            return
        }

        val priceCents = parsed.priceCents
        if (priceCents == null) {
            bitmap.recycle()
            captureInFlight = false
            scheduleAttempt(adaptiveOcrDelay(pending))
            return
        }

        var saved: SavedScreenshot? = null
        try {
            saved = ScreenshotStore.save(this, bitmap, pending.sourceName)
            val rowId = OfferDatabase.get(this).insert(
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
            CaptureEventLog.append(
                this,
                stage = "saved",
                platform = platform,
                message = "Offer saved successfully as record #$rowId (${parsed.deliveryCount ?: 1} delivery${if ((parsed.deliveryCount ?: 1) == 1) "" else "ies"})",
            )
            OfferState.clear(this)
            lastHandledArmedAt = 0L
        } catch (t: Throwable) {
            // If MediaStore succeeds but SQLite fails, roll back the published PNG too. Otherwise a
            // failed save leaves an orphan image in Pictures/CourierOffers with no history record.
            saved?.let { ScreenshotStore.delete(this, it) }
            OfferState.markError(this, "Offer save failed: ${t.message ?: t.javaClass.simpleName}")
            CaptureEventLog.append(this, "save_failed", t.javaClass.simpleName, platform)
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
        CaptureEventLog.append(
            this,
            "screenshot_failed",
            reason,
            OfferState.platformLabel(pending.packageName),
            dedupeWindowMs = 5_000L,
        )
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

    private fun adaptiveWindowDelay(pending: PendingOffer): Long {
        val age = System.currentTimeMillis() - pending.armedAt
        return when {
            age < 5_000L -> 350L
            age < 30_000L -> 900L
            age < 60_000L -> 1_500L
            else -> 2_500L
        }
    }

    private fun scheduleAttempt(delayMs: Long) {
        handler.removeCallbacks(attemptRunnable)
        handler.postDelayed(attemptRunnable, delayMs)
    }

    private fun collectVisibleText(root: AccessibilityNodeInfo): String {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val pieces = mutableListOf<String>()
        queue.add(root)
        var visited = 0

        fun addPiece(value: CharSequence?) {
            val cleaned = value?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return
            if (pieces.lastOrNull() != cleaned) pieces += cleaned
        }

        while (queue.isNotEmpty() && visited < 700) {
            val node = queue.removeFirst()
            visited++
            addPiece(node.text)
            addPiece(node.contentDescription)
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
        private const val IDLE_WATCHDOG_MS = 8_000L
    }
}
