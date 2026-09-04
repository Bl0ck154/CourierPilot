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
import android.os.SystemClock
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
    private val captureGuard = CaptureFlightGuard(CAPTURE_OPERATION_TIMEOUT_MS)
    private var lastHandledArmedAt = 0L
    private var unlockReceiverRegistered = false
    private var lastCourierEventAtElapsed = 0L
    private var lastCourierEventPackage = ""
    private var lastDiscoveryOcrAtElapsed = 0L
    private var screenshotFailureKey = ""
    private var screenshotFailureCount = 0
    private var lastFastAccessibilityPriceKey = ""
    private var woltPricePollKey = ""
    private var lastWoltPriceProbeAtElapsed = 0L
    private var woltFrameKey = ""
    private var woltCardFrameText = ""
    private var woltDropoffFrameText = ""
    private var woltDropoffProbeKey = ""
    private var woltDropoffProbeAttempts = 0
    private var woltDropoffSheetSettleAttempts = 0

    private val attemptRunnable = Runnable { attemptCapture() }
    private val woltPricePollRunnable = Runnable { pollPendingWoltAccessibilityPrice() }
    private val captureWatchdogRunnable = Runnable {
        if (recoverTimedOutCaptureIfNeeded()) scheduleAttempt(100L)
    }

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
                    val platform = pending?.let { OfferState.platformLabel(it.packageName) }.orEmpty()
                    CaptureEventLog.append(
                        this@OfferAccessibilityService,
                        stage = "unlocked",
                        platform = platform,
                        message = if (pending == null) "Device unlocked; observing courier screens" else "Device unlocked; resuming pending capture",
                    )
                    // NotificationListenerService retries the exact active offer PendingIntent on
                    // unlock. Do not race it by launching the courier home activity from here.
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
        val eventPackage = event?.packageName?.toString().orEmpty()
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && eventPackage.isNotBlank()) {
            LiveAdvisorHub.onForegroundWindowChanged(this, eventPackage)
        }
        if (CourierSignals.isCourierPackage(eventPackage)) {
            LiveAdvisorHub.onCourierWindowEvent(this, eventPackage)
            if (eventPackage == CourierSignals.WOLT_PACKAGE) {
                OfferState.pending(this)?.takeIf { it.packageName == CourierSignals.WOLT_PACKAGE }?.let { pending ->
                    ensureWoltPricePolling(pending, expedite = true)
                }
            }
            if (OfferOpenState.markWindowVisible(this, eventPackage)) {
                CaptureEventLog.append(
                    this,
                    stage = "open_window_seen",
                    platform = OfferState.platformLabel(eventPackage),
                    message = "Accessibility observed the courier window after an open request",
                )
            }
            lastCourierEventAtElapsed = SystemClock.elapsedRealtime()
            lastCourierEventPackage = eventPackage
            scheduleAttempt(80L)
            return
        }

        val pending = OfferState.pending(this)
        if (pending != null && eventPackage == "com.android.systemui") scheduleAttempt(100L)
    }

    override fun onInterrupt() {
        CaptureEventLog.append(this, "accessibility", "Accessibility service interrupted", dedupeWindowMs = 10_000L)
    }

    override fun onDestroy() {
        captureInFlight = false
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

    private fun attemptCapture() {
        if (captureInFlight && !recoverTimedOutCaptureIfNeeded()) {
            val priceReady = probeWoltAccessibilityPrice()
            val pending = OfferState.pending(this)
            if (!priceReady && pending?.packageName == CourierSignals.WOLT_PACKAGE) {
                scheduleAttempt(WOLT_FAST_PRICE_POLL_MS)
            }
            return
        }
        var pending = OfferState.pending(this)

        if (pending == null) {
            val visible = findAnyCourierWindow()
            if (visible != null) {
                val uiText = collectVisibleText(visible.root)
                observeCourierScreen(visible.packageName, uiText)
                if (uiText.isNotBlank()) OfferState.saveUiText(this, uiText)
                val parsed = OfferParser.parse(uiText)

                // If the live advisor already owns this courier screen, discovery OCR would only
                // screenshot our own card, trigger ColorOS capture UI and risk re-arming the same
                // Wolt offer. Stay idle until the advisor confirms a stable replacement instead.
                if (LiveAdvisorHub.isCurrentTrackedOfferScreen(visible.packageName, parsed)) {
                    CaptureEventLog.append(
                        this,
                        stage = "screen_live_duplicate",
                        platform = OfferState.platformLabel(visible.packageName),
                        message = "Active advisor owns visible offer; skipped discovery OCR and re-arm",
                        dedupeWindowMs = 10_000L,
                    )
                    scheduleAttempt(IDLE_WATCHDOG_MS)
                    return
                }

                if (armFromVisibleOffer(visible.packageName, uiText, parsed)) {
                    pending = OfferState.pending(this)
                } else if (shouldRunDiscoveryOcr(visible.packageName)) {
                    discoverCurrentFrame(visible, uiText)
                    return
                }
            }
            if (pending == null) {
                scheduleAttempt(IDLE_WATCHDOG_MS)
                return
            }
        }

        val platform = OfferState.platformLabel(pending.packageName)
        if (pending.packageName == CourierSignals.WOLT_PACKAGE) ensureWoltPricePolling(pending)
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

        val currentUiText = collectVisibleText(target.root)
        val uiText = accumulateOfferFrame(pending, currentUiText)
        observeCourierScreen(target.packageName, uiText)
        if (uiText.isNotBlank()) OfferState.saveUiText(this, uiText)
        val parsed = OfferParser.parse(uiText)
        if (maybeResolveWoltHiddenDropoffs(target.root, pending, currentUiText, parsed)) return

        // Bolt's map is not semantically exposed on the current real-device build. Even if a future
        // build exposes a price through Accessibility, keep Bolt metadata on the spatially isolated
        // bottom-card OCR path so map labels can never become merchant names.
        if (parsed.priceCents != null && target.packageName != CourierSignals.BOLT_PACKAGE) {
            CaptureEventLog.append(this, "price_accessibility", "Price detected in Accessibility tree", platform, 3_000L)
            // The preview already owns the overlay. Update its priced state immediately instead of
            // waiting for the optional proof screenshot + DB insert to finish.
            LiveAdvisorHub.showPendingOffer(this, pending, parsed)
            if (CaptureStorageSettings.saveOfferScreenshots(this)) {
                captureCurrentFrameAndPersist(pending, target.windowId, uiText, parsed)
            } else {
                persistOffer(null, pending, uiText, parsed)
            }
        } else {
            CaptureEventLog.append(this, "price_wait", "Price not exposed yet; checking current frame with OCR", platform, 5_000L)
            captureCurrentFrameForOcr(pending, target.windowId, currentUiText)
        }
    }

    /**
     * Wolt can expose the final price through Accessibility while a screenshot/OCR request that
     * started a moment earlier is still running. Do not make the live €/km card wait for that
     * optional capture: read the lightweight tree and update the already-visible advisor at once.
     * Persistence remains serialized by the normal capture loop.
     */
    private fun probeWoltAccessibilityPrice(): Boolean {
        val pending = OfferState.pending(this) ?: return false
        if (pending.packageName != CourierSignals.WOLT_PACKAGE) return false
        val target = findCourierWindow(pending) ?: return false
        val currentUiText = collectVisibleText(target.root)
        if (currentUiText.isBlank()) return false
        val uiText = accumulateOfferFrame(pending, currentUiText)
        val parsed = OfferParser.parse(uiText)
        val price = parsed.priceCents ?: return false
        val money = parsed.money ?: return false

        val key = "${pending.packageName}|${pending.armedAt}|$price|${money.currencyCode}"
        if (key == lastFastAccessibilityPriceKey) return true
        lastFastAccessibilityPriceKey = key
        OfferState.saveUiText(this, uiText)
        LiveAdvisorHub.showPendingOffer(this, pending, parsed)
        CaptureEventLog.append(
            this,
            stage = "price_accessibility_fast",
            platform = "Wolt",
            message = "Hot Accessibility price watcher pushed price directly into the live card",
            dedupeWindowMs = 3_000L,
        )
        return true
    }

    private fun ensureWoltPricePolling(pending: PendingOffer, expedite: Boolean = false) {
        if (pending.packageName != CourierSignals.WOLT_PACKAGE) return
        val key = "${pending.packageName}|${pending.armedAt}|${pending.notificationKey}"
        if (key != woltPricePollKey) {
            woltPricePollKey = key
            handler.removeCallbacks(woltPricePollRunnable)
            handler.post(woltPricePollRunnable)
            return
        }
        if (expedite) {
            handler.removeCallbacks(woltPricePollRunnable)
            handler.post(woltPricePollRunnable)
        }
    }

    private fun pollPendingWoltAccessibilityPrice() {
        val pending = OfferState.pending(this)
        if (pending == null || pending.packageName != CourierSignals.WOLT_PACKAGE) {
            woltPricePollKey = ""
            return
        }
        val key = "${pending.packageName}|${pending.armedAt}|${pending.notificationKey}"
        if (woltPricePollKey != key) {
            woltPricePollKey = key
        }

        val now = SystemClock.elapsedRealtime()
        val sinceLast = now - lastWoltPriceProbeAtElapsed
        if (sinceLast >= 0L && sinceLast < WOLT_PRICE_EVENT_THROTTLE_MS) {
            handler.postDelayed(woltPricePollRunnable, WOLT_PRICE_EVENT_THROTTLE_MS - sinceLast)
            return
        }
        lastWoltPriceProbeAtElapsed = now
        if (probeWoltAccessibilityPrice()) {
            woltPricePollKey = ""
            return
        }
        handler.postDelayed(woltPricePollRunnable, WOLT_HOT_PRICE_POLL_MS)
    }

    private fun accumulateOfferFrame(pending: PendingOffer, currentText: String): String {
        if (pending.packageName != CourierSignals.WOLT_PACKAGE) return currentText
        val key = "${pending.packageName}|${pending.armedAt}|${pending.notificationKey}"
        if (key != woltFrameKey) {
            woltFrameKey = key
            woltCardFrameText = ""
            woltDropoffFrameText = ""
            woltDropoffProbeKey = key
            woltDropoffProbeAttempts = 0
            woltDropoffSheetSettleAttempts = 0
        }

        val clean = currentText.trim()
        if (clean.isNotBlank()) {
            when {
                WoltOfferUiText.hasExpandedMultipleDropoffSheet(clean) -> woltDropoffFrameText = clean
                WoltOfferUiText.hasModernOfferStructure(clean) -> woltCardFrameText = clean
            }
        }
        val frames = listOf(woltCardFrameText, woltDropoffFrameText)
            .filter { it.isNotBlank() }
            .distinct()
        return if (frames.isEmpty()) currentText else frames.joinToString(separator = 10.toChar().toString())
    }

    /**
     * The redesigned Wolt card hides batched customer addresses behind a separate row. When Wolt
     * routing is enabled, briefly expand that row, accumulate the modal text with the base card, and
     * close it again before persisting. If Wolt stops exposing a clickable node, fail open after two
     * attempts and preserve the existing incomplete-route fallback instead of trapping the courier UI.
     */
    private fun maybeResolveWoltHiddenDropoffs(
        root: AccessibilityNodeInfo,
        pending: PendingOffer,
        currentText: String,
        parsed: ParsedOffer,
    ): Boolean {
        if (pending.packageName != CourierSignals.WOLT_PACKAGE) return false
        if (!LiveAdvisorSettings.automaticWoltRouting(this)) return false

        val expectedDropoffs = parsed.deliveryCount?.coerceAtLeast(1) ?: return false
        val currentIsExpanded = WoltOfferUiText.hasExpandedMultipleDropoffSheet(currentText)
        if (currentIsExpanded) {
            if (parsed.dropoffAddresses.size < expectedDropoffs) {
                woltDropoffSheetSettleAttempts += 1
                if (woltDropoffSheetSettleAttempts < WOLT_DROPOFF_SHEET_MAX_SETTLE_ATTEMPTS) {
                    scheduleAttempt(WOLT_DROPOFF_SHEET_SETTLE_MS)
                    return true
                }
                val closedIncomplete = clickAccessibilityText(root) { value -> value.equals("done", ignoreCase = true) } ||
                    performGlobalAction(GLOBAL_ACTION_BACK)
                CaptureEventLog.append(
                    this,
                    stage = "wolt_dropoffs_incomplete",
                    platform = "Wolt",
                    message = "Drop-off sheet stayed incomplete after OCR/Accessibility retries; returning to fallback capture",
                    dedupeWindowMs = 2_000L,
                )
                if (closedIncomplete) {
                    scheduleAttempt(WOLT_DROPOFF_SHEET_SETTLE_MS)
                    return true
                }
                return false
            }
            woltDropoffSheetSettleAttempts = 0
            val closed = clickAccessibilityText(root) { value -> value.equals("done", ignoreCase = true) } ||
                performGlobalAction(GLOBAL_ACTION_BACK)
            if (closed) {
                CaptureEventLog.append(
                    this,
                    stage = "wolt_dropoffs_close",
                    platform = "Wolt",
                    message = "Captured hidden customer stops and closed the Wolt drop-off sheet",
                    dedupeWindowMs = 2_000L,
                )
                scheduleAttempt(WOLT_DROPOFF_SHEET_SETTLE_MS)
                return true
            }
            return false
        }

        if (parsed.dropoffAddresses.size >= expectedDropoffs) return false
        if (!WoltOfferUiText.hasCollapsedMultipleDropoffs(currentText)) return false

        val key = "${pending.packageName}|${pending.armedAt}|${pending.notificationKey}"
        if (woltDropoffProbeKey != key) {
            woltDropoffProbeKey = key
            woltDropoffProbeAttempts = 0
        }
        if (woltDropoffProbeAttempts >= WOLT_DROPOFF_PROBE_MAX_ATTEMPTS) return false

        woltDropoffProbeAttempts += 1
        val opened = clickAccessibilityText(root) { value ->
            value.lowercase().contains("multiple drop-off")
        }
        if (opened) {
            woltDropoffSheetSettleAttempts = 0
            CaptureEventLog.append(
                this,
                stage = "wolt_dropoffs_expand",
                platform = "Wolt",
                message = "Opened the redesigned Wolt multiple-drop-off sheet to recover hidden addresses",
                dedupeWindowMs = 2_000L,
            )
            scheduleAttempt(WOLT_DROPOFF_SHEET_SETTLE_MS)
            return true
        }

        CaptureEventLog.append(
            this,
            stage = "wolt_dropoffs_click_missed",
            platform = "Wolt",
            message = "Multiple-drop-off row was visible but not clickable through Accessibility",
            dedupeWindowMs = 2_000L,
        )
        if (woltDropoffProbeAttempts < WOLT_DROPOFF_PROBE_MAX_ATTEMPTS) {
            scheduleAttempt(WOLT_DROPOFF_SHEET_SETTLE_MS)
            return true
        }
        return false
    }

    private fun clickAccessibilityText(
        root: AccessibilityNodeInfo,
        matches: (String) -> Boolean,
    ): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 700) {
            val node = queue.removeFirst()
            visited += 1
            val values = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                .map { it.trim().replace(Regex("\\s+"), " ") }
                .filter { it.isNotBlank() }
            if (node.isVisibleToUser && values.any(matches)) {
                var clickable: AccessibilityNodeInfo? = node
                repeat(5) {
                    val candidate = clickable ?: return@repeat
                    if (candidate.isClickable && candidate.isEnabled &&
                        candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    ) {
                        return true
                    }
                    clickable = candidate.parent
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return false
    }

    private data class CourierWindow(val root: AccessibilityNodeInfo, val windowId: Int, val packageName: String)

    private fun findAnyCourierWindow(): CourierWindow? {
        val active = rootInActiveWindow
        val activePackage = active?.packageName?.toString().orEmpty()
        if (active != null && CourierSignals.isCourierPackage(activePackage)) {
            return CourierWindow(active, active.windowId, activePackage)
        }
        windows.forEach { window ->
            val root = runCatching { window.root }.getOrNull() ?: return@forEach
            val pkg = root.packageName?.toString().orEmpty()
            if (CourierSignals.isCourierPackage(pkg)) return CourierWindow(root, window.id, pkg)
        }
        return null
    }

    private fun findCourierWindow(pending: PendingOffer): CourierWindow? {
        val active = rootInActiveWindow
        if (active?.packageName?.toString() == pending.packageName) {
            return CourierWindow(active, active.windowId, pending.packageName)
        }
        windows.forEach { window ->
            val root = runCatching { window.root }.getOrNull() ?: return@forEach
            if (root.packageName?.toString() == pending.packageName) {
                return CourierWindow(root, window.id, pending.packageName)
            }
        }
        return null
    }

    private fun observeCourierScreen(
        packageName: String,
        text: String,
        source: ScreenTextSource = ScreenTextSource.ACCESSIBILITY,
    ) {
        if (OfferOpenState.markWindowVisible(this, packageName)) {
            CaptureEventLog.append(
                this,
                stage = "open_window_seen",
                platform = OfferState.platformLabel(packageName),
                message = "Accessibility observed the courier window after an open request",
            )
        }
        if (text.isBlank()) return

        val parsed = OfferParser.parse(text)
        if (CourierSignals.looksLikeOfferScreen(text, parsed)) {
            if (OfferOpenState.markOfferVisible(this, packageName)) {
                CaptureEventLog.append(
                    this,
                    stage = "open_offer_seen",
                    platform = OfferState.platformLabel(packageName),
                    message = "Accessibility/OCR verified an actual offer screen",
                )
            }
            val pending = OfferState.pending(this)
            val learned = pending != null &&
                pending.packageName == packageName &&
                pending.notificationKey.isNotBlank() &&
                !pending.notificationKey.startsWith("screen:") &&
                NotificationOfferProfileStore.confirmCandidate(this, packageName, pending.notificationKey)
            if (learned) {
                CaptureEventLog.append(
                    this,
                    stage = "notification_profile_learned",
                    platform = OfferState.platformLabel(packageName),
                    message = "Confirmed offer screen taught CourierPilot a structural notification profile",
                    dedupeWindowMs = 10_000L,
                )
            }
        }

        CourierPresence.markScreen(this, packageName, CourierSignals.detectPresence(text))
        DeliveryMemory.observeScreen(this, packageName, text, source)
    }

    private fun armFromVisibleOffer(packageName: String, text: String, parsed: ParsedOffer): Boolean {
        if (!CourierSignals.looksLikeOfferScreen(text, parsed)) return false
        OfferOpenState.markOfferVisible(this, packageName)

        // Direct screen discovery is only a fallback for missed notifications. Once the live advisor
        // already owns this same offer, re-arming it causes a second capture transaction which hides
        // the card before the later DB duplicate guard can run. Suppress that self-recapture here.
        if (LiveAdvisorHub.isCurrentTrackedOfferScreen(packageName, parsed)) {
            CaptureEventLog.append(
                this,
                stage = "screen_live_duplicate",
                platform = OfferState.platformLabel(packageName),
                message = "Visible offer already belongs to the active advisor; skipped screen re-arm",
                dedupeWindowMs = 10_000L,
            )
            return false
        }

        val fingerprint = CourierSignals.offerFingerprint(packageName, text)
        if (!ScreenOfferDeduper.shouldArm(this, packageName, fingerprint)) return false

        val result = OfferState.arm(this, packageName, resolveAppName(packageName), "screen:$fingerprint")
        val armed = result == ArmResult.ARMED || result == ArmResult.REPLACED_SAME_PLATFORM
        if (armed) {
            CaptureEventLog.append(
                this,
                stage = "screen_armed",
                platform = OfferState.platformLabel(packageName),
                message = "Offer detected directly on courier screen; notification was not required",
            )
        }
        return armed
    }

    private fun shouldRunDiscoveryOcr(packageName: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (packageName != lastCourierEventPackage) return false
        if (now - lastCourierEventAtElapsed > DISCOVERY_EVENT_WINDOW_MS) return false
        if (now - lastDiscoveryOcrAtElapsed < DISCOVERY_OCR_MIN_INTERVAL_MS) return false
        lastDiscoveryOcrAtElapsed = now
        return true
    }

    private fun discoverCurrentFrame(window: CourierWindow, accessibilityText: String) {
        val platform = OfferState.platformLabel(window.packageName)
        val captureToken = beginCapture("discovery screenshot/OCR", platform)
        takeTargetScreenshot(
            window.windowId,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    if (!isCaptureCurrent(captureToken)) {
                        discardScreenshot(screenshot)
                        return
                    }
                    val bitmap = screenshotToBitmap(screenshot)
                    if (bitmap == null) {
                        finishCapture(captureToken)
                        CaptureEventLog.append(
                            this@OfferAccessibilityService,
                            "bitmap_failed",
                            "Discovery screenshot buffer could not be converted; retrying soon",
                            platform,
                            3_000L,
                        )
                        scheduleAttempt(DISCOVERY_SCREENSHOT_RETRY_MS)
                        return
                    }
                    recognizer.process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { result ->
                            if (!isCaptureCurrent(captureToken)) {
                                bitmap.recycle()
                                return@addOnSuccessListener
                            }
                            val combined = OfferOcrText.combine(window.packageName, accessibilityText, result, bitmap.height)
                            observeCourierScreen(window.packageName, combined, ScreenTextSource.OCR_AUGMENTED)
                            if (combined.isNotBlank()) OfferState.saveUiText(this@OfferAccessibilityService, combined)
                            val parsed = OfferParser.parse(combined)
                            val armed = armFromVisibleOffer(window.packageName, combined, parsed)
                            val pending = if (armed) OfferState.pending(this@OfferAccessibilityService) else null
                            val trustedPrice = parsed.priceCents != null && (
                                window.packageName != CourierSignals.WOLT_PACKAGE ||
                                    CourierSignals.isTrustedWoltOcrOffer(combined, parsed)
                                )
                            finishCapture(captureToken)
                            if (pending != null && trustedPrice) {
                                persistOffer(bitmap, pending, combined, parsed)
                            } else {
                                if (pending != null && parsed.priceCents != null && !trustedPrice) {
                                    CaptureEventLog.append(
                                        this@OfferAccessibilityService,
                                        "price_ocr_untrusted",
                                        "Ignored Wolt OCR money without a complete offer identity; retrying",
                                        platform,
                                        3_000L,
                                    )
                                }
                                bitmap.recycle()
                                scheduleAttempt(if (pending != null) 250L else IDLE_WATCHDOG_MS)
                            }
                        }
                        .addOnFailureListener {
                            if (finishCapture(captureToken)) {
                                bitmap.recycle()
                                scheduleAttempt(IDLE_WATCHDOG_MS)
                            } else if (!bitmap.isRecycled) {
                                bitmap.recycle()
                            }
                        }
                }

                override fun onFailure(errorCode: Int) {
                    if (!finishCapture(captureToken)) return
                    CaptureEventLog.append(
                        this@OfferAccessibilityService,
                        "discovery_screenshot_failed",
                        "Screenshot failed during screen discovery: Android error $errorCode; retrying soon",
                        platform,
                        3_000L,
                    )
                    scheduleAttempt(DISCOVERY_SCREENSHOT_RETRY_MS)
                }
            },
        )
    }

    private fun captureCurrentFrameForOcr(pending: PendingOffer, windowId: Int, accessibilityText: String) {
        val platform = OfferState.platformLabel(pending.packageName)
        val probeStartedAt = SystemClock.elapsedRealtime()
        val captureToken = beginCapture("offer screenshot/OCR", platform)
        takeTargetScreenshot(
            windowId,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    if (!isCaptureCurrent(captureToken)) {
                        discardScreenshot(screenshot)
                        return
                    }
                    val bitmap = screenshotToBitmap(screenshot)
                    if (bitmap == null) {
                        val failures = recordScreenshotFailure(pending)
                        finishCapture(captureToken)
                        CaptureEventLog.append(this@OfferAccessibilityService, "bitmap_failed", "Android screenshot buffer could not be converted (attempt $failures)", platform, 5_000L)
                        scheduleAttempt(adaptiveOcrDelay(pending))
                        return
                    }
                    resetScreenshotFailures(pending)
                    val accumulatedAccessibilityText = accumulateOfferFrame(pending, accessibilityText)
                    val earlyParsed = OfferParser.parse(accumulatedAccessibilityText)
                    if (CourierSignals.looksLikeOfferScreen(accumulatedAccessibilityText, earlyParsed)) {
                        LiveAdvisorHub.showPendingOffer(this@OfferAccessibilityService, pending, earlyParsed)
                    }

                    val ocrStartedAt = SystemClock.elapsedRealtime()
                    recognizer.process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { result ->
                            if (!isCaptureCurrent(captureToken)) {
                                bitmap.recycle()
                                return@addOnSuccessListener
                            }
                            val combinedCurrent = OfferOcrText.combine(pending.packageName, accessibilityText, result, bitmap.height)
                            val combined = accumulateOfferFrame(pending, combinedCurrent)
                            observeCourierScreen(pending.packageName, combined, ScreenTextSource.OCR_AUGMENTED)
                            if (combined.isNotBlank()) OfferState.saveUiText(this@OfferAccessibilityService, combined)
                            val parsedText = OfferParser.parse(combined)
                            val spatialWoltMoney = if (pending.packageName == CourierSignals.WOLT_PACKAGE) {
                                OfferOcrText.woltEarningsMoney(result, bitmap.height)
                            } else null
                            val parsed = if (spatialWoltMoney != null) {
                                parsedText.copy(
                                    priceCents = spatialWoltMoney.amountMinor
                                        .takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }
                                        ?.toInt(),
                                    money = spatialWoltMoney,
                                )
                            } else parsedText
                            val trustedPrice = parsed.priceCents != null && (
                                pending.packageName != CourierSignals.WOLT_PACKAGE ||
                                    CourierSignals.isTrustedWoltOcrOffer(combined, parsed)
                                )
                            val latestRoot = findCourierWindow(pending)?.root
                            if (latestRoot != null &&
                                maybeResolveWoltHiddenDropoffs(latestRoot, pending, combinedCurrent, parsed)
                            ) {
                                finishCapture(captureToken)
                                bitmap.recycle()
                                return@addOnSuccessListener
                            }
                            CaptureEventLog.append(
                                this@OfferAccessibilityService,
                                stage = "ocr_price_probe",
                                platform = platform,
                                message = "capture_ms=${(ocrStartedAt - probeStartedAt).coerceAtLeast(0L)}; " +
                                    "ocr_ms=${(SystemClock.elapsedRealtime() - ocrStartedAt).coerceAtLeast(0L)}; " +
                                    "spatial=${spatialWoltMoney != null}; price=${parsed.priceCents != null}; trusted=$trustedPrice",
                                dedupeWindowMs = 1_500L,
                            )
                            if (spatialWoltMoney != null && trustedPrice) {
                                CaptureEventLog.append(
                                    this@OfferAccessibilityService,
                                    stage = "price_ocr_spatial",
                                    platform = platform,
                                    message = "Spatial Wolt earnings OCR matched the visible amount to its label",
                                    dedupeWindowMs = 3_000L,
                                )
                            }
                            if (CourierSignals.looksLikeOfferScreen(combined, parsed) &&
                                (parsed.priceCents == null || trustedPrice)
                            ) {
                                LiveAdvisorHub.showPendingOffer(this@OfferAccessibilityService, pending, parsed)
                            }
                            finishCapture(captureToken)
                            if (parsed.priceCents != null && trustedPrice) {
                                CaptureEventLog.append(this@OfferAccessibilityService, "price_ocr", "Price detected by OCR fallback", platform)
                                persistOffer(bitmap, pending, combined, parsed)
                            } else {
                                if (parsed.priceCents != null && !trustedPrice) {
                                    CaptureEventLog.append(
                                        this@OfferAccessibilityService,
                                        "price_ocr_untrusted",
                                        "Ignored Wolt OCR money without a complete offer identity; retrying",
                                        platform,
                                        3_000L,
                                    )
                                }
                                bitmap.recycle()
                                scheduleAttempt(adaptiveOcrDelay(pending))
                            }
                        }
                        .addOnFailureListener { error ->
                            val current = finishCapture(captureToken)
                            if (!bitmap.isRecycled) bitmap.recycle()
                            if (!current) return@addOnFailureListener
                            OfferState.markError(this@OfferAccessibilityService, "Waiting for price; OCR not ready: ${error.message ?: error.javaClass.simpleName}")
                            CaptureEventLog.append(this@OfferAccessibilityService, "ocr_failed", error.javaClass.simpleName, platform, 5_000L)
                            scheduleAttempt(adaptiveOcrDelay(pending).coerceAtLeast(1_500L))
                        }
                }

                override fun onFailure(errorCode: Int) {
                    if (!finishCapture(captureToken)) return
                    handleScreenshotFailure(errorCode, retry = true, pending = pending)
                }
            },
        )
    }

    private fun captureCurrentFrameAndPersist(pending: PendingOffer, windowId: Int, text: String, parsed: ParsedOffer) {
        val platform = OfferState.platformLabel(pending.packageName)
        val captureToken = beginCapture("final offer screenshot", platform)
        takeTargetScreenshot(
            windowId,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    if (!isCaptureCurrent(captureToken)) {
                        discardScreenshot(screenshot)
                        return
                    }
                    val bitmap = screenshotToBitmap(screenshot)
                    if (bitmap == null) {
                        val failures = recordScreenshotFailure(pending)
                        finishCapture(captureToken)
                        CaptureEventLog.append(
                            this@OfferAccessibilityService,
                            "bitmap_failed",
                            "Final screenshot buffer could not be converted (attempt $failures)",
                            platform,
                            3_000L,
                        )
                        if (failures >= OPTIONAL_SCREENSHOT_FAILURE_LIMIT) {
                            CaptureEventLog.append(
                                this@OfferAccessibilityService,
                                "saved_without_screenshot",
                                "Price is already known; saving offer metadata after repeated optional screenshot failures",
                                platform,
                            )
                            LiveAdvisorHub.showPendingOffer(this@OfferAccessibilityService, pending, parsed)
                            persistOffer(null, pending, text, parsed)
                        } else {
                            scheduleAttempt(500L)
                        }
                        return
                    }
                    resetScreenshotFailures(pending)
                    LiveAdvisorHub.showPendingOffer(this@OfferAccessibilityService, pending, parsed)
                    finishCapture(captureToken)
                    persistOffer(bitmap, pending, text, parsed)
                }

                override fun onFailure(errorCode: Int) {
                    if (!finishCapture(captureToken)) return
                    val failures = recordScreenshotFailure(pending)
                    if (failures >= OPTIONAL_SCREENSHOT_FAILURE_LIMIT) {
                        CaptureEventLog.append(
                            this@OfferAccessibilityService,
                            "saved_without_screenshot",
                            "Price is already known; saving offer metadata after $failures screenshot failures",
                            platform,
                        )
                        LiveAdvisorHub.showPendingOffer(this@OfferAccessibilityService, pending, parsed)
                        persistOffer(null, pending, text, parsed)
                    } else {
                        handleScreenshotFailure(errorCode, retry = true, pending = pending, failureCount = failures)
                    }
                }
            },
        )
    }

    private fun beginCapture(operation: String, platform: String): Long {
        captureInFlight = true
        val token = captureGuard.begin(SystemClock.elapsedRealtime(), operation, platform)
        handler.removeCallbacks(captureWatchdogRunnable)
        handler.postDelayed(captureWatchdogRunnable, CAPTURE_OPERATION_TIMEOUT_MS)
        return token
    }

    private fun isCaptureCurrent(token: Long): Boolean = captureInFlight && captureGuard.isCurrent(token)

    private fun finishCapture(token: Long): Boolean {
        val finished = captureGuard.finish(token)
        if (finished) {
            captureInFlight = false
            handler.removeCallbacks(captureWatchdogRunnable)
        }
        return finished
    }

    private fun recoverTimedOutCaptureIfNeeded(): Boolean {
        val timedOut = captureGuard.recoverIfTimedOut(SystemClock.elapsedRealtime()) ?: return false
        captureInFlight = false
        handler.removeCallbacks(captureWatchdogRunnable)
        CaptureEventLog.append(
            this,
            stage = "capture_watchdog_recovered",
            platform = timedOut.platform,
            message = "Recovered stuck ${timedOut.operation} after ${timedOut.ageMs} ms; capture loop resumed",
            dedupeWindowMs = 3_000L,
        )
        return true
    }

    private fun discardScreenshot(screenshot: ScreenshotResult) {
        runCatching { screenshot.hardwareBuffer.close() }
    }

    private fun takeTargetScreenshot(windowId: Int, callback: TakeScreenshotCallback) {
        if (Build.VERSION.SDK_INT < 34) {
            LiveAdvisorHub.setCaptureSuppressed(this, true)
            val cleanCallback = object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    LiveAdvisorHub.setCaptureSuppressed(this@OfferAccessibilityService, false)
                    callback.onSuccess(screenshot)
                }

                override fun onFailure(errorCode: Int) {
                    LiveAdvisorHub.setCaptureSuppressed(this@OfferAccessibilityService, false)
                    callback.onFailure(errorCode)
                }
            }
            runCatching { takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, cleanCallback) }
                .onFailure {
                    LiveAdvisorHub.setCaptureSuppressed(this, false)
                    CaptureEventLog.append(
                        this,
                        "screenshot_request_exception",
                        "Display screenshot request threw ${it.javaClass.simpleName}",
                        dedupeWindowMs = 3_000L,
                    )
                    callback.onFailure(ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)
                }
            return
        }

        // Window-scoped capture is ideal, but courier activities transition quickly and Android can
        // reject a perfectly valid request because the window id went stale between discovery and
        // capture. Fall back to a display screenshot instead of losing the offer.
        val windowCallback = object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                callback.onSuccess(screenshot)
            }

            override fun onFailure(errorCode: Int) {
                if (!shouldFallbackToDisplayScreenshot(errorCode)) {
                    callback.onFailure(errorCode)
                    return
                }

                val delay = if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                    DISPLAY_SCREENSHOT_RATE_LIMIT_RETRY_MS
                } else {
                    DISPLAY_SCREENSHOT_FALLBACK_DELAY_MS
                }
                CaptureEventLog.append(
                    this@OfferAccessibilityService,
                    "screenshot_window_fallback",
                    "Window screenshot failed with Android error $errorCode; retrying as display capture",
                    dedupeWindowMs = 2_000L,
                )
                handler.postDelayed({ requestDisplayScreenshotFallback(callback) }, delay)
            }
        }

        runCatching { takeScreenshotOfWindow(windowId, mainExecutor, windowCallback) }
            .onFailure {
                CaptureEventLog.append(
                    this,
                    "screenshot_window_exception",
                    "Window screenshot request threw ${it.javaClass.simpleName}; trying display capture",
                    dedupeWindowMs = 3_000L,
                )
                handler.postDelayed(
                    { requestDisplayScreenshotFallback(callback) },
                    DISPLAY_SCREENSHOT_FALLBACK_DELAY_MS,
                )
            }
    }

    private fun requestDisplayScreenshotFallback(callback: TakeScreenshotCallback) {
        LiveAdvisorHub.setCaptureSuppressed(this, true)
        val displayCallback = object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                LiveAdvisorHub.setCaptureSuppressed(this@OfferAccessibilityService, false)
                CaptureEventLog.append(
                    this@OfferAccessibilityService,
                    "screenshot_display_fallback_ok",
                    "Display screenshot fallback succeeded",
                    dedupeWindowMs = 3_000L,
                )
                callback.onSuccess(screenshot)
            }

            override fun onFailure(displayErrorCode: Int) {
                LiveAdvisorHub.setCaptureSuppressed(this@OfferAccessibilityService, false)
                CaptureEventLog.append(
                    this@OfferAccessibilityService,
                    "screenshot_display_fallback_failed",
                    "Display screenshot fallback failed with Android error $displayErrorCode",
                    dedupeWindowMs = 3_000L,
                )
                callback.onFailure(displayErrorCode)
            }
        }
        runCatching { takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, displayCallback) }
            .onFailure {
                LiveAdvisorHub.setCaptureSuppressed(this, false)
                CaptureEventLog.append(
                    this,
                    "screenshot_display_exception",
                    "Display screenshot request threw ${it.javaClass.simpleName}",
                    dedupeWindowMs = 3_000L,
                )
                callback.onFailure(ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)
            }
    }

    private fun shouldFallbackToDisplayScreenshot(errorCode: Int): Boolean {
        if (errorCode == ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS) return false
        if (Build.VERSION.SDK_INT >= 34 && errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) return false
        return true
    }

    private fun persistOffer(bitmap: Bitmap?, pending: PendingOffer, rawText: String, parsed: ParsedOffer) {
        val platform = OfferState.platformLabel(pending.packageName)
        val current = OfferState.pending(this)
        val stillCurrent = current != null &&
            current.packageName == pending.packageName &&
            current.armedAt == pending.armedAt &&
            (pending.notificationKey.isBlank() || current.notificationKey == pending.notificationKey)
        if (!stillCurrent) {
            bitmap?.recycle()
            captureInFlight = false
            CaptureEventLog.append(this, "stale_callback", "Discarded capture from superseded offer", platform)
            scheduleAttempt(100L)
            return
        }

        val money = parsed.money
        val priceCents = parsed.priceCents
        if (money == null || priceCents == null) {
            bitmap?.recycle()
            captureInFlight = false
            scheduleAttempt(adaptiveOcrDelay(pending))
            return
        }

        OfferOpenState.markOfferVisible(this, pending.packageName)
        if (pending.notificationKey.isNotBlank() && !pending.notificationKey.startsWith("screen:")) {
            NotificationOfferProfileStore.confirmCandidate(this, pending.packageName, pending.notificationKey)
        }

        val database = OfferDatabase.get(this)
        val visualFingerprint = if (pending.packageName == CourierSignals.BOLT_PACKAGE && bitmap != null) {
            OfferVisualFingerprint.fromBottomCard(bitmap).orEmpty()
        } else {
            ""
        }
        val candidate = OfferRecord(
            capturedAt = pending.armedAt,
            platform = OfferParser.platformName(pending.packageName, pending.sourceName),
            packageName = pending.packageName,
            priceCents = priceCents,
            currencyCode = money.currencyCode,
            currencyFractionDigits = money.fractionDigits,
            distanceMeters = parsed.distanceMeters,
            restaurant = parsed.restaurant,
            screenshotUri = "",
            screenshotFilename = "",
            rawText = rawText,
            merchantNames = parsed.merchantNames,
            pickupAddresses = parsed.pickupAddresses,
            customerNames = parsed.customerNames,
            dropoffAddresses = parsed.dropoffAddresses,
            deliveryCount = parsed.deliveryCount,
            estimatedMinutesMin = parsed.estimatedMinutesMin,
            estimatedMinutesMax = parsed.estimatedMinutesMax,
            captureKey = pending.notificationKey,
            visualFingerprint = visualFingerprint,
        )

        val duplicate = database.findRecentDuplicate(candidate)
        if (duplicate != null) {
            bitmap?.recycle()
            captureInFlight = false
            CaptureEventLog.append(
                this,
                "duplicate_suppressed",
                "Same live offer already exists as record #${duplicate.id}; skipped history insert",
                platform,
            )
            OfferState.clear(this)
            lastHandledArmedAt = 0L
            scheduleAttempt(IDLE_WATCHDOG_MS)
            return
        }

        var saved: SavedScreenshot? = null
        try {
            if (bitmap != null && CaptureStorageSettings.saveOfferScreenshots(this)) {
                saved = runCatching { ScreenshotStore.save(this, bitmap, pending.sourceName) }
                    .onFailure { error ->
                        // Gallery/MediaStore failure must not discard an otherwise valid offer.
                        // Keep the metadata and make the missing screenshot explicit in diagnostics.
                        CaptureEventLog.append(
                            this,
                            "screenshot_save_failed",
                            "Could not persist screenshot: ${error.javaClass.simpleName}; saving offer metadata without image",
                            platform,
                            3_000L,
                        )
                    }
                    .getOrNull()
            }
            val stored = if (saved != null) {
                candidate.copy(
                    screenshotUri = saved.uri.toString(),
                    screenshotFilename = saved.filename,
                )
            } else {
                candidate
            }
            val insertResult = database.insertDeduplicated(stored)
            if (!insertResult.inserted) {
                saved?.let { ScreenshotStore.delete(this, it) }
                saved = null
                CaptureEventLog.append(
                    this,
                    "duplicate_race_suppressed",
                    "Duplicate reached persistence guard; reused record #${insertResult.rowId}",
                    platform,
                )
            } else {
                val screenshotsEnabled = CaptureStorageSettings.saveOfferScreenshots(this)
                OfferState.markCapture(
                    this,
                    when {
                        saved != null -> saved.filename
                        screenshotsEnabled -> "Offer saved · screenshot unavailable"
                        else -> "Offer saved · gallery screenshots disabled"
                    },
                )
                val screenshotStatus = when {
                    saved != null -> "saved"
                    screenshotsEnabled -> "unavailable"
                    else -> "off"
                }
                CaptureEventLog.append(
                    this,
                    "saved",
                    "Offer saved successfully as record #${insertResult.rowId} (${parsed.deliveryCount ?: 1} deliveries; screenshot $screenshotStatus)",
                    platform,
                )
            }
            if (pending.notificationKey.startsWith("screen:")) {
                ScreenOfferDeduper.markArmed(this, pending.packageName, pending.notificationKey.removePrefix("screen:"))
            }
            OfferState.clear(this)
            lastHandledArmedAt = 0L
        } catch (t: Throwable) {
            saved?.let { ScreenshotStore.delete(this, it) }
            OfferState.markError(this, "Offer save failed: ${t.message ?: t.javaClass.simpleName}")
            CaptureEventLog.append(this, "save_failed", t.javaClass.simpleName, platform)
        } finally {
            bitmap?.recycle()
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

    private fun handleScreenshotFailure(
        errorCode: Int,
        retry: Boolean,
        pending: PendingOffer,
        failureCount: Int? = null,
    ) {
        val failures = failureCount ?: recordScreenshotFailure(pending)
        if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
            CaptureEventLog.append(
                this,
                "screenshot_rate_limited",
                "Android screenshot rate limit hit (attempt $failures); retrying",
                OfferState.platformLabel(pending.packageName),
                2_000L,
            )
            if (retry) scheduleAttempt(DISPLAY_SCREENSHOT_RATE_LIMIT_RETRY_MS)
            return
        }
        val reason = when (errorCode) {
            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "no accessibility access"
            ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "invalid display"
            ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "stale/invalid courier window"
            ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "internal Android error"
            else -> if (Build.VERSION.SDK_INT >= 34 && errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) "secure courier window"
            else "Android error $errorCode"
        }
        OfferState.markError(this, "Screenshot failed: $reason (attempt $failures)")
        CaptureEventLog.append(this, "screenshot_failed", "$reason (attempt $failures)", OfferState.platformLabel(pending.packageName), 5_000L)
        if (retry) scheduleAttempt(adaptiveOcrDelay(pending).coerceAtLeast(1_200L))
    }

    private fun recordScreenshotFailure(pending: PendingOffer): Int {
        val key = "${pending.packageName}|${pending.armedAt}|${pending.notificationKey}"
        if (screenshotFailureKey != key) {
            screenshotFailureKey = key
            screenshotFailureCount = 0
        }
        screenshotFailureCount += 1
        return screenshotFailureCount
    }

    private fun resetScreenshotFailures(pending: PendingOffer) {
        val key = "${pending.packageName}|${pending.armedAt}|${pending.notificationKey}"
        screenshotFailureKey = key
        screenshotFailureCount = 0
    }

    private fun adaptiveOcrDelay(pending: PendingOffer): Long {
        val age = System.currentTimeMillis() - pending.armedAt
        if (pending.packageName == CourierSignals.WOLT_PACKAGE) {
            // Accessibility can lag the pixels by many seconds on Wolt. Keep screenshot/OCR as an
            // active visual-price sensor instead of backing off to multi-second sleeps while the
            // courier is deciding whether to accept the offer.
            return when {
                age < 30_000L -> 650L
                age < 90_000L -> 900L
                else -> 1_200L
            }
        }
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

    private fun mergeText(accessibilityText: String, ocrText: String): String =
        listOf(accessibilityText.trim(), ocrText.trim())
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")

    private fun resolveAppName(pkg: String): String = try {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (_: Throwable) {
        pkg
    }

    companion object {
        private const val IDLE_WATCHDOG_MS = 8_000L
        private const val WOLT_FAST_PRICE_POLL_MS = 350L
        private const val WOLT_HOT_PRICE_POLL_MS = 220L
        private const val WOLT_PRICE_EVENT_THROTTLE_MS = 90L
        private const val WOLT_DROPOFF_SHEET_SETTLE_MS = 180L
        private const val WOLT_DROPOFF_SHEET_MAX_SETTLE_ATTEMPTS = 4
        private const val WOLT_DROPOFF_PROBE_MAX_ATTEMPTS = 2
        private const val DISCOVERY_EVENT_WINDOW_MS = 1_500L
        private const val DISCOVERY_OCR_MIN_INTERVAL_MS = 1_800L
        private const val DISCOVERY_SCREENSHOT_RETRY_MS = 1_200L
        private const val DISPLAY_SCREENSHOT_FALLBACK_DELAY_MS = 120L
        private const val DISPLAY_SCREENSHOT_RATE_LIMIT_RETRY_MS = 750L
        private const val OPTIONAL_SCREENSHOT_FAILURE_LIMIT = 3
        private const val CAPTURE_OPERATION_TIMEOUT_MS = 8_000L
    }
}
