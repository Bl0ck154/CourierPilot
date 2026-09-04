package com.block154.courierpilot

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.ColorStateList
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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.abs

/**
 * Stable live card: the shell appears first with profitability data, then routing updates the same
 * card in place. Route work never owns card lifetime and a route callback cannot create a new card.
 */
internal class StableLiveOfferAdvisor(
    private val service: AccessibilityService,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop

    private var root: LinearLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var decisionContainer: FrameLayout? = null
    private var decisionText: TextView? = null
    private var decisionSpinner: ProgressBar? = null
    private var routeText: TextView? = null

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
    private var cachedDecisionLoading = true
    private var cachedRouteLine = ""
    private var cachedRouteVisible = true
    private var cachedPedestrianRoute: RouteResult? = null
    private var cachedCyclewayRoute: RouteResult? = null
    private val differentOfferConfirmation = OfferDifferenceConfirmation()

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
            cachedDecisionLoading = true
            cachedRouteLine = ""
            cachedRouteVisible = true
            cachedPedestrianRoute = null
            cachedCyclewayRoute = null
            resetMissingEvidence()
            boltBaselineSurface = if (platform.equals("Bolt", ignoreCase = true)) {
                findVisiblePackageRoot(packageName)?.let { inspectVisibleSurface(it).snapshot }
            } else null
        }
        previewMode = true
        currentParsed = parsed
        differentOfferConfirmation.reset()
        renderProgressiveDecision(parsed)
        if (cachedRouteLine.isBlank()) renderRouteLoadingState()
        if (!temporarilyHidden) {
            ensureView()
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
            differentOfferConfirmation.reset()
            renderProgressiveDecision(parsed)
            if (cachedRouteLine.isBlank()) renderRouteLoadingState()
            if (!temporarilyHidden) {
                ensureView()
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
        cachedDecisionLoading = true
        cachedRouteLine = ""
        cachedRouteVisible = true
        cachedPedestrianRoute = null
        cachedCyclewayRoute = null
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
            renderProgressiveDecision(parsed)
            renderRouteLoadingState()
            if (!temporarilyHidden) {
                ensureView()
                if (root != null) {
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

    /**
     * Courier UIs can briefly expose contradictory merchant/address snapshots while the same offer
     * recomposes. A single such frame must never destroy/re-arm the live card. Notifications still
     * arm truly new offers immediately; screen-only replacement needs stable conflict evidence.
     */
    fun isConfirmedDifferentOffer(packageName: String, parsed: ParsedOffer): Boolean {
        if (!isTrackingOffer(packageName)) return true
        val expected = currentParsed ?: return true
        return differentOfferConfirmation.observe(
            different = LiveOfferResumePolicy.definitelyDifferent(expected, parsed),
            nowElapsedMs = SystemClock.elapsedRealtime(),
        )
    }

    fun updateRoute(comparison: RouteComparison, waypointCount: Int) {
        if (dismissed || !LiveAdvisorSettings.enabled(service)) return
        handler.post {
            if (dismissed) return@post
            val walking = comparison.pedestrian.getOrNull()
            val cycling = comparison.cycleway.getOrNull()
            cachedPedestrianRoute = walking
            cachedCyclewayRoute = cycling
            currentParsed?.let(::renderProgressiveDecision)
            setRouteContent(LiveAdvisorPresentation.routeLine(walking, cycling))
            CaptureEventLog.append(
                service,
                stage = "route_ready",
                platform = currentPlatform,
                message = "Route updated cached card; points=$waypointCount; visible=${root != null}; card_age_ms=${(SystemClock.elapsedRealtime() - offerVisualStartedAtElapsed).coerceAtLeast(0L)}",
            )
        }
    }

    fun updateBoltRoute(outcome: AutomaticBoltRouteOutcome) {
        if (dismissed || !LiveAdvisorSettings.enabled(service)) return
        handler.post {
            if (dismissed) return@post
            val comparison = outcome.comparison
            if (comparison == null) {
                setDecisionUnavailable()
                setRouteContent("⚠️ Route unavailable")
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
            if (outcome.scope == BoltRouteScope.FULL) {
                cachedPedestrianRoute = walking
                cachedCyclewayRoute = cycling
                currentParsed?.let { parsed -> renderProfitability(parsed, walking, cycling) }
            } else {
                // A pickup-only route is useful context, but it is not the full paid delivery.
                // Never turn that partial distance into a misleading €/km verdict.
                cachedPedestrianRoute = null
                cachedCyclewayRoute = null
                currentParsed?.let { parsed -> renderProfitability(parsed, null, null) }
            }
            setRouteContent(LiveAdvisorPresentation.routeLine(walking, cycling))
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
            setDecisionUnavailable()
            setRouteContent("⚠️ Route unavailable")
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
        when {
            packageName == expectedPackageName -> onCourierWindowEvent(packageName)
            packageName == service.packageName || isTransientSystemOverlayPackage(packageName) -> Unit
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

    /**
     * The live card has one primary number: native money per real route kilometre. Until both
     * money and a full route exist, the right side stays as a spinner instead of exposing internal
     * capture states such as "price ready" or "calculating".
     */
    private fun renderProgressiveDecision(parsed: ParsedOffer) {
        val hasPrice = parsed.priceCents != null && parsed.money != null
        val hasRoute = cachedPedestrianRoute != null || cachedCyclewayRoute != null
        when {
            !hasPrice -> setDecisionLoading()
            hasRoute -> renderProfitability(parsed, cachedPedestrianRoute, cachedCyclewayRoute)
            LiveAdvisorSettings.routeEnabled(service, currentPlatform) -> setDecisionLoading()
            else -> setDecisionUnavailable()
        }
    }

    private fun setDecisionLoading() {
        cachedDecisionLine = ""
        cachedDecisionBand = OfferDecisionBand.UNKNOWN
        cachedDecisionLoading = true
        applyDecisionPresentation()
    }

    private fun setDecisionUnavailable() {
        cachedDecisionLine = "—/km"
        cachedDecisionBand = OfferDecisionBand.UNKNOWN
        cachedDecisionLoading = false
        applyDecisionPresentation()
    }

    private fun renderProfitability(
        parsed: ParsedOffer,
        pedestrianRoute: RouteResult?,
        cyclewayRoute: RouteResult?,
    ) {
        val currencyCode = parsed.money?.currencyCode
        val adaptiveThresholds = currencyCode?.let { code ->
            MarketIntelligence.thresholdsFor(service, currentPlatform, code)
        }
        val coldStartThresholds = currencyCode?.let(LiveOfferColdStartThresholds::forCurrency)
        val thresholdSource = when {
            adaptiveThresholds != null -> "adaptive"
            coldStartThresholds != null -> "currency_cold_start"
            else -> "none"
        }
        val decision = OfferDecisionEngine.evaluate(
            parsed,
            pedestrianRoute,
            cyclewayRoute,
            thresholds = adaptiveThresholds ?: coldStartThresholds,
        )
        CaptureEventLog.append(
            service,
            stage = "score_model",
            platform = currentPlatform,
            message = "source=$thresholdSource; currency=${currencyCode ?: "none"}; band=${decision.band.name}; " +
                "rate=${decision.moneyPerKilometer?.let { "%.2f".format(Locale.US, it) } ?: "none"}",
            dedupeWindowMs = 5_000L,
        )
        if (decision.moneyPerKilometer == null) {
            setDecisionLoading()
            return
        }
        cachedDecisionLine = LiveAdvisorPresentation.rateLine(decision)
        cachedDecisionBand = decision.band
        cachedDecisionLoading = false
        applyDecisionPresentation()
    }

    private fun renderRouteLoadingState() {
        // The spinner on the primary €/km field is enough feedback; keep the left side uncluttered.
        setRouteContent("", visible = false)
    }

    private fun setRouteContent(text: String, visible: Boolean = true) {
        cachedRouteLine = text
        cachedRouteVisible = visible
        routeText?.apply {
            visibility = if (visible) View.VISIBLE else View.INVISIBLE
            this.text = text
        }
    }

    private fun applyCachedPresentation() {
        applyDecisionPresentation()
        routeText?.apply {
            visibility = if (cachedRouteVisible) View.VISIBLE else View.INVISIBLE
            text = cachedRouteLine
        }
    }

    private fun applyDecisionPresentation() {
        val loading = cachedDecisionLoading
        decisionSpinner?.visibility = if (loading) View.VISIBLE else View.GONE
        decisionText?.apply {
            visibility = if (loading) View.INVISIBLE else View.VISIBLE
            text = cachedDecisionLine
            setTextColor(decisionColor(cachedDecisionBand))
            when (cachedDecisionBand) {
                OfferDecisionBand.FIRE -> setShadowLayer(dp(5).toFloat(), 0f, 0f, Color.argb(210, 255, 112, 38))
                OfferDecisionBand.GOOD -> setShadowLayer(dp(3).toFloat(), 0f, 0f, Color.argb(120, 52, 211, 153))
                OfferDecisionBand.OK -> setShadowLayer(dp(2).toFloat(), 0f, 0f, Color.argb(75, 245, 158, 11))
                else -> clearShadowLayer()
            }
        }
        decisionContainer?.background = decisionBackground(cachedDecisionBand, loading)
    }

    private fun ensureView() {
        if (root != null) return

        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(5), dp(10), dp(7))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.argb(246, 15, 23, 36))
                setStroke(dp(1), Color.argb(125, 71, 85, 105))
            }
            elevation = dp(9).toFloat()
        }
        installGestureSurface(container)

        val topRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        installGestureSurface(topRow)

        val title = TextView(service).apply {
            text = "CourierPilot · ${BuildConfig.VERSION_NAME}"
            setTextColor(Color.rgb(148, 163, 184))
            textSize = 9.5f
            includeFontPadding = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        installGestureSurface(title)
        topRow.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        topRow.addView(TextView(service).apply {
            text = "×"
            setTextColor(Color.rgb(148, 163, 184))
            textSize = 17f
            includeFontPadding = false
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, 0, 0)
            setOnClickListener { suppressCurrentOffer("closed by user") }
        })
        container.addView(topRow)

        val mainRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, 0)
        }
        installGestureSurface(mainRow)

        routeText = TextView(service).apply {
            setTextColor(Color.rgb(190, 200, 214))
            textSize = 11.5f
            includeFontPadding = false
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            maxLines = 2
        }.also { view ->
            installGestureSurface(view)
            mainRow.addView(
                view,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(8)
                },
            )
        }

        val rateFrame = FrameLayout(service).apply {
            minimumWidth = dp(RATE_MIN_WIDTH_DP)
            minimumHeight = dp(RATE_MIN_HEIGHT_DP)
            background = decisionBackground(OfferDecisionBand.UNKNOWN, loading = true)
        }
        installGestureSurface(rateFrame)
        decisionContainer = rateFrame

        decisionText = TextView(service).apply {
            textSize = 24f
            includeFontPadding = false
            typeface = Typeface.create("monospace", Typeface.BOLD)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(3), dp(9), dp(3))
            maxLines = 1
        }.also { view ->
            installGestureSurface(view)
            rateFrame.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.END or Gravity.CENTER_VERTICAL,
                ),
            )
        }

        decisionSpinner = ProgressBar(service, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Color.rgb(148, 163, 184))
        }.also { spinner ->
            rateFrame.addView(
                spinner,
                FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER),
            )
        }

        mainRow.addView(
            rateFrame,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(RATE_MIN_HEIGHT_DP)),
        )
        container.addView(mainRow)

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
                decisionContainer = null
                decisionText = null
                decisionSpinner = null
                routeText = null
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
        decisionContainer = null
        decisionText = null
        decisionSpinner = null
        routeText = null
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

    /**
     * Keep Wolt visible during display fallback captures. Before price the card contains no money
     * token, and after price the final proof capture is not reparsed; hiding it was pure user-visible
     * flicker on Realme/ColorOS. Bolt keeps the conservative suppression path because its OCR is more
     * spatially fragile on Android versions that cannot capture a single app window.
     */
    fun setCaptureSuppressed(suppressed: Boolean) {
        if (!LiveAdvisorCapturePolicy.shouldSuppressOverlay(currentPlatform)) {
            captureSuppressed = false
            root?.apply {
                animate().cancel()
                translationY = 0f
                alpha = 1f
            }
            return
        }
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
        cachedDecisionLoading = true
        cachedRouteLine = ""
        cachedRouteVisible = true
        cachedPedestrianRoute = null
        cachedCyclewayRoute = null
        differentOfferConfirmation.reset()
    }


    private fun decisionColor(band: OfferDecisionBand): Int = when (band) {
        OfferDecisionBand.FIRE -> Color.rgb(255, 139, 61)
        OfferDecisionBand.GOOD -> Color.rgb(110, 231, 183)
        OfferDecisionBand.OK -> Color.rgb(245, 190, 72)
        OfferDecisionBand.BAD -> Color.rgb(177, 143, 128)
        OfferDecisionBand.TERRIBLE -> Color.rgb(121, 132, 148)
        OfferDecisionBand.UNKNOWN -> Color.rgb(190, 200, 214)
    }

    private fun decisionBackground(band: OfferDecisionBand, loading: Boolean): GradientDrawable {
        val accent = if (loading) Color.rgb(100, 116, 139) else decisionColor(band)
        val fillAlpha = when {
            loading -> 10
            band == OfferDecisionBand.FIRE -> 30
            band == OfferDecisionBand.GOOD -> 20
            band == OfferDecisionBand.OK -> 15
            else -> 9
        }
        val strokeAlpha = when {
            loading -> 30
            band == OfferDecisionBand.FIRE -> 150
            band == OfferDecisionBand.GOOD -> 95
            band == OfferDecisionBand.OK -> 70
            else -> 38
        }
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(Color.argb(fillAlpha, Color.red(accent), Color.green(accent), Color.blue(accent)))
            setStroke(
                dp(1),
                Color.argb(strokeAlpha, Color.red(accent), Color.green(accent), Color.blue(accent)),
            )
        }
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
            !isTransientSystemOverlayPackage(activePackage)

        if (definitelyAway) {
            temporarilyHide("foreground changed to $activePackage")
            return
        }

        if (courierRoot == null) {
            if (isTransientSystemOverlayPackage(activePackage)) {
                resetMissingEvidence()
                return
            }
            if (registerMissingEvidence()) {
                temporarilyHide("courier window temporarily unavailable; active=$activePackage")
            }
            return
        }

        val inspection = inspectVisibleSurface(courierRoot)
        val visibleText = inspection.text
        val parsed = OfferParser.parse(visibleText)
        val hasOfferUi = CourierSignals.looksLikeOfferScreen(visibleText, parsed) || hasDecisionPair(visibleText)

        // Strong accepted/in-progress task surfaces always beat stale offer identity. Bolt can keep
        // merchant/address nodes around after Accept, so waiting for generic surface change was able
        // to pin the card over Dropoff/Address details screens indefinitely.
        if (DeliveryLifecycleTracking.hasActiveTaskSurface(visibleText)) {
            suppressCurrentOffer("offer accepted; active delivery screen visible")
            return
        }

        // A live offer is stronger evidence than generic background/presence strings rendered on
        // the same Wolt screen. In particular, Wolt can expose "Go offline" while an incoming
        // offer is still fully visible. Never end the card before checking the offer UI itself.
        if (hasOfferUi) {
            if (isConfirmedDifferentOffer(expected, parsed)) {
                suppressCurrentOffer("different offer is now stably visible")
                return
            }
            resetMissingEvidence()
            if (currentPlatform.equals("Bolt", ignoreCase = true)) {
                // Adopt the latest confirmed same-offer surface after map zoom/recomposition instead
                // of treating the old geometry snapshot as immutable proof that the offer vanished.
                boltBaselineSurface = inspection.snapshot
            }
            if (temporarilyHidden) restoreFromCache("same offer returned to foreground")
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

        // Compose/map recompositions can temporarily remove Accept/Decline while retaining the same
        // price, merchant or address. This identity is stronger than window geometry on both apps.
        if (LiveOfferResumePolicy.hasMatchingIdentity(expectedOffer, parsed)) {
            resetMissingEvidence()
            if (currentPlatform.equals("Bolt", ignoreCase = true)) boltBaselineSurface = inspection.snapshot
            if (temporarilyHidden) restoreFromCache("same offer identity returned without controls")
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
            // Zooming/panning the live map changes a large part of Bolt's Accessibility geometry even
            // though the bottom offer card is exactly the same. Geometry alone therefore gets a long
            // grace period; explicit task/presence/different-offer evidence above still hides at once.
            if (registerMissingEvidence(graceMs = BOLT_GONE_GRACE_MS, minChecks = BOLT_MIN_MISSING_CHECKS)) {
                temporarilyHide("Bolt offer surface remained unconfirmed")
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

    private fun isTransientSystemOverlayPackage(packageName: String): Boolean =
        packageName == SYSTEM_UI_PACKAGE ||
            packageName == "com.oplus.screenshot" ||
            packageName == "com.coloros.screenshot"

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


    private fun baseSpeech(platform: String, parsed: ParsedOffer): String {
        val price = parsed.money?.let { "${it.major().toPlainString()} ${it.currencyCode}" }
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
        const val BOLT_GONE_GRACE_MS = 8_000L
        const val BOLT_MIN_MISSING_CHECKS = 5
        const val WOLT_UNCERTAIN_GRACE_MS = 5_000L
        const val WOLT_UNCERTAIN_MIN_CHECKS = 5
        const val FADE_IN_MS = 380L
        const val FADE_OUT_MS = 280L
        const val FADE_OFFSET_DP = 10
        const val DEFAULT_Y_DP = 48
        const val MIN_Y_DP = 12
        const val BOTTOM_MARGIN_DP = 16
        const val HORIZONTAL_MARGIN_DP = 12
        const val RATE_MIN_WIDTH_DP = 176
        const val RATE_MIN_HEIGHT_DP = 44
        const val SWIPE_MIN_DP = 44
        const val SWIPE_FRACTION = 0.16f
        const val SNAP_BACK_MS = 140L
        const val GESTURE_NONE = 0
        const val GESTURE_HORIZONTAL = 1
        const val GESTURE_VERTICAL = 2
    }
}
