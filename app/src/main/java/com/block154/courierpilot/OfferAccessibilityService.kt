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
    private var woltVisibleBasePickupAddresses: List<String> = emptyList()
    private var woltDropoffProbeKey = ""
    private var woltDropoffProbeAttempts = 0
    private var woltDropoffSemanticProbeAttempts = 0
    private var woltDropoffResolvedKey = ""
    private var woltDropoffResolvedCount = 0
    private var woltDropoffSheetSettleAttempts = 0
    private var woltRouteOcrRecoveryAttempts = 0
    private var woltIdleHomeKey = ""
    private var woltIdleHomeFirstSeenAtElapsed = 0L
    private var woltIdleHomeChecks = 0
    private var woltProofBitmap: Bitmap? = null
    private var woltProofOfferKey = ""

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
            val overlayDragging = LiveAdvisorHub.isOverlayGestureActive()
            if (!overlayDragging && eventPackage == CourierSignals.WOLT_PACKAGE) {
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
            scheduleAttempt(if (overlayDragging) OVERLAY_DRAG_CAPTURE_DEFER_MS else 80L)
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
        clearWoltProofBitmap()
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
        if (LiveAdvisorHub.isOverlayGestureActive()) {
            scheduleAttempt(OVERLAY_DRAG_CAPTURE_DEFER_MS)
            return
        }
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
                // Screen discovery must never arm from hidden Compose semantics. Real telemetry from
                // 0.15.46 showed a stale 10.5 km Wolt offer being re-armed from background nodes,
                // producing a bogus €0.55/km card before the actual 3.9 km offer was processed.
                val uiText = collectStrictlyVisibleText(visible.root)
                observeCourierScreen(visible.packageName, uiText)
                if (uiText.isNotBlank()) OfferState.saveUiText(this, uiText)
                val parsed = OfferParser.parse(uiText)
                if (terminatePendingOfferOnActiveTask(visible.packageName, uiText)) {
                    scheduleAttempt(IDLE_WATCHDOG_MS)
                    return
                }
                if (visible.packageName == CourierSignals.WOLT_PACKAGE &&
                    CourierSignals.looksLikeIdleHomeScreen(visible.packageName, uiText) &&
                    !CourierSignals.looksLikeOfferScreen(uiText, parsed)
                ) {
                    LiveAdvisorHub.clearUserDismissal(
                        this,
                        visible.packageName,
                        "Stable Wolt home screen cleared the user-dismissed offer tombstone",
                    )
                }

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

        // Lifetime decisions must use only the surface that is genuinely visible right now. Wolt
        // Compose keeps hidden offer/home semantics around for a while, so using the full tree here
        // can either resurrect a finished offer or incorrectly kill the current one under a modal.
        val visibleUiText = collectStrictlyVisibleText(target.root)
        observeCourierScreen(target.packageName, visibleUiText)
        if (terminatePendingOfferOnActiveTask(target.packageName, visibleUiText)) {
            scheduleAttempt(IDLE_WATCHDOG_MS)
            return
        }
        if (handleWoltIdleHomeSurface(pending, target.packageName, visibleUiText)) return

        // Never parse Wolt's whole semantics tree as the base offer. Old hidden Compose nodes can
        // carry a previous distance/price for seconds and poisoned 0.15.46 with a stale 10.5 km
        // denominator. Hidden customer rows are recovered separately by maybeResolveWoltHiddenDropoffs().
        val currentUiText = if (target.packageName == CourierSignals.WOLT_PACKAGE) {
            visibleUiText
        } else {
            collectVisibleText(target.root)
        }
        val uiText = accumulateOfferFrame(pending, currentUiText)
        if (uiText.isNotBlank()) OfferState.saveUiText(this, uiText)
        val parsed = OfferParser.parse(uiText)

        // Batched Wolt destinations are an Accessibility problem first, not an OCR problem. The
        // live 0.15.42 traces showed the price available immediately while CourierPilot spent
        // several seconds taking a screenshot before it even tried the already-visible destination
        // semantics. Resolve/click the multiple-dropoff surface directly from the current tree and
        // only fall back to screenshot/OCR after the semantic path is exhausted.
        val woltBatchRouteIncomplete = target.packageName == CourierSignals.WOLT_PACKAGE &&
            LiveAdvisorSettings.automaticWoltRouting(this) &&
            (parsed.deliveryCount ?: 0) > 1 &&
            AutomaticWoltRouteCoordinator.routeFingerprint(parsed) == null
        if (woltBatchRouteIncomplete && parsed.priceCents != null && parsed.money != null) {
            // Preserve the priced top-level card in the live advisor *before* opening Wolt's hidden
            // drop-off sheet. That sheet can remove the price node from Accessibility entirely; the
            // 0.15.42 trace had a full 9/10 km route but no €/km because we clicked first and tried
            // to rediscover the price afterwards.
            LiveAdvisorHub.showPendingOffer(this, pending, parsed)
            CaptureEventLog.append(
                this,
                stage = "price_before_dropoff_expand",
                platform = "Wolt",
                message = "Pushed priced batch offer to live card before opening hidden destinations",
                dedupeWindowMs = 1_500L,
            )
        }
        if (woltBatchRouteIncomplete && maybeResolveWoltHiddenDropoffs(target.root, pending, parsed)) {
            CaptureEventLog.append(
                this,
                stage = "wolt_dropoffs_fastpath",
                platform = "Wolt",
                message = "Handled incomplete batch destinations directly from Accessibility before OCR",
                dedupeWindowMs = 1_000L,
            )
            return
        }

        // Bolt's map is not semantically exposed on the current real-device build. Even if a future
        // build exposes a price through Accessibility, keep Bolt metadata on the spatially isolated
        // bottom-card OCR path so map labels can never become merchant names.
        if (parsed.priceCents != null && target.packageName != CourierSignals.BOLT_PACKAGE) {
            CaptureEventLog.append(this, "price_accessibility", "Price detected in Accessibility tree", platform, 3_000L)
            // The preview already owns the overlay. Update its priced state immediately instead of
            // waiting for the optional proof screenshot + DB insert to finish.
            LiveAdvisorHub.showPendingOffer(this, pending, parsed)

            val needsWoltOfferTextRecovery = target.packageName == CourierSignals.WOLT_PACKAGE &&
                WoltOfferTextRecoveryPolicy.needsOcrBeforePersist(
                    parsed,
                    automaticRouting = LiveAdvisorSettings.automaticWoltRouting(this),
                )
            if (needsWoltOfferTextRecovery) {
                val routeIncomplete = AutomaticWoltRouteCoordinator.routeFingerprint(parsed) == null
                CaptureEventLog.append(
                    this,
                    stage = if (routeIncomplete) "route_text_ocr_recovery" else "merchant_text_ocr_recovery",
                    platform = platform,
                    message = if (routeIncomplete) {
                        "Price is ready but Wolt route text is incomplete; augmenting visible card with OCR"
                    } else {
                        "Wolt route is ready but merchant title is missing from Accessibility; augmenting visible card with OCR"
                    },
                    dedupeWindowMs = 1_500L,
                )
                captureCurrentFrameForOcr(pending, target.windowId, currentUiText)
            } else if (CaptureStorageSettings.saveOfferScreenshots(this)) {
                val frozenProof = takeWoltProofBitmap(pending)
                if (frozenProof != null) {
                    CaptureEventLog.append(
                        this,
                        stage = "wolt_frozen_proof_used",
                        platform = platform,
                        message = "Persisting the first frozen Wolt offer frame instead of taking a late screenshot",
                        dedupeWindowMs = 2_000L,
                    )
                    persistOffer(frozenProof, pending, uiText, parsed)
                } else {
                    captureCurrentFrameAndPersist(pending, target.windowId, uiText, parsed)
                }
            } else {
                discardWoltProofBitmap(pending)
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
        // Price polling is a hot path, so it must be even stricter than normal capture: never let
        // hidden Compose nodes from a previous offer inject an old price/distance into the advisor.
        val currentUiText = collectStrictlyVisibleText(target.root)
        if (currentUiText.isBlank()) return false
        if (terminatePendingOfferOnActiveTask(pending.packageName, currentUiText)) return true
        if (CourierSignals.looksLikeWoltDeclineConfirmation(pending.packageName, currentUiText)) return true
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
        if (WoltOfferTextRecoveryPolicy.needsOcrBeforePersist(
                parsed,
                automaticRouting = LiveAdvisorSettings.automaticWoltRouting(this),
            )
        ) {
            // Price readiness is not capture completeness. Wolt may expose money before the merchant
            // or destination semantics settle; immediately wake the capture loop instead of waiting
            // for an unrelated Accessibility event (live 0.15.55 traces showed a ~27 s gap here).
            scheduleAttempt(WOLT_FAST_PRICE_POLL_MS)
        }
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
        if (LiveAdvisorHub.isOverlayGestureActive()) {
            handler.postDelayed(woltPricePollRunnable, OVERLAY_DRAG_CAPTURE_DEFER_MS)
            return
        }
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
            if (woltProofOfferKey.isNotBlank() && woltProofOfferKey != key) clearWoltProofBitmap()
            woltFrameKey = key
            woltCardFrameText = ""
            woltDropoffFrameText = ""
            woltVisibleBasePickupAddresses = emptyList()
            woltDropoffProbeKey = key
            woltDropoffProbeAttempts = 0
            woltDropoffSemanticProbeAttempts = 0
            woltDropoffResolvedKey = ""
            woltDropoffResolvedCount = 0
            woltDropoffSheetSettleAttempts = 0
            woltRouteOcrRecoveryAttempts = 0
            woltIdleHomeKey = ""
            woltIdleHomeFirstSeenAtElapsed = 0L
            woltIdleHomeChecks = 0
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
     * The redesigned Wolt card hides batched customer addresses behind a separate row. Prefer
     * reading already-created but non-visible Accessibility nodes first; Compose can keep the
     * collapsed sheet content in the semantics tree even though OCR cannot see it. Only if that
     * semantic recovery is incomplete do we briefly expand the row. One physical expansion is the
     * maximum for a capture transaction; if it is insufficient, preserve the non-invasive fallback
     * instead of touching the courier UI a second time.
     */
    private fun maybeResolveWoltHiddenDropoffs(
        root: AccessibilityNodeInfo,
        pending: PendingOffer,
        parsed: ParsedOffer,
    ): Boolean {
        if (pending.packageName != CourierSignals.WOLT_PACKAGE) return false
        if (!LiveAdvisorSettings.automaticWoltRouting(this)) return false

        val expectedDropoffs = parsed.deliveryCount?.coerceAtLeast(1) ?: return false
        val key = "${pending.packageName}|${pending.armedAt}|${pending.notificationKey}"
        // Once this exact capture has recovered a complete route, never touch Wolt's disclosure
        // again merely because the collapsed card hides those destinations on a later frame. A
        // genuinely larger route (for example an add-on increasing N) may recover again.
        if (woltDropoffResolvedKey == key && woltDropoffResolvedCount >= expectedDropoffs) return false
        val strictlyVisiblePieces = collectStrictlyVisibleAccessibilityPieces(root)
        val strictlyVisibleText = strictlyVisiblePieces.joinToString("\n")
        val collapsedDisclosureVisible = hasVisibleClickableAccessibilityText(root) { value ->
            value.lowercase().contains("multiple drop-off")
        }
        val collapsedVisible = WoltOfferUiText.hasCollapsedMultipleDropoffs(strictlyVisibleText) ||
            collapsedDisclosureVisible
        val visibleParsed = OfferParser.parse(strictlyVisibleText)
        if (collapsedVisible && visibleParsed.pickupAddresses.isNotEmpty()) {
            // Only remember pickups that are truly visible on the collapsed card. collectVisibleText()
            // intentionally includes hidden Compose semantics, and those hidden customer addresses
            // can be misclassified as pickups; excluding them later is exactly what made 0.15.42
            // unable to recover the opened sheet despite the destinations being plainly visible.
            woltVisibleBasePickupAddresses = visibleParsed.pickupAddresses
        }

        // On current Wolt Compose builds the opened sheet can be a separate semantics surface whose
        // header is not exposed consistently, even though the destination rows themselves are. Once
        // we have clicked the multiple-dropoff row, exact visible street candidates are sufficient
        // proof that we are looking at that sheet; do not wait for OCR merely to rediscover its title.
        val baseParsed = OfferParser.parse(woltCardFrameText)
        val knownDropoffs = (baseParsed.dropoffAddresses + visibleParsed.dropoffAddresses).distinct()
        val expandedRecovery = WoltAccessibilityDropoffRecovery.recover(
            hiddenTextPieces = strictlyVisiblePieces,
            excludedAddresses = woltVisibleBasePickupAddresses.ifEmpty {
                // Compatibility fallback for an older flow that reaches the opened sheet before a
                // collapsed strict-visible snapshot was recorded. Never exclude parsed drop-offs:
                // the opened sheet legitimately contains those customer addresses again.
                baseParsed.pickupAddresses
            },
            expectedCount = expectedDropoffs,
            knownAddresses = knownDropoffs,
        )
        val doneVisible = hasVisibleAccessibilityText(root) { value -> value.equals("done", ignoreCase = true) }
        val currentIsExpanded = WoltOfferUiText.hasExpandedMultipleDropoffSheet(strictlyVisibleText) ||
            doneVisible ||
            (woltDropoffProbeAttempts > 0 && !collapsedDisclosureVisible && expandedRecovery.candidateCount > 0)
        if (currentIsExpanded) {
            // Do not depend on Wolt keeping the popup labels in a parser-friendly order. Recover the
            // visible popup destinations directly, excluding pickup addresses remembered from the
            // collapsed base card.
            if (expandedRecovery.resolvedAddresses.size == expectedDropoffs) {
                woltDropoffFrameText = WoltAccessibilityDropoffRecovery.expandedFrame(
                    expandedRecovery.resolvedAddresses,
                    expectedDropoffs,
                )
                publishRecoveredWoltBatchRoute(pending, expectedDropoffs)
                woltDropoffSheetSettleAttempts = 0
                val closedRecovered = clickAccessibilityText(root) { value -> value.equals("done", ignoreCase = true) } ||
                    performGlobalAction(GLOBAL_ACTION_BACK)
                CaptureEventLog.append(
                    this,
                    stage = "wolt_dropoffs_expanded_accessibility",
                    platform = "Wolt",
                    message = "Recovered $expectedDropoffs customer stops from the opened Accessibility sheet",
                    dedupeWindowMs = 2_000L,
                )
                if (closedRecovered) {
                    scheduleAttempt(WOLT_DROPOFF_ACCESSIBILITY_SETTLE_MS)
                    return true
                }
            }

            if (parsed.dropoffAddresses.size < expectedDropoffs) {
                woltDropoffSheetSettleAttempts += 1
                if (woltDropoffSheetSettleAttempts < WOLT_DROPOFF_SHEET_MAX_SETTLE_ATTEMPTS) {
                    scheduleAttempt(WOLT_DROPOFF_SHEET_SETTLE_MS)
                    return true
                }
                // One visible expansion is enough. Reopening the sheet a second time proved noisy
                // on the live 0.15.37 trace, so mark the click fallback exhausted before returning
                // to the non-invasive OCR/Accessibility capture path.
                woltDropoffProbeAttempts = WOLT_DROPOFF_PROBE_MAX_ATTEMPTS
                val closedIncomplete = clickAccessibilityText(root) { value -> value.equals("done", ignoreCase = true) } ||
                    performGlobalAction(GLOBAL_ACTION_BACK)
                CaptureEventLog.append(
                    this,
                    stage = "wolt_dropoffs_incomplete",
                    platform = "Wolt",
                    message = "Drop-off sheet stayed incomplete after Accessibility retries; returning to non-click fallback capture",
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
        if (!WoltOfferUiText.hasCollapsedMultipleDropoffs(strictlyVisibleText)) return false

        // Prefer the already-instantiated Accessibility semantics over touching the Wolt UI. The
        // collapsed Compose sheet may keep destination rows alive with isVisibleToUser=false. The
        // ordinary parser cannot know that those rows are destinations, so classify them here using
        // the visibility bit and inject a tiny synthetic expanded-sheet frame for the next pass.
        // Recover the complete customer set, including any customer address that Wolt may also
        // expose on the collapsed card. Only merchant/pickup addresses are exclusions; duplicates
        // are already de-duplicated by WoltAccessibilityDropoffRecovery.
        val excludedVisibleAddresses = (visibleParsed.pickupAddresses + baseParsed.pickupAddresses).distinct()
        val visibleDropoffs = (baseParsed.dropoffAddresses + visibleParsed.dropoffAddresses).distinct()
        val hiddenRecovery = WoltAccessibilityDropoffRecovery.recover(
            hiddenTextPieces = collectHiddenAccessibilityPieces(root),
            excludedAddresses = excludedVisibleAddresses,
            expectedCount = expectedDropoffs,
            knownAddresses = visibleDropoffs,
        )
        // Some Compose versions keep collapsed descendants in the tree but still mark them visible.
        // If the strict hidden-node pass misses, compare the whole semantics tree against the
        // addresses from the actually visible base card. Resolve only on an exact count match.
        val treeRecovery = if (hiddenRecovery.resolvedAddresses.isEmpty()) {
            WoltAccessibilityDropoffRecovery.recover(
                hiddenTextPieces = collectAccessibilityPieces(root, visibleFilter = null),
                excludedAddresses = excludedVisibleAddresses,
                expectedCount = expectedDropoffs,
                knownAddresses = visibleDropoffs,
            )
        } else hiddenRecovery
        if (treeRecovery.resolvedAddresses.size == expectedDropoffs) {
            woltDropoffFrameText = WoltAccessibilityDropoffRecovery.expandedFrame(
                treeRecovery.resolvedAddresses,
                expectedDropoffs,
            )
            publishRecoveredWoltBatchRoute(pending, expectedDropoffs)
            woltDropoffSheetSettleAttempts = 0
            CaptureEventLog.append(
                this,
                stage = "wolt_dropoffs_accessibility",
                platform = "Wolt",
                message = "Recovered $expectedDropoffs customer stops from the collapsed Accessibility tree without opening the drop-off sheet",
                dedupeWindowMs = 2_000L,
            )
            scheduleAttempt(WOLT_DROPOFF_ACCESSIBILITY_SETTLE_MS)
            return true
        }
        val candidateCount = maxOf(hiddenRecovery.candidateCount, treeRecovery.candidateCount)
        if (candidateCount > 0) {
            CaptureEventLog.append(
                this,
                stage = "wolt_dropoffs_accessibility_incomplete",
                platform = "Wolt",
                message = "Accessibility address candidates=$candidateCount; expected=$expectedDropoffs; using one click fallback",
                dedupeWindowMs = 2_000L,
            )
        }

        if (woltDropoffProbeKey != key) {
            woltDropoffProbeKey = key
            woltDropoffProbeAttempts = 0
            woltDropoffSemanticProbeAttempts = 0
        }

        // Compose can publish hidden destination semantics a few frames after the collapsed card.
        // Give Accessibility a very short, non-invasive settle window before touching Wolt UI.
        // This costs at most ~210 ms and often avoids opening the disclosure at all.
        if (woltDropoffSemanticProbeAttempts < WOLT_DROPOFF_SEMANTIC_PROBE_MAX_ATTEMPTS) {
            woltDropoffSemanticProbeAttempts += 1
            CaptureEventLog.append(
                this,
                stage = "wolt_dropoffs_semantic_wait",
                platform = "Wolt",
                message = "Waiting briefly for hidden Accessibility destinations before click fallback; " +
                    "probe=$woltDropoffSemanticProbeAttempts/$WOLT_DROPOFF_SEMANTIC_PROBE_MAX_ATTEMPTS; " +
                    "candidates=$candidateCount; expected=$expectedDropoffs",
                dedupeWindowMs = 500L,
            )
            scheduleAttempt(WOLT_DROPOFF_SEMANTIC_RETRY_MS)
            return true
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

    /**
     * Hidden Wolt customer rows are enough to start the real route. Feed the reconstructed route
     * back to the live advisor immediately instead of waiting for the popup to close, another
     * capture pass, or OCR.
     */
    private fun publishRecoveredWoltBatchRoute(pending: PendingOffer, expectedDropoffs: Int): Boolean {
        val mergedText = listOf(woltCardFrameText, woltDropoffFrameText)
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString("\n")
        if (mergedText.isBlank()) return false

        val recovered = OfferParser.parse(mergedText)
        if (recovered.dropoffAddresses.size < expectedDropoffs) return false
        if (AutomaticWoltRouteCoordinator.routeFingerprint(recovered) == null) return false

        val key = "${pending.packageName}|${pending.armedAt}|${pending.notificationKey}"
        woltDropoffResolvedKey = key
        woltDropoffResolvedCount = maxOf(woltDropoffResolvedCount, expectedDropoffs)
        woltDropoffProbeKey = key
        woltDropoffProbeAttempts = WOLT_DROPOFF_PROBE_MAX_ATTEMPTS
        OfferState.saveUiText(this, mergedText)
        enrichPersistedWoltRouteIfPresent(pending, recovered, mergedText)
        LiveAdvisorHub.showPendingOffer(this, pending, recovered)
        CaptureEventLog.append(
            this,
            stage = "wolt_route_ready_from_accessibility",
            platform = "Wolt",
            message = "Started real route immediately after hidden-stop recovery; " +
                "pickups=${recovered.pickupAddresses.size}; dropoffs=${recovered.dropoffAddresses.size}; " +
                "deliveries=${recovered.deliveryCount ?: expectedDropoffs}",
            dedupeWindowMs = 1_000L,
        )
        return true
    }

    private fun enrichPersistedWoltRouteIfPresent(
        pending: PendingOffer,
        recovered: ParsedOffer,
        mergedText: String,
    ) {
        val money = recovered.money ?: return
        val priceCents = recovered.priceCents ?: return
        val candidate = OfferRecord(
            capturedAt = pending.armedAt,
            platform = OfferParser.platformName(pending.packageName, pending.sourceName),
            packageName = pending.packageName,
            priceCents = priceCents,
            currencyCode = money.currencyCode,
            currencyFractionDigits = money.fractionDigits,
            distanceMeters = recovered.distanceMeters,
            restaurant = recovered.restaurant,
            screenshotUri = "",
            screenshotFilename = "",
            rawText = mergedText,
            merchantNames = recovered.merchantNames,
            pickupAddresses = recovered.pickupAddresses,
            customerNames = recovered.customerNames,
            dropoffAddresses = recovered.dropoffAddresses,
            deliveryCount = recovered.deliveryCount,
            estimatedMinutesMin = recovered.estimatedMinutesMin,
            estimatedMinutesMax = recovered.estimatedMinutesMax,
            captureKey = pending.notificationKey,
        )
        val enrichedId = OfferDatabase.get(this).enrichRecentWoltDuplicateRoute(candidate, recovered, mergedText)
            ?: return
        CaptureEventLog.append(
            this,
            stage = "history_route_enriched",
            platform = "Wolt",
            message = "Upgraded persisted record #$enrichedId with recovered customer stops",
            dedupeWindowMs = 1_000L,
        )
    }

    /**
     * Accumulated route text must never resurrect a finished offer over Wolt's home map. Ignore a
     * single transient home frame, then clear the pending transaction after a second stable check.
     */
    private fun handleWoltIdleHomeSurface(
        pending: PendingOffer,
        packageName: String,
        currentText: String,
    ): Boolean {
        if (packageName != CourierSignals.WOLT_PACKAGE || pending.packageName != CourierSignals.WOLT_PACKAGE) {
            return false
        }
        val key = "${pending.packageName}|${pending.armedAt}|${pending.notificationKey}"
        if (CourierSignals.looksLikeWoltDeclineConfirmation(packageName, currentText)) {
            // The confirmation sheet belongs to the current offer. Wolt leaves the map/home
            // semantics behind it, so never interpret this surface as an ended transaction.
            woltIdleHomeKey = ""
            woltIdleHomeFirstSeenAtElapsed = 0L
            woltIdleHomeChecks = 0
            CaptureEventLog.append(
                this,
                stage = "wolt_decline_modal",
                platform = "Wolt",
                message = "Decline confirmation is open; preserving current offer and route state",
                dedupeWindowMs = 2_000L,
            )
            scheduleAttempt(WOLT_IDLE_HOME_RECHECK_MS)
            return true
        }
        val currentParsed = OfferParser.parse(currentText)
        val isIdleHome = CourierSignals.looksLikeIdleHomeScreen(packageName, currentText) &&
            !CourierSignals.looksLikeOfferScreen(currentText, currentParsed)
        if (!isIdleHome) {
            if (woltIdleHomeKey == key) {
                woltIdleHomeKey = ""
                woltIdleHomeFirstSeenAtElapsed = 0L
                woltIdleHomeChecks = 0
            }
            return false
        }

        val now = SystemClock.elapsedRealtime()
        if (woltIdleHomeKey != key) {
            woltIdleHomeKey = key
            woltIdleHomeFirstSeenAtElapsed = now
            woltIdleHomeChecks = 1
        } else {
            woltIdleHomeChecks += 1
        }

        val stable = woltIdleHomeChecks >= WOLT_IDLE_HOME_END_MIN_CHECKS &&
            now - woltIdleHomeFirstSeenAtElapsed >= WOLT_IDLE_HOME_END_GRACE_MS
        if (!stable) {
            scheduleAttempt(WOLT_IDLE_HOME_RECHECK_MS)
            return true
        }

        CaptureEventLog.append(
            this,
            stage = "wolt_home_end_confirmed",
            platform = "Wolt",
            message = "Visible Wolt home screen ended pending capture; stale accumulated offer will not be rendered again",
            dedupeWindowMs = 1_000L,
        )
        LiveAdvisorHub.clearUserDismissal(
            this,
            CourierSignals.WOLT_PACKAGE,
            "Confirmed Wolt home screen ended the manually dismissed offer",
        )
        OfferState.clear(this)
        woltIdleHomeKey = ""
        woltIdleHomeFirstSeenAtElapsed = 0L
        woltIdleHomeChecks = 0
        scheduleAttempt(IDLE_WATCHDOG_MS)
        return true
    }

    private fun hasVisibleAccessibilityText(
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
            if (node.isVisibleToUser && values.any(matches)) return true
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return false
    }

    private fun hasVisibleClickableAccessibilityText(
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
                var candidate: AccessibilityNodeInfo? = node
                repeat(6) {
                    val current = candidate ?: return@repeat
                    if (current.isVisibleToUser && current.isClickable && current.isEnabled) return true
                    candidate = current.parent
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
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
        // A real Wolt navigation page is stronger evidence than stale offer semantics left behind
        // by Compose. Never re-arm an old offer over Stats/History/Settings-like screens.
        if (CourierSignals.looksLikeWoltNonOfferNavigationScreen(packageName, text)) return false
        if (isAcceptedTaskWithoutOfferControls(text)) {
            LiveAdvisorHub.onActiveTaskSurface(this, packageName)
            return false
        }
        if (!CourierSignals.looksLikeOfferScreen(text, parsed)) return false
        if (LiveAdvisorHub.isUserDismissedOffer(packageName, parsed)) {
            CaptureEventLog.append(
                this,
                stage = "overlay_user_dismiss_screen_rearm_blocked",
                platform = OfferState.platformLabel(packageName),
                message = "Same visible offer was manually dismissed; skipped screen re-arm",
                dedupeWindowMs = 2_000L,
            )
            return false
        }
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

        // If one bad Accessibility frame destroyed the advisor, the normal screen deduper would
        // intentionally block this exact fingerprint for ten minutes. Before applying that tombstone,
        // compare the still-visible card against recent persisted history. A strict price + distance +
        // merchant match means this is the same transaction, so restore its saved route immediately.
        if (LiveAdvisorHub.tryRestoreRecentOffer(this, packageName, parsed)) {
            CaptureEventLog.append(
                this,
                stage = "screen_history_restore",
                platform = OfferState.platformLabel(packageName),
                message = "Same recent offer is still visible; restored advisor from history instead of re-arming",
                dedupeWindowMs = 2_000L,
            )
            return false
        }

        val fingerprint = CourierSignals.offerFingerprint(packageName, text)
        if (!ScreenOfferDeduper.shouldArm(this, packageName, fingerprint)) return false

        val result = OfferState.arm(this, packageName, resolveAppName(packageName), "screen:$fingerprint")
        val armed = result == ArmResult.ARMED || result == ArmResult.REPLACED_SAME_PLATFORM
        if (LiveOfferTransactionPolicy.startsNewCapture(result)) {
            OfferState.pending(this)?.let { pending -> LiveAdvisorHub.hideForCapture(this, pending) }
        }
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
                    if (deferScreenshotProcessingForOverlayDrag(screenshot, captureToken, platform)) return
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
                    if (deferScreenshotProcessingForOverlayDrag(screenshot, captureToken, platform)) return
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

                            // Publish the OCR-enriched base card immediately. Batch-route recovery
                            // may briefly open Wolt's hidden drop-off sheet; the advisor must not wait
                            // for that UI round-trip before it can show merchant/pickup information.
                            if (CourierSignals.looksLikeOfferScreen(combined, parsed) &&
                                (parsed.priceCents == null || trustedPrice)
                            ) {
                                LiveAdvisorHub.showPendingOffer(this@OfferAccessibilityService, pending, parsed)
                            }

                            val latestRoot = findCourierWindow(pending)?.root
                            if (latestRoot != null &&
                                maybeResolveWoltHiddenDropoffs(latestRoot, pending, parsed)
                            ) {
                                finishCapture(captureToken)
                                if (!stashWoltProofBitmap(pending, bitmap)) bitmap.recycle()
                                return@addOnSuccessListener
                            }
                            CaptureEventLog.append(
                                this@OfferAccessibilityService,
                                stage = "ocr_price_probe",
                                platform = platform,
                                message = "capture_ms=${(ocrStartedAt - probeStartedAt).coerceAtLeast(0L)}; " +
                                    "ocr_ms=${(SystemClock.elapsedRealtime() - ocrStartedAt).coerceAtLeast(0L)}; " +
                                    "spatial=${spatialWoltMoney != null}; price=${parsed.priceCents != null}; trusted=$trustedPrice; " +
                                    "merchants=${parsed.merchantNames.size}; pickups=${parsed.pickupAddresses.size}; dropoffs=${parsed.dropoffAddresses.size}",
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
                            val routeStillIncomplete = pending.packageName == CourierSignals.WOLT_PACKAGE &&
                                LiveAdvisorSettings.automaticWoltRouting(this@OfferAccessibilityService) &&
                                AutomaticWoltRouteCoordinator.routeFingerprint(parsed) == null
                            if (routeStillIncomplete &&
                                woltRouteOcrRecoveryAttempts < WOLT_ROUTE_OCR_RECOVERY_RETRIES
                            ) {
                                woltRouteOcrRecoveryAttempts += 1
                                CaptureEventLog.append(
                                    this@OfferAccessibilityService,
                                    stage = "route_text_ocr_retry",
                                    platform = platform,
                                    message = "OCR still lacks a complete Wolt route; retry=${woltRouteOcrRecoveryAttempts}; " +
                                        "pickups=${parsed.pickupAddresses.size}; dropoffs=${parsed.dropoffAddresses.size}; " +
                                        "deliveries=${parsed.deliveryCount ?: 0}",
                                    dedupeWindowMs = 500L,
                                )
                                finishCapture(captureToken)
                                if (!stashWoltProofBitmap(pending, bitmap)) bitmap.recycle()
                                scheduleAttempt(WOLT_ROUTE_OCR_RECOVERY_DELAY_MS)
                                return@addOnSuccessListener
                            }
                            if (!routeStillIncomplete) woltRouteOcrRecoveryAttempts = 0
                            finishCapture(captureToken)
                            if (parsed.priceCents != null && trustedPrice) {
                                CaptureEventLog.append(this@OfferAccessibilityService, "price_ocr", "Price detected by OCR fallback", platform)
                                val frozenProof = takeWoltProofBitmap(pending)
                                if (frozenProof != null) {
                                    bitmap.recycle()
                                    persistOffer(frozenProof, pending, combined, parsed)
                                } else {
                                    persistOffer(bitmap, pending, combined, parsed)
                                }
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
            preferDisplay = pending.packageName == CourierSignals.WOLT_PACKAGE,
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
                    if (deferScreenshotProcessingForOverlayDrag(screenshot, captureToken, platform)) return
                    if (pending.packageName == CourierSignals.WOLT_PACKAGE && !isVisibleWoltOfferSurface(pending)) {
                        discardScreenshot(screenshot)
                        finishCapture(captureToken)
                        CaptureEventLog.append(
                            this@OfferAccessibilityService,
                            stage = "screenshot_stale_offer_discarded",
                            platform = platform,
                            message = "Discarded screenshot because the Wolt offer surface had already disappeared",
                            dedupeWindowMs = 2_000L,
                        )
                        persistOffer(null, pending, text, parsed)
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
            preferDisplay = pending.packageName == CourierSignals.WOLT_PACKAGE,
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

    private fun deferScreenshotProcessingForOverlayDrag(
        screenshot: ScreenshotResult,
        captureToken: Long,
        platform: String,
    ): Boolean {
        if (!LiveAdvisorHub.isOverlayGestureActive()) return false
        discardScreenshot(screenshot)
        finishCapture(captureToken)
        CaptureEventLog.append(
            this,
            stage = "capture_deferred_drag",
            platform = platform,
            message = "Deferred screenshot bitmap/OCR work until the live card drag ends",
            dedupeWindowMs = 2_000L,
        )
        scheduleAttempt(OVERLAY_DRAG_CAPTURE_DEFER_MS)
        return true
    }

    private fun discardScreenshot(screenshot: ScreenshotResult) {
        runCatching { screenshot.hardwareBuffer.close() }
    }

    private fun takeTargetScreenshot(
        windowId: Int,
        callback: TakeScreenshotCallback,
        preferDisplay: Boolean = false,
    ) {
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

        // On the real Android 16/ColorOS courier device takeScreenshotOfWindow can sit for several
        // seconds and fail only after Wolt has already replaced the offer. A display capture returns
        // much sooner and the overlay is suppressed around it, so use that direct path for
        // time-critical Wolt OCR/proof frames.
        if (preferDisplay) {
            requestDisplayScreenshot(callback, fallback = false)
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
                handler.postDelayed({ requestDisplayScreenshot(callback, fallback = true) }, delay)
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
                    { requestDisplayScreenshot(callback, fallback = true) },
                    DISPLAY_SCREENSHOT_FALLBACK_DELAY_MS,
                )
            }
    }

    private fun requestDisplayScreenshot(callback: TakeScreenshotCallback, fallback: Boolean) {
        LiveAdvisorHub.setCaptureSuppressed(this, true)
        val displayCallback = object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                LiveAdvisorHub.setCaptureSuppressed(this@OfferAccessibilityService, false)
                CaptureEventLog.append(
                    this@OfferAccessibilityService,
                    if (fallback) "screenshot_display_fallback_ok" else "screenshot_display_direct_ok",
                    if (fallback) "Display screenshot fallback succeeded" else "Direct display screenshot succeeded",
                    dedupeWindowMs = 3_000L,
                )
                callback.onSuccess(screenshot)
            }

            override fun onFailure(displayErrorCode: Int) {
                LiveAdvisorHub.setCaptureSuppressed(this@OfferAccessibilityService, false)
                CaptureEventLog.append(
                    this@OfferAccessibilityService,
                    if (fallback) "screenshot_display_fallback_failed" else "screenshot_display_direct_failed",
                    if (fallback) {
                        "Display screenshot fallback failed with Android error $displayErrorCode"
                    } else {
                        "Direct display screenshot failed with Android error $displayErrorCode"
                    },
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

    private fun isVisibleWoltOfferSurface(pending: PendingOffer): Boolean {
        if (pending.packageName != CourierSignals.WOLT_PACKAGE) return true
        val target = findCourierWindow(pending) ?: return false
        val text = collectStrictlyVisibleText(target.root)
        if (text.isBlank()) return false
        return CourierSignals.looksLikeOfferScreen(text, OfferParser.parse(text))
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
            // Persistence dedupe used to stop here, which meant the database was correct but the live
            // overlay stayed gone. Reattach the duplicate row and its saved route before clearing the
            // capture transaction. This is the durable second line of defence behind screen recovery.
            LiveAdvisorHub.restoreDuplicateOffer(this, duplicate, parsed)
            bitmap?.recycle()
            captureInFlight = false
            CaptureEventLog.append(
                this,
                "duplicate_suppressed",
                "Same live offer already exists as record #${duplicate.id}; restored advisor and skipped history insert",
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
                database.findById(insertResult.rowId)?.let { existing ->
                    LiveAdvisorHub.restoreDuplicateOffer(this, existing, parsed)
                }
                CaptureEventLog.append(
                    this,
                    "duplicate_race_suppressed",
                    "Duplicate reached persistence guard; restored record #${insertResult.rowId}",
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

    private fun proofKeyFor(pending: PendingOffer): String =
        "${pending.packageName}|${pending.armedAt}|${pending.notificationKey}"

    private fun stashWoltProofBitmap(pending: PendingOffer, bitmap: Bitmap): Boolean {
        if (pending.packageName != CourierSignals.WOLT_PACKAGE) return false
        val key = proofKeyFor(pending)
        if (woltProofOfferKey.isNotBlank() && woltProofOfferKey != key) clearWoltProofBitmap()
        if (woltProofBitmap != null) return false
        woltProofOfferKey = key
        woltProofBitmap = bitmap
        CaptureEventLog.append(
            this,
            stage = "wolt_frozen_proof_saved",
            platform = "Wolt",
            message = "Frozen the first priced Wolt card before multiple-dropoff recovery",
            dedupeWindowMs = 2_000L,
        )
        return true
    }

    private fun takeWoltProofBitmap(pending: PendingOffer): Bitmap? {
        if (pending.packageName != CourierSignals.WOLT_PACKAGE) return null
        val key = proofKeyFor(pending)
        if (woltProofOfferKey != key) {
            if (woltProofOfferKey.isNotBlank()) clearWoltProofBitmap()
            return null
        }
        val bitmap = woltProofBitmap
        woltProofBitmap = null
        woltProofOfferKey = ""
        return bitmap
    }

    private fun discardWoltProofBitmap(pending: PendingOffer) {
        if (woltProofOfferKey == proofKeyFor(pending)) clearWoltProofBitmap()
    }

    private fun clearWoltProofBitmap() {
        woltProofBitmap?.let { if (!it.isRecycled) it.recycle() }
        woltProofBitmap = null
        woltProofOfferKey = ""
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

    private fun terminatePendingOfferOnActiveTask(packageName: String, text: String): Boolean {
        if (!isAcceptedTaskWithoutOfferControls(text)) return false
        val pending = OfferState.pending(this)
        if (pending != null && pending.packageName == packageName) {
            captureGuard.cancel()
            captureInFlight = false
            handler.removeCallbacks(captureWatchdogRunnable)
            handler.removeCallbacks(woltPricePollRunnable)
            woltPricePollKey = ""
            lastFastAccessibilityPriceKey = ""
            OfferState.clear(this)
            lastHandledArmedAt = 0L
            CaptureEventLog.append(
                this,
                stage = "active_task_pending_cleared",
                platform = OfferState.platformLabel(packageName),
                message = "Accepted task surface cleared pending capture and invalidated late callbacks",
                dedupeWindowMs = 2_000L,
            )
        }
        LiveAdvisorHub.onActiveTaskSurface(this, packageName)
        return true
    }

    private fun isAcceptedTaskWithoutOfferControls(text: String): Boolean =
        DeliveryLifecycleTracking.isAcceptedTaskWithoutOfferControls(text)

    private fun scheduleAttempt(delayMs: Long) {
        handler.removeCallbacks(attemptRunnable)
        handler.postDelayed(attemptRunnable, delayMs)
    }

    // Historical name kept for compatibility: this intentionally collects every Accessibility
    // node, including non-visible semantics. Some older Wolt screens depended on that behaviour.
    private fun collectVisibleText(root: AccessibilityNodeInfo): String =
        collectAccessibilityPieces(root, visibleFilter = null).joinToString("\n")

    private fun collectStrictlyVisibleText(root: AccessibilityNodeInfo): String =
        collectStrictlyVisibleAccessibilityPieces(root).joinToString("\n")

    private fun collectStrictlyVisibleAccessibilityPieces(root: AccessibilityNodeInfo): List<String> =
        collectAccessibilityPieces(root, visibleFilter = true)

    private fun collectHiddenAccessibilityPieces(root: AccessibilityNodeInfo): List<String> =
        collectAccessibilityPieces(root, visibleFilter = false)

    private fun collectAccessibilityPieces(
        root: AccessibilityNodeInfo,
        visibleFilter: Boolean?,
    ): List<String> {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val pieces = mutableListOf<String>()
        queue.add(root)
        var visited = 0

        fun addPiece(value: CharSequence?, eligible: Boolean) {
            if (!eligible) return
            val cleaned = value?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return
            if (pieces.lastOrNull() != cleaned) pieces += cleaned
        }

        while (queue.isNotEmpty() && visited < 700) {
            val node = queue.removeFirst()
            visited++
            val eligible = visibleFilter == null || node.isVisibleToUser == visibleFilter
            addPiece(node.text, eligible)
            addPiece(node.contentDescription, eligible)
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return pieces
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
        private const val OVERLAY_DRAG_CAPTURE_DEFER_MS = 120L
        private const val WOLT_FAST_PRICE_POLL_MS = 350L
        private const val WOLT_HOT_PRICE_POLL_MS = 220L
        private const val WOLT_PRICE_EVENT_THROTTLE_MS = 90L
        private const val WOLT_DROPOFF_SHEET_SETTLE_MS = 180L
        private const val WOLT_DROPOFF_ACCESSIBILITY_SETTLE_MS = 40L
        private const val WOLT_DROPOFF_SEMANTIC_RETRY_MS = 70L
        private const val WOLT_DROPOFF_SEMANTIC_PROBE_MAX_ATTEMPTS = 3
        private const val WOLT_DROPOFF_SHEET_MAX_SETTLE_ATTEMPTS = 4
        private const val WOLT_DROPOFF_PROBE_MAX_ATTEMPTS = 1
        private const val WOLT_IDLE_HOME_RECHECK_MS = 180L
        private const val WOLT_IDLE_HOME_END_GRACE_MS = 160L
        private const val WOLT_IDLE_HOME_END_MIN_CHECKS = 2
        private const val WOLT_ROUTE_OCR_RECOVERY_DELAY_MS = 220L
        private const val WOLT_ROUTE_OCR_RECOVERY_RETRIES = 2
        private const val DISCOVERY_EVENT_WINDOW_MS = 1_500L
        private const val DISCOVERY_OCR_MIN_INTERVAL_MS = 1_800L
        private const val DISCOVERY_SCREENSHOT_RETRY_MS = 1_200L
        private const val DISPLAY_SCREENSHOT_FALLBACK_DELAY_MS = 120L
        private const val DISPLAY_SCREENSHOT_RATE_LIMIT_RETRY_MS = 750L
        private const val OPTIONAL_SCREENSHOT_FAILURE_LIMIT = 3
        private const val CAPTURE_OPERATION_TIMEOUT_MS = 8_000L
    }
}
