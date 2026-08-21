package com.block154.courierpilot

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
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
    private var economicsText: TextView? = null
    private var routeText: TextView? = null
    private var routeToggle: TextView? = null
    private var voiceToggle: TextView? = null

    private var currentPlatform = ""
    private var currentParsed: ParsedOffer? = null
    private var expectedPackageName = ""
    private var dismissed = false
    private var generation = 0L
    private var missingSince = 0L
    private var missingChecks = 0

    private var gestureDownX = 0f
    private var gestureDownY = 0f
    private var gestureStartY = 0
    private var gestureMode = GESTURE_NONE

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null

    private val visibilityWatchdog = object : Runnable {
        override fun run() {
            if (dismissed || root == null) return
            checkOfferStillVisible()
            if (!dismissed && root != null) handler.postDelayed(this, VISIBILITY_CHECK_MS)
        }
    }

    fun showBase(platform: String, parsed: ParsedOffer) {
        if (!LiveAdvisorSettings.enabled(service)) {
            suppressCurrentOffer("advisor disabled")
            return
        }

        generation += 1
        val expectedGeneration = generation
        currentPlatform = platform
        currentParsed = parsed
        expectedPackageName = packageForPlatform(platform)
        dismissed = false
        resetMissingEvidence()

        handler.post {
            if (dismissed || expectedGeneration != generation) return@post
            ensureView()
            if (root == null) return@post
            refreshControls()
            renderProfitability(parsed, completeCyclewayRoute = null)
            renderRouteLoadingState()
            CaptureEventLog.append(
                service,
                stage = "overlay_show",
                platform = platform,
                message = "Stable advisor shell shown before route result",
            )
            startVisibilityWatchdog()
            if (LiveAdvisorSettings.voiceEnabled(service)) speak(baseSpeech(platform, parsed))
        }
    }

    fun updateRoute(comparison: RouteComparison, waypointCount: Int) {
        if (dismissed || !LiveAdvisorSettings.enabled(service)) return
        handler.post {
            if (dismissed || root == null) return@post
            val walking = comparison.pedestrian.getOrNull()
            val cycling = comparison.cycleway.getOrNull()
            currentParsed?.let { parsed -> renderProfitability(parsed, cycling) }
            routeText?.apply {
                visibility = View.VISIBLE
                text = formatRoutes(walking, cycling)
            }
            CaptureEventLog.append(
                service,
                stage = "route_ready",
                platform = currentPlatform,
                message = "Valhalla updated existing card; points=$waypointCount",
            )
        }
    }

    fun updateBoltRoute(outcome: AutomaticBoltRouteOutcome) {
        if (dismissed || !LiveAdvisorSettings.enabled(service)) return
        handler.post {
            if (dismissed || root == null) return@post
            val comparison = outcome.comparison
            if (comparison == null) {
                routeText?.text = "⚠️ Valhalla · маршрут недоступний"
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
                currentParsed?.let { parsed -> renderProfitability(parsed, cycling) }
            }
            val routes = formatRoutes(walking, cycling)
            routeText?.text = if (outcome.scope == BoltRouteScope.PICKUP_ONLY) {
                "До pickup лише · $routes"
            } else {
                routes
            }
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
            if (dismissed || root == null) return@post
            routeText?.text = "⚠️ Valhalla · маршрут недоступний"
            CaptureEventLog.append(service, "route_failed", reason, currentPlatform)
        }
    }

    fun suppressCurrentOffer(reason: String = "superseded") {
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
        generation += 1
        hideView()
    }

    fun destroy() {
        suppressCurrentOffer("advisor destroyed")
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        pendingSpeech = null
    }

    private fun renderProfitability(parsed: ParsedOffer, completeCyclewayRoute: RouteResult?) {
        val decision = OfferDecisionEngine.evaluate(parsed, completeCyclewayRoute)
        decisionText?.apply {
            val scorePart = decision.score?.let { "$it/100" } ?: "—/100"
            val source = when {
                decision.routeVerifiedKilometerRate -> " · 🚲 маршрут"
                decision.score != null -> " · попередньо"
                else -> " · чекаю дані"
            }
            text = "${decision.band.emoji} $scorePart · ${decision.band.label}$source"
            setTextColor(decisionColor(decision.band))
        }

        val platformEconomics = PlatformOfferEconomicsCalculator.calculate(parsed)
        val kmText = decision.euroPerKilometer?.let {
            "€${"%.2f".format(Locale.US, it)}/km${if (decision.routeVerifiedKilometerRate) " 🚲" else ""}"
        } ?: "€/km —"
        val lo = platformEconomics.euroPerHourMin
        val hi = platformEconomics.euroPerHourMax
        val hourText = when {
            lo != null && hi != null && abs(lo - hi) < 0.05 -> "€${"%.1f".format(Locale.US, lo)}/h"
            lo != null && hi != null -> "€${"%.1f".format(Locale.US, lo)}–€${"%.1f".format(Locale.US, hi)}/h"
            else -> "€/h —"
        }
        economicsText?.text = "$kmText   •   $hourText"
    }

    private fun renderRouteLoadingState() {
        val enabled = LiveAdvisorSettings.routeEnabled(service, currentPlatform)
        routeText?.apply {
            visibility = View.VISIBLE
            text = if (enabled) "⏳ Valhalla · рахую маршрут…" else "Маршрут вимкнено"
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

        economicsText = TextView(service).apply {
            setTextColor(Color.WHITE)
            textSize = 14.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(5), 0, 0)
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

    private fun hideView() {
        handler.removeCallbacks(visibilityWatchdog)
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        windowParams = null
        decisionText = null
        economicsText = null
        routeText = null
        routeToggle = null
        voiceToggle = null
        gestureMode = GESTURE_NONE
        resetMissingEvidence()
    }

    private fun refreshControls() {
        routeToggle?.apply {
            visibility = if (currentPlatform.equals("Wolt", true) || currentPlatform.equals("Bolt", true)) View.VISIBLE else View.GONE
            text = "Route ${if (LiveAdvisorSettings.routeEnabled(service, currentPlatform)) "ON" else "OFF"}"
        }
        voiceToggle?.text = if (LiveAdvisorSettings.voiceEnabled(service)) "🔊" else "🔇"
    }

    private fun decisionColor(band: OfferDecisionBand): Int = when (band) {
        OfferDecisionBand.TAKE -> Color.rgb(134, 239, 172)
        OfferDecisionBand.GOOD -> Color.rgb(167, 243, 208)
        OfferDecisionBand.OK -> Color.rgb(253, 224, 71)
        OfferDecisionBand.WEAK -> Color.rgb(253, 186, 116)
        OfferDecisionBand.SKIP -> Color.rgb(252, 165, 165)
        OfferDecisionBand.UNKNOWN -> Color.rgb(203, 213, 225)
    }

    private fun formatRoutes(walking: RouteResult?, cycling: RouteResult?): String {
        val walk = walking?.let { formatKm(it.distanceMeters) } ?: "—"
        val cycle = cycling?.let { formatKm(it.distanceMeters) } ?: "—"
        return "🚶 $walk      🚲 $cycle"
    }

    private fun startVisibilityWatchdog() {
        handler.removeCallbacks(visibilityWatchdog)
        handler.postDelayed(visibilityWatchdog, VISIBILITY_CHECK_MS)
    }

    private fun checkOfferStillVisible() {
        val expected = expectedPackageName
        if (expected.isBlank()) return

        val activePackage = service.rootInActiveWindow?.packageName?.toString().orEmpty()
        val courierRoot = findVisiblePackageRoot(expected)
        if (courierRoot == null) {
            val definitelyForeign = activePackage.isNotBlank() &&
                activePackage != expected &&
                activePackage != service.packageName &&
                activePackage != SYSTEM_UI_PACKAGE
            if (definitelyForeign) {
                suppressCurrentOffer("foreground changed to $activePackage")
                return
            }
            if (registerMissingEvidence()) suppressCurrentOffer("courier window disappeared")
            return
        }

        resetMissingEvidence()
        val visibleText = collectVisibleText(courierRoot)
        DeliveryLifecycleTracking.detect(visibleText)?.let {
            suppressCurrentOffer("offer ended: ${it.type}")
            return
        }
        val presence = CourierSignals.detectPresence(visibleText)
        if (presence != PresenceSignal.UNKNOWN) {
            suppressCurrentOffer("offer replaced by presence=$presence")
            return
        }

        // Bolt's offer card/map is frequently sparse in Accessibility. Window presence is stronger
        // evidence than missing text, so generic text absence must never make the Bolt card blink.
        if (currentPlatform.equals("Bolt", ignoreCase = true)) return

        val parsed = OfferParser.parse(visibleText)
        if (CourierSignals.looksLikeOfferScreen(visibleText, parsed) || hasDecisionPair(visibleText)) return
        if (registerMissingEvidence()) suppressCurrentOffer("Wolt offer controls disappeared")
    }

    private fun findVisiblePackageRoot(packageName: String): AccessibilityNodeInfo? {
        val active = service.rootInActiveWindow
        if (active?.packageName?.toString() == packageName) return active
        service.windows.forEach { window ->
            val candidate = runCatching { window.root }.getOrNull() ?: return@forEach
            if (candidate.packageName?.toString() == packageName) return candidate
        }
        return null
    }

    private fun registerMissingEvidence(now: Long = SystemClock.elapsedRealtime()): Boolean {
        if (missingSince == 0L) missingSince = now
        missingChecks += 1
        return missingChecks >= MIN_MISSING_CHECKS && now - missingSince >= GONE_GRACE_MS
    }

    private fun resetMissingEvidence() {
        missingSince = 0L
        missingChecks = 0
    }

    private fun collectVisibleText(rootNode: AccessibilityNodeInfo): String {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val pieces = mutableListOf<String>()
        queue.add(rootNode)
        var visited = 0
        while (queue.isNotEmpty() && visited < 600) {
            val node = queue.removeFirst()
            visited += 1
            listOf(node.text, node.contentDescription).forEach { value ->
                val cleaned = value?.toString()?.trim().orEmpty()
                if (cleaned.isNotEmpty() && pieces.lastOrNull() != cleaned) pieces += cleaned
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
        }
        return pieces.joinToString("\n")
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
        val decision = OfferDecisionEngine.evaluate(parsed)
        val score = decision.score?.let { "$it out of 100" }
        val price = parsed.priceCents?.let { "${it / 100} euro ${it % 100}" }
        return listOfNotNull(platform, price, score).joinToString(". ") + "."
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

    private fun formatKm(meters: Int): String = "${"%.2f".format(Locale.US, meters / 1000.0)} km"
    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()

    private companion object {
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        const val VISIBILITY_CHECK_MS = 750L
        const val GONE_GRACE_MS = 1_500L
        const val MIN_MISSING_CHECKS = 3
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
