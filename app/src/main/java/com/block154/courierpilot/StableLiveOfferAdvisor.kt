package com.block154.courierpilot

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.abs

/**
 * Stable live card: the shell appears first with profitability data, then Valhalla updates the same
 * card in place. Route work never owns card lifetime and a route callback cannot create a new card.
 */
internal class StableLiveOfferAdvisor(
    private val service: AccessibilityService,
    private val onRouteToggleChanged: ((platform: String, enabled: Boolean) -> Unit)? = null,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop

    private var root: LinearLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var decisionText: TextView? = null
    private var routeText: TextView? = null
    private var routeToggle: TextView? = null
    private var voiceToggle: TextView? = null

    private var currentPlatform = ""
    private var currentParsed: ParsedOffer? = null
    private var expectedPackageName = ""
    private var dismissed = false
    private var temporarilyHidden = false
    private var generation = 0L
    private var missingSince = 0L
    private var missingChecks = 0
    private var boltBaselineSurface: LiveOfferSurfaceSnapshot? = null
    private var previewMode = false
    private var captureSuppressed = false
    private var offerVisualStartedAtElapsed = 0L

    private var cachedDecisionLine = ""
    private var cachedDecisionBand = OfferDecisionBand.UNKNOWN
    private var cachedRouteLine = ""
    private var cachedRouteVisible = true

    private var gestureDownX = 0f
    private var gestureDownY = 0f
    private var gestureStartY = 0
    private var gestureMode = GESTURE_NONE

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null

    private val visibilityWatchdog = object : Runnable {
        override fun run() {
            if (dismissed || currentParsed == null) return
            checkOfferStillVisible()
            if (!dismissed && currentParsed != null) {
                handler.postDelayed(this, if (temporarilyHidden) HIDDEN_VISIBILITY_CHECK_MS else VISIBILITY_CHECK_MS)
            }
        }
    }

    /**
     * Show the advisor as soon as CourierPilot has a verified offer surface, even while the courier
     * app is still loading the price. Later price/persistence/route updates mutate this same view.
     */
    fun showPending(platform: String, parsed: ParsedOffer) {
        if (!LiveAdvisorSettings.enabled(service)) return
        val packageName = packageForPlatform(platform)
        val sameSurface = !dismissed && currentParsed != null && expectedPackageName == packageName
        val createdSurface = !sameSurface
        if (!sameSurface) {
            generation += 1
            offerVisualStartedAtElapsed = SystemClock.elapsedRealtime()
            currentPlatform = platform
            expectedPackageName = packageName
            dismissed = false
            temporarilyHidden = false
            cachedDecisionLine = ""
            cachedDecisionBand = OfferDecisionBand.UNKNOWN
            cachedRouteLine = ""
            cachedRouteVisible = true
            resetMissingEvidence()
            boltBaselineSurface = if (platform.equals("Bolt", ignoreCase = true)) {
                findVisiblePackageRoot(packageName)?.let { inspectVisibleSurface(it).snapshot }
            } else null
        }
        previewMode = true
        currentParsed = parsed
        if (parsed.priceCents == null) {
            cachedDecisionLine = "⏳  Waiting for price…"
            cachedDecisionBand = OfferDecisionBand.UNKNOWN
            decisionText?.apply {
                text = cachedDecisionLine
                setTextColor(decisionColor(cachedDecisionBand))
            }
        } else {
            renderProfitability(parsed, pedestrianRoute = null, cyclewayRoute = null)
        }
        if (cachedRouteLine.isBlank() || cachedRouteLine.startsWith("⏳") || cachedRouteLine.startsWith("⚡")) {
            setRouteContent(if (LiveAdvisorSettings.routeEnabled(service, platform)) "⚡ Valhalla · preparing…" else "Route off")
        }
        if (!temporarilyHidden) {
            ensureView()
            refreshControls()
            applyCachedPresentation()
            if (createdSurface && root != null) {
                CaptureEventLog.append(
                    service,
                    stage = "overlay_preview",
                    platform = platform,
                    message = "Progressive advisor shown before final price persistence",
                )
            }
        }
        startVisibilityWatchdog()
    }

    fun showBase(platform: String, parsed: ParsedOffer) {
        if (!LiveAdvisorSettings.enabled(service)) {
            suppressCurrentOffer("advisor disabled")
            return
        }

        val packageName = packageForPlatform(platform)
        if (!dismissed && currentParsed != null && expectedPackageName == packageName && previewMode) {
            currentPlatform = platform
            currentParsed = parsed
            previewMode = false
            renderProfitability(parsed, pedestrianRoute = null, cyclewayRoute = null)
            if (cachedRouteLine.isBlank() || cachedRouteLine.startsWith("⚡")) renderRouteLoadingState()
            if (!temporarilyHidden) {
                ensureView()
                refreshControls()
                applyCachedPresentation()
            }
            CaptureEventLog.append(
                service,
                stage = "overlay_promote",
                platform = platform,
                message = "Pending advisor promoted in place after price capture",
                dedupeWindowMs = 1_000L,
            )
            if (LiveAdvisorSettings.voiceEnabled(service)) speak(baseSpeech(platform, parsed))
            startVisibilityWatchdog()
            return
        }

        generation += 1
        offerVisualStartedAtElapsed = SystemClock.elapsedRealtime()
        val expectedGeneration = generation
        currentPlatform = platform
        currentParsed = parsed
        expectedPackageName = packageForPlatform(platform)
        dismissed = false
        temporarilyHidden = false
        previewMode = false
        cachedDecisionLine = ""
        cachedDecisionBand = OfferDecisionBand.UNKNOWN
        cachedRouteLine = ""
        cachedRouteVisible = true
        resetMissingEvidence()
        boltBaselineSurface = if (platform.equals("Bolt", ignoreCase = true)) {
            findVisiblePackageRoot(expectedPackageName)?.let { inspectVisibleSurface(it).snapshot }
        } else {
            null
        }

        handler.post {
            if (dismissed || expectedGeneration != generation) return@post
            // Cache presentation first. If the courier app was already backgrounded, keep the
            // offer warm without recreating an overlay on top of another app.
            renderProfitability(parsed, pedestrianRoute = null, cyclewayRoute = null)
            renderRouteLoadingState()
            if (!temporarilyHidden) {
                ensureView()
                if (root != null) {
                    refreshControls()
                    applyCachedPresentation()
                    CaptureEventLog.append(
                        service,
                        stage = "overlay_show",
                        platform = platform,
                        message = "Stable advisor shell shown before route result",
                    )
                    if (LiveAdvisorSettings.voiceEnabled(service)) speak(baseSpeech(platform, parsed))
                }
            }
            startVisibilityWatchdog()
        }
    }

    fun isTrackingOffer(packageName: String): Boolean =
        !dismissed && currentParsed != null && expectedPackageName == packageName

    fun updateRoute(comparison: RouteComparison, waypointCount: Int) {
        if (dismissed || !LiveAdvisorSettings.enabled(service)) return
        handler.post {
            if (dismissed) return@post
            val walking = comparison.pedestrian.getOrNull()
            val cycling = comparison.cycleway.getOrNull()
            currentParsed?.takeIf { it.priceCents != null }?.let { parsed -> renderProfitability(parsed, walking, cycling) }
            setRouteContent(LiveAdvisorPresentation.routeLine(walking, cycling))
            CaptureEventLog.append(
                service,
                stage = "route_ready",
                platform = currentPlatform,
                message = "Valhalla updated cached card; points=$waypointCount; visible=${root != null}; card_age_ms=${(SystemClock.elapsedRealtime() - offerVisualStartedAtElapsed).coerceAtLeast(0L)}",
            )
        }
    }

    fun updateBoltRoute(outcome: AutomaticBoltRouteOutcome) {
        if (dismissed || !LiveAdvisorSettings.enabled(service)) return
        handler.post {
            if (dismissed) return@post
            val comparison = outcome.comparison
            if (comparison == null) {
                setRouteContent("⚠️ Valhalla · unavailable")
                CaptureEventLog.append(
                    service,
                    stage = "bolt_route_failed",
                    platform = "Bolt",
                    message = outcome.failureReason ?: "unknown route failure",
                )
                return@post
            }

            val walking = comparison.pedestrian.getOrNull()
            val cycling = comparison.cycleway.getOrNull()
            // Pickup-only Bolt routing is incomplete, so it must not distort whole-order €/km/score.
            if (outcome.scope == BoltRouteScope.FULL) {
                currentParsed?.let { parsed -> renderProfitability(parsed, walking, cycling) }
            }
            val routes = LiveAdvisorPresentation.routeLine(walking, cycling)
            setRouteContent(
                if (outcome.scope == BoltRouteScope.PICKUP_ONLY) "Pickup · $routes" else routes,
            )
            CaptureEventLog.append(
                service,
                stage = "bolt_route_ready",
                platform = "Bolt",
                message = "Updated existing card; scope=${outcome.scope}; waypoints=${outcome.waypoints.size}",
            )
        }
    }

    fun updateRouteUnavailable(reason: String) {
        if (dismissed || !LiveAdvisorSettings.enabled(service)) return
        handler.post {
            if (dismissed) return@post
            setRouteContent("⚠️ Valhalla · unavailable")
            CaptureEventLog.append(service, "route_failed", reason, currentPlatform)
        }
    }

    /** Accessibility events make resume nearly immediate; the watchdog remains the fallback. */
    fun onCourierWindowEvent(packageName: String) {
        if (dismissed || currentParsed == null || packageName != expectedPackageName) return
        handler.post {
            if (!dismissed && currentParsed != null) checkOfferStillVisible()
        }
    }

    /** A real foreign window-state event is stronger than rootInActiveWindow on overlay-heavy OEMs. */
    fun onForegroundWindowChanged(packageName: String) {
        if (dismissed || currentParsed == null || packageName.isBlank()) return
        when (packageName) {
            expectedPackageName -> onCourierWindowEvent(packageName)
            service.packageName, SYSTEM_UI_PACKAGE -> Unit
            else -> handler.post {
                if (!dismissed && currentParsed != null) {
                    temporarilyHide("foreground window changed to $packageName")
                }
            }
        }
    }

    fun suppressCurrentOffer(reason: String = "superseded", animate: Boolean = true) {
        if (!dismissed || root != null) {
            CaptureEventLog.append(
                service,
                stage = "overlay_hide",
                platform = currentPlatform,
                message = reason,
                dedupeWindowMs = 500L,
            )
        }
        dismissed = true
        temporarilyHidden = false
        generation += 1
        clearOfferViewState(animate = animate)
    }

    fun destroy() {
        suppressCurrentOffer("advisor destroyed", animate = false)
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        pendingSpeech = null
    }

    private fun renderProfitability(
        parsed: ParsedOffer,
        pedestrianRoute: RouteResult?,
        cyclewayRoute: RouteResult?,
    ) {
        val decision = OfferDecisionEngine.evaluate(
            parsed,
            pedestrianRoute,
            cyclewayRoute,
            thresholds = MarketIntelligence.thresholdsFor(service, currentPlatform, parsed.money?.currencyCode ?: "EUR"),
        )
        val platformEconomics = PlatformOfferEconomicsCalculator.calculate(parsed)
        cachedDecisionLine = LiveAdvisorPresentation.profitabilityLine(decision, platformEconomics)
        cachedDecisionBand = decision.band
        decisionText?.apply {
            text = cachedDecisionLine
            setTextColor(decisionColor(cachedDecisionBand))
        }
    }

    private fun renderRouteLoadingState() {
        val enabled = LiveAdvisorSettings.routeEnabled(service, currentPlatform)
        setRouteContent(if (enabled) "⏳ Valhalla · calculating…" else "Route off")
    }

    private fun setRouteContent(text: String, visible: Boolean = true) {
        cachedRouteLine = text
        cachedRouteVisible = visible
        routeText?.apply {
            visibility = if (visible) View.VISIBLE else View.GONE
            this.text = text
        }
    }

    private fun applyCachedPresentation() {
        decisionText?.apply {
            text = cachedDecisionLine
            setTextColor(decisionColor(cachedDecisionBand))
        }
        routeText?.apply {
            visibility = if (cachedRouteVisible) View.VISIBLE else View.GONE
            text = cachedRouteLine
        }
    }

    private fun ensureView() {
        if (root != null) return

        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(11))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(244, 17, 24, 39))
                setStroke(dp(1), Color.argb(150, 75, 85, 99))
            }
            elevation = dp(10).toFloat()
        }
        installGestureSurface(container)

        val topRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        installGestureSurface(topRow)

        val title = TextView(service).apply {
            text = "CourierPilot ${BuildConfig.VERSION_NAME}"
            setTextColor(Color.rgb(229, 231, 235))
            textSize = 12.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        installGestureSurface(title)
        topRow.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        routeToggle = controlText(dp(7)).apply {
            setOnClickListener {
                if (currentPlatform.isBlank()) return@setOnClickListener
                val enabled = !LiveAdvisorSettings.routeEnabled(service, currentPlatform)
                LiveAdvisorSettings.setRouteEnabled(service, currentPlatform, enabled)
                refreshControls()
                renderRouteLoadingState()
                onRouteToggleChanged?.invoke(currentPlatform, enabled)
            }
        }
        topRow.addView(routeToggle)

        voiceToggle = controlText(dp(6)).apply {
            setOnClickListener {
                val enabled = !LiveAdvisorSettings.voiceEnabled(service)
                LiveAdvisorSettings.setVoiceEnabled(service, enabled)
                if (!enabled) tts?.stop()
                refreshControls()
            }
        }
        topRow.addView(voiceToggle)

        topRow.addView(TextView(service).apply {
            text = "×"
            setTextColor(Color.LTGRAY)
            textSize = 21f
            gravity = Gravity.CENTER
            setPadding(dp(7), 0, 0, 0)
            setOnClickListener { suppressCurrentOffer("closed by user") }
        })
        container.addView(topRow)

        decisionText = TextView(service).apply {
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, dp(6), 0, 0)
        }.also {
            installGestureSurface(it)
            container.addView(it)
        }

        routeText = TextView(service).apply {
            setTextColor(Color.rgb(226, 232, 240))
            textSize = 13.5f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setPadding(0, dp(6), 0, 0)
        }.also {
            installGestureSurface(it)
            container.addView(it)
        }

        val screenWidth = service.resources.displayMetrics.widthPixels
        val params = WindowManager.LayoutParams(
            (screenWidth - dp(HORIZONTAL_MARGIN_DP * 2)).coerceAtLeast(1),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = LiveAdvisorSettings.overlayYPx(service) ?: dp(DEFAULT_Y_DP)
        }
        windowParams = params

        runCatching { windowManager.addView(container, params) }
            .onSuccess {
                root = container
                captureSuppressed = false
                container.alpha = 0f
                container.translationY = -dp(FADE_OFFSET_DP).toFloat()
                container.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setInterpolator(DecelerateInterpolator())
                    .setDuration(FADE_IN_MS)
                    .start()
                container.post {
                    val current = windowParams ?: return@post
                    current.y = clampY(current.y, container)
                    runCatching { windowManager.updateViewLayout(container, current) }
                }
            }
            .onFailure { error ->
                windowParams = null
                CaptureEventLog.append(
                    service,
                    stage = "overlay_add_failed",
                    platform = currentPlatform,
                    message = "${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                )
            }
    }

    private fun detachView(animate: Boolean = true) {
        val view = root
        root = null
        windowParams = null
        decisionText = null
        routeText = null
        routeToggle = null
        voiceToggle = null
        gestureMode = GESTURE_NONE
        captureSuppressed = false
        if (view == null) return
        view.animate().cancel()
        if (!animate || !view.isAttachedToWindow) {
            runCatching { windowManager.removeView(view) }
            return
        }
        view.animate()
            .alpha(0f)
            .translationY(-dp(FADE_OFFSET_DP).toFloat())
            .setInterpolator(AccelerateInterpolator())
            .setDuration(FADE_OUT_MS)
            .withEndAction { runCatching { windowManager.removeView(view) } }
            .start()
    }

    /** Temporarily make the accessibility overlay invisible to display-level screenshots. */
    fun setCaptureSuppressed(suppressed: Boolean) {
        if (captureSuppressed == suppressed) return
        captureSuppressed = suppressed
        val view = root ?: return
        view.animate().cancel()
        if (suppressed) {
            view.alpha = 0f
        } else {
            view.translationY = 0f
            view.alpha = 1f
        }
    }

    private fun temporarilyHide(reason: String) {
        if (dismissed || currentParsed == null) return
        if (!temporarilyHidden || root != null) {
            CaptureEventLog.append(
                service,
                stage = "overlay_suspend",
                platform = currentPlatform,
                message = reason,
                dedupeWindowMs = 750L,
            )
        }
        temporarilyHidden = true
        detachView()
        resetMissingEvidence()
    }

    private fun restoreFromCache(reason: String) {
        if (dismissed || currentParsed == null || !temporarilyHidden) return
        val pending = OfferState.pending(service)
        if (pending != null && pending.packageName == expectedPackageName && !previewMode) return
        ensureView()
        if (root == null) return
        temporarilyHidden = false
        refreshControls()
        applyCachedPresentation()
        resetMissingEvidence()
        CaptureEventLog.append(
            service,
            stage = "overlay_restore",
            platform = currentPlatform,
            message = reason,
            dedupeWindowMs = 500L,
        )
    }

    private fun clearOfferViewState(animate: Boolean = true) {
        handler.removeCallbacks(visibilityWatchdog)
        detachView(animate = animate)
        resetMissingEvidence()
        boltBaselineSurface = null
        previewMode = false
        captureSuppressed = false
        offerVisualStartedAtElapsed = 0L
        currentParsed = null
        expectedPackageName = ""
        cachedDecisionLine = ""
        cachedDecisionBand = OfferDecisionBand.UNKNOWN
        cachedRouteLine = ""
        cachedRouteVisible = true
    }

    private fun refreshControls() {
        routeToggle?.apply {
            visibility = if (currentPlatform.equals("Wolt", true) || currentPlatform.equals("Bolt", true)) View.VISIBLE else View.GONE
            text = "Route ${if (LiveAdvisorSettings.routeEnabled(service, currentPlatform)) "ON" else "OFF"}"
        }
        voiceToggle?.text = if (LiveAdvisorSettings.voiceEnabled(service)) "🔊" else "🔇"
    }

    private fun decisionColor(band: OfferDecisionBand): Int = when (band) {
        OfferDecisionBand.FIRE -> Color.rgb(134, 239, 172)
        OfferDecisionBand.GOOD -> Color.rgb(167, 243, 208)
        OfferDecisionBand.OK -> Color.rgb(253, 224, 71)
        OfferDecisionBand.BAD -> Color.rgb(253, 186, 116)
        OfferDecisionBand.TERRIBLE -> Color.rgb(252, 165, 165)
        OfferDecisionBand.UNKNOWN -> Color.rgb(203, 213, 225)
    }

    private fun startVisibilityWatchdog() {
        handler.removeCallbacks(visibilityWatchdog)
        handler.postDelayed(visibilityWatchdog, VISIBILITY_CHECK_MS)
    }

    private fun checkOfferStillVisible() {
        val expected = expectedPackageName
        val expectedOffer = currentParsed ?: return
        if (expected.isBlank()) return

        val activePackage = service.rootInActiveWindow?.packageName?.toString().orEmpty()
        val courierRoot = findVisiblePackageRoot(expected)
        val definitelyAway = activePackage.isNotBlank() &&
            activePackage != expected &&
            activePackage != service.packageName &&
            activePackage != SYSTEM_UI_PACKAGE

        if (definitelyAway) {
            temporarilyHide("foreground changed to $activePackage")
            return
        }

        if (courierRoot == null) {
            if (registerMissingEvidence()) {
                temporarilyHide("courier window temporarily unavailable; active=$activePackage")
            }
            return
        }

        val inspection = inspectVisibleSurface(courierRoot)
        val visibleText = inspection.text
        val parsed = OfferParser.parse(visibleText)
        val hasOfferUi = CourierSignals.looksLikeOfferScreen(visibleText, parsed) || hasDecisionPair(visibleText)

        // A live offer is stronger evidence than generic background/presence strings rendered on
        // the same Wolt screen. In particular, Wolt can expose "Go offline" while an incoming
        // offer is still fully visible. Never end the card before checking the offer UI itself.
        if (hasOfferUi) {
            if (LiveOfferResumePolicy.definitelyDifferent(expectedOffer, parsed)) {
                suppressCurrentOffer("different offer is now visible")
                return
            }
            resetMissingEvidence()
            if (currentPlatform.equals("Bolt", ignoreCase = true) && boltBaselineSurface == null) {
                boltBaselineSurface = inspection.snapshot
            }
            if (temporarilyHidden) restoreFromCache("same offer returned to foreground")
            return
        }

        // Some Wolt Compose recompositions temporarily drop the Accept/Decline semantics while the
        // price/merchant/address remains visible. A matching identity keeps the card alive.
        if (
            currentPlatform.equals("Wolt", ignoreCase = true) &&
            LiveOfferResumePolicy.hasMatchingIdentity(expectedOffer, parsed)
        ) {
            resetMissingEvidence()
            if (temporarilyHidden) restoreFromCache("same Wolt offer identity returned without controls")
            return
        }

        DeliveryLifecycleTracking.detect(visibleText)?.let {
            suppressCurrentOffer("offer ended: ${it.type}")
            return
        }
        val presence = CourierSignals.detectPresence(visibleText)
        if (presence != PresenceSignal.UNKNOWN) {
            suppressCurrentOffer("offer replaced by presence=$presence")
            return
        }

        if (currentPlatform.equals("Bolt", ignoreCase = true)) {
            val baseline = boltBaselineSurface
            if (baseline == null) {
                // Without a known live Bolt surface, do not resurrect a hidden card from guesswork.
                if (!temporarilyHidden) {
                    boltBaselineSurface = inspection.snapshot
                    resetMissingEvidence()
                }
                return
            }
            if (!LiveOfferSurfaceEvidence.materiallyChanged(baseline, inspection.snapshot)) {
                resetMissingEvidence()
                if (temporarilyHidden) restoreFromCache("same sparse Bolt offer surface returned")
                return
            }
            if (registerMissingEvidence(graceMs = BOLT_GONE_GRACE_MS, minChecks = BOLT_MIN_MISSING_CHECKS)) {
                temporarilyHide("Bolt offer surface is no longer visible")
            }
            return
        }

        // Missing Wolt controls alone are weak evidence: real-device telemetry shows the Compose
        // tree can stay semantically sparse for >1.5 s while the offer is still visible. Keep the
        // card through that transient gap; explicit lifecycle/presence still ends it immediately.
        if (registerMissingEvidence(graceMs = WOLT_UNCERTAIN_GRACE_MS, minChecks = WOLT_UNCERTAIN_MIN_CHECKS)) {
            temporarilyHide("Wolt offer surface remained unconfirmed")
        }
    }

    private fun findVisiblePackageRoot(packageName: String): AccessibilityNodeInfo? {
        fun refreshed(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            val candidate = node ?: return null
            if (candidate.packageName?.toString() != packageName) return null
            val valid = runCatching { candidate.refresh() }.getOrDefault(false)
            if (!valid || candidate.packageName?.toString() != packageName) return null
            return candidate
        }

        refreshed(service.rootInActiveWindow)?.let { return it }
        service.windows.forEach { window ->
            val candidate = runCatching { window.root }.getOrNull()
            refreshed(candidate)?.let { return it }
        }
        return null
    }

    private fun registerMissingEvidence(
        now: Long = SystemClock.elapsedRealtime(),
        graceMs: Long = GONE_GRACE_MS,
        minChecks: Int = MIN_MISSING_CHECKS,
    ): Boolean {
        if (missingSince == 0L) missingSince = now
        missingChecks += 1
        return missingChecks >= minChecks && now - missingSince >= graceMs
    }

    private fun resetMissingEvidence() {
        missingSince = 0L
        missingChecks = 0
    }

    private data class SurfaceInspection(
        val text: String,
        val snapshot: LiveOfferSurfaceSnapshot,
    )

    /**
     * Accessibility trees can retain Compose nodes after they are visually hidden. Only visible
     * nodes are allowed to keep an offer alive; otherwise stale Accept/Decline text can pin the
     * advisor on screen until the user opens another menu.
     */
    private fun inspectVisibleSurface(rootNode: AccessibilityNodeInfo): SurfaceInspection {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val pieces = mutableListOf<String>()
        val interactiveSlots = linkedSetOf<String>()
        val screenWidth = service.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        val screenHeight = service.resources.displayMetrics.heightPixels.coerceAtLeast(1)
        val bounds = Rect()
        queue.add(rootNode)
        var visited = 0
        var visibleNodes = 0
        var leafNodes = 0
        var bottomNodes = 0

        while (queue.isNotEmpty() && visited < 600) {
            val node = queue.removeFirst()
            visited += 1
            val childCount = node.childCount
            for (index in 0 until childCount) node.getChild(index)?.let(queue::addLast)

            if (!runCatching { node.isVisibleToUser }.getOrDefault(true)) continue
            visibleNodes += 1
            if (childCount == 0) leafNodes += 1

            listOf(node.text, node.contentDescription).forEach { value ->
                val cleaned = value?.toString()?.trim().orEmpty()
                if (cleaned.isNotEmpty() && pieces.lastOrNull() != cleaned) pieces += cleaned
            }

            bounds.setEmpty()
            runCatching { node.getBoundsInScreen(bounds) }
            val centerY = bounds.centerY()
            if (centerY >= (screenHeight * 55 / 100)) bottomNodes += 1

            val interactive = runCatching { node.isClickable || node.isLongClickable }.getOrDefault(false)
            if (interactive && !bounds.isEmpty) {
                val className = node.className?.toString()?.substringAfterLast('.') ?: "node"
                val centerXBin = (bounds.centerX().coerceIn(0, screenWidth) * 20 / screenWidth)
                val centerYBin = (bounds.centerY().coerceIn(0, screenHeight) * 20 / screenHeight)
                val widthBin = (bounds.width().coerceAtLeast(0) * 20 / screenWidth).coerceAtMost(20)
                val heightBin = (bounds.height().coerceAtLeast(0) * 20 / screenHeight).coerceAtMost(20)
                interactiveSlots += "$className:$centerXBin:$centerYBin:$widthBin:$heightBin"
            }
        }

        val text = pieces.joinToString("\n")
        return SurfaceInspection(
            text = text,
            snapshot = LiveOfferSurfaceSnapshot(
                windowId = rootNode.windowId,
                nodeCount = visibleNodes,
                leafCount = leafNodes,
                bottomNodeCount = bottomNodes,
                interactiveSlots = interactiveSlots,
                stableLines = LiveOfferSurfaceEvidence.normalizeStableLines(pieces),
            ),
        )
    }

    private fun hasDecisionPair(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        val accept = listOf("accept", "priimti", "принять", "прийняти").any(lower::contains)
        val decline = listOf("decline", "reject", "atmesti", "отклонить", "відхилити").any(lower::contains)
        return accept && decline
    }

    private fun installGestureSurface(view: View) {
        view.isClickable = true
        view.setOnTouchListener { _, event -> handleGesture(event) }
    }

    private fun handleGesture(event: MotionEvent): Boolean {
        val view = root
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureDownX = event.rawX
                gestureDownY = event.rawY
                gestureStartY = windowParams?.y ?: dp(DEFAULT_Y_DP)
                gestureMode = GESTURE_NONE
                view?.animate()?.cancel()
                view?.translationX = 0f
                view?.alpha = 1f
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - gestureDownX
                val dy = event.rawY - gestureDownY
                if (gestureMode == GESTURE_NONE && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    gestureMode = if (abs(dx) >= abs(dy)) GESTURE_HORIZONTAL else GESTURE_VERTICAL
                }
                if (gestureMode == GESTURE_HORIZONTAL) {
                    view?.translationX = dx
                    view?.alpha = (1f - abs(dx) / ((view?.width ?: 1).coerceAtLeast(1) * 1.1f)).coerceIn(0.3f, 1f)
                } else if (gestureMode == GESTURE_VERTICAL) {
                    moveTo(gestureStartY + dy.toInt())
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dx = event.rawX - gestureDownX
                val mode = gestureMode
                gestureMode = GESTURE_NONE
                if (mode == GESTURE_HORIZONTAL) {
                    val threshold = maxOf(dp(SWIPE_MIN_DP).toFloat(), (view?.width ?: 1) * SWIPE_FRACTION)
                    if (event.actionMasked == MotionEvent.ACTION_UP && abs(dx) >= threshold) {
                        suppressCurrentOffer("swiped by user")
                        return true
                    }
                    view?.animate()?.translationX(0f)?.alpha(1f)?.setDuration(SNAP_BACK_MS)?.start()
                } else if (mode == GESTURE_VERTICAL) {
                    windowParams?.y?.let { LiveAdvisorSettings.setOverlayYPx(service, it) }
                }
            }
        }
        return true
    }

    private fun moveTo(targetY: Int) {
        val view = root ?: return
        val params = windowParams ?: return
        params.y = clampY(targetY, view)
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun clampY(targetY: Int, view: View): Int {
        val min = dp(MIN_Y_DP)
        val max = (service.resources.displayMetrics.heightPixels - view.height - dp(BOTTOM_MARGIN_DP)).coerceAtLeast(min)
        return targetY.coerceIn(min, max)
    }

    private fun controlText(horizontalPadding: Int) = TextView(service).apply {
        setTextColor(Color.rgb(147, 197, 253))
        textSize = 10.5f
        setPadding(horizontalPadding, 3, horizontalPadding, 3)
        gravity = Gravity.CENTER
    }

    private fun baseSpeech(platform: String, parsed: ParsedOffer): String {
        val price = parsed.priceCents?.let { "${it / 100} euro ${it % 100}" }
        return listOfNotNull(platform, price).joinToString(". ") + "."
    }

    private fun speak(text: String) {
        pendingSpeech = text
        val engine = tts
        if (engine != null && ttsReady) {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "courierpilot-offer")
            pendingSpeech = null
            return
        }
        if (engine == null) {
            tts = TextToSpeech(service.applicationContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.language = Locale.ENGLISH
                    pendingSpeech?.let { pending ->
                        tts?.speak(pending, TextToSpeech.QUEUE_FLUSH, null, "courierpilot-offer")
                        pendingSpeech = null
                    }
                }
            }
        }
    }

    private fun packageForPlatform(platform: String): String = when {
        platform.equals("Wolt", true) -> CourierSignals.WOLT_PACKAGE
        platform.equals("Bolt", true) -> CourierSignals.BOLT_PACKAGE
        else -> ""
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()

    private companion object {
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        const val VISIBILITY_CHECK_MS = 750L
        const val HIDDEN_VISIBILITY_CHECK_MS = 1_500L
        const val GONE_GRACE_MS = 1_500L
        const val MIN_MISSING_CHECKS = 3
        const val BOLT_GONE_GRACE_MS = 700L
        const val BOLT_MIN_MISSING_CHECKS = 2
        const val WOLT_UNCERTAIN_GRACE_MS = 5_000L
        const val WOLT_UNCERTAIN_MIN_CHECKS = 5
        const val FADE_IN_MS = 220L
        const val FADE_OUT_MS = 160L
        const val FADE_OFFSET_DP = 6
        const val DEFAULT_Y_DP = 48
        const val MIN_Y_DP = 12
        const val BOTTOM_MARGIN_DP = 16
        const val HORIZONTAL_MARGIN_DP = 12
        const val SWIPE_MIN_DP = 44
        const val SWIPE_FRACTION = 0.16f
        const val SNAP_BACK_MS = 140L
        const val GESTURE_NONE = 0
        const val GESTURE_HORIZONTAL = 1
        const val GESTURE_VERTICAL = 2
    }
}
