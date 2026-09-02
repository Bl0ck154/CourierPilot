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

internal data class PlatformOfferEconomics(
    val euroPerKilometer: Double?,
    val euroPerHourMin: Double?,
    val euroPerHourMax: Double?,
    val currencyCode: String? = null,
) {
    val moneyPerKilometer: Double? get() = euroPerKilometer
    val moneyPerHourMin: Double? get() = euroPerHourMin
    val moneyPerHourMax: Double? get() = euroPerHourMax
}


internal object PlatformOfferEconomicsCalculator {
    fun calculate(parsed: ParsedOffer): PlatformOfferEconomics {
        val money = parsed.money
        val major = money?.major()?.toDouble()?.takeIf { it > 0.0 }
        val km = parsed.distanceMeters?.takeIf { it > 0 }?.div(1000.0)
        val minMinutes = parsed.estimatedMinutesMin?.takeIf { it > 0 }
        val maxMinutes = parsed.estimatedMinutesMax?.takeIf { it > 0 }
        val perKm = if (major != null && km != null) major / km else null
        val perHourMin = if (major != null && maxMinutes != null) major * 60.0 / maxMinutes else null
        val perHourMax = if (major != null && minMinutes != null) major * 60.0 / minMinutes else null
        return PlatformOfferEconomics(perKm, perHourMin, perHourMax, money?.currencyCode)
    }
}

/**
 * Compact TYPE_ACCESSIBILITY_OVERLAY advisor shown after a priced offer is persisted.
 *
 * Important: an accessibility overlay can itself become rootInActiveWindow on some Android/OEM
 * builds. Therefore foreground detection must look through AccessibilityService.windows for the
 * underlying Wolt/Bolt window before deciding that the courier app disappeared.
 */
internal class LiveOfferAdvisor(
    private val service: AccessibilityService,
    private val onRouteToggleChanged: ((platform: String, enabled: Boolean) -> Unit)? = null,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val overlayTouchSlop = ViewConfiguration.get(service).scaledTouchSlop

    private var root: LinearLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var routeText: TextView? = null
    private var economicsText: TextView? = null
    private var routeToggle: TextView? = null
    private var voiceToggle: TextView? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null

    private var currentPlatform = ""
    private var expectedPackageName = ""
    private var dismissedCurrentOffer = false
    private var offerGeneration = 0L
    private var missingOfferSinceElapsed = 0L
    private var missingOfferChecks = 0

    private var gestureDownRawX = 0f
    private var gestureDownRawY = 0f
    private var gestureStartWindowY = 0
    private var gestureMode = GESTURE_NONE

    private val visibilityWatchdog = object : Runnable {
        override fun run() {
            if (dismissedCurrentOffer || root == null) return
            checkOfferStillVisible()
            if (!dismissedCurrentOffer && root != null) {
                handler.postDelayed(this, VISIBILITY_CHECK_MS)
            }
        }
    }

    fun showBase(platform: String, parsed: ParsedOffer) {
        if (!LiveAdvisorSettings.enabled(service)) {
            suppressCurrentOffer("advisor disabled")
            return
        }

        offerGeneration += 1
        val generation = offerGeneration
        currentPlatform = platform
        expectedPackageName = packageForPlatform(platform)
        dismissedCurrentOffer = false
        resetMissingOfferEvidence()

        handler.post {
            if (generation != offerGeneration || dismissedCurrentOffer) return@post
            ensureView()
            if (root == null) return@post
            refreshControls()
            economicsText?.apply {
                val value = formatEconomics(parsed)
                text = value
                visibility = if (value.isBlank()) View.GONE else View.VISIBLE
            }
            val routeEnabled = LiveAdvisorSettings.routeEnabled(service, platform)
            routeText?.apply {
                visibility = if (routeEnabled) View.VISIBLE else View.GONE
                text = if (routeEnabled) "Calculating routes…" else ""
            }
            CaptureEventLog.append(
                service,
                stage = "overlay_show",
                platform = platform,
                message = "Live advisor shown; route=${if (routeEnabled) "on" else "off"}",
            )
            startVisibilityWatchdog()
            if (LiveAdvisorSettings.voiceEnabled(service)) speak(baseSpeech(platform, parsed))
        }
    }

    fun updateRoute(comparison: RouteComparison, waypointCount: Int) {
        if (!LiveAdvisorSettings.enabled(service) || dismissedCurrentOffer) return
        handler.post {
            if (dismissedCurrentOffer) return@post
            ensureView()
            routeText?.visibility = View.VISIBLE
            val pedestrian = comparison.pedestrian.getOrNull()
            val cycleway = comparison.cycleway.getOrNull()
            routeText?.text = formatRouteComparison(pedestrian, cycleway)
            CaptureEventLog.append(
                service,
                stage = "route_ready",
                platform = currentPlatform,
                message = "Route comparison ready; points=$waypointCount",
            )
        }
    }

    fun updateBoltRoute(outcome: AutomaticBoltRouteOutcome) {
        if (!LiveAdvisorSettings.enabled(service) || dismissedCurrentOffer) return
        handler.post {
            if (dismissedCurrentOffer) return@post
            ensureView()
            routeText?.visibility = View.VISIBLE
            val comparison = outcome.comparison
            if (comparison == null) {
                routeText?.text = "Route unavailable"
                CaptureEventLog.append(
                    service,
                    stage = "bolt_route_failed",
                    platform = "Bolt",
                    message = outcome.failureReason ?: "unknown route failure",
                )
                return@post
            }
            val pedestrian = comparison.pedestrian.getOrNull()
            val cycleway = comparison.cycleway.getOrNull()
            val route = formatRouteComparison(pedestrian, cycleway)
            routeText?.text = if (outcome.scope == BoltRouteScope.PICKUP_ONLY) {
                "To pickup only  ·  $route"
            } else {
                route
            }
            CaptureEventLog.append(
                service,
                stage = "bolt_route_ready",
                platform = "Bolt",
                message = "scope=${outcome.scope}; waypoints=${outcome.waypoints.size}; ${outcome.note.orEmpty()}".take(300),
            )
        }
    }

    fun updateRouteUnavailable(reason: String) {
        if (!LiveAdvisorSettings.enabled(service) || dismissedCurrentOffer) return
        handler.post {
            if (dismissedCurrentOffer) return@post
            ensureView()
            routeText?.visibility = View.VISIBLE
            routeText?.text = "Route unavailable"
            CaptureEventLog.append(
                service,
                stage = "route_failed",
                platform = currentPlatform,
                message = reason,
            )
        }
    }

    fun suppressCurrentOffer(reason: String = "superseded") {
        if (!dismissedCurrentOffer || root != null) {
            CaptureEventLog.append(
                service,
                stage = "overlay_hide",
                platform = currentPlatform,
                message = reason,
                dedupeWindowMs = 500L,
            )
        }
        dismissedCurrentOffer = true
        offerGeneration += 1
        hide()
    }

    fun hide() {
        handler.removeCallbacks(visibilityWatchdog)
        root?.let { view -> runCatching { windowManager.removeView(view) } }
        root = null
        windowParams = null
        routeText = null
        economicsText = null
        routeToggle = null
        voiceToggle = null
        gestureMode = GESTURE_NONE
        resetMissingOfferEvidence()
    }

    fun destroy() {
        suppressCurrentOffer("advisor destroyed")
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        pendingSpeech = null
    }

    private fun ensureView() {
        if (root != null) return

        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(14), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.argb(242, 17, 24, 39))
                setStroke(dp(1), Color.argb(145, 75, 85, 99))
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
                routeText?.apply {
                    if (enabled) {
                        visibility = View.VISIBLE
                        text = "Calculating routes…"
                    } else {
                        visibility = View.GONE
                        text = ""
                    }
                }
                onRouteToggleChanged?.invoke(currentPlatform, enabled)
            }
        }
        topRow.addView(routeToggle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        voiceToggle = controlText(dp(6)).apply {
            setOnClickListener {
                val enabled = !LiveAdvisorSettings.voiceEnabled(service)
                LiveAdvisorSettings.setVoiceEnabled(service, enabled)
                if (!enabled) tts?.stop()
                refreshControls()
            }
        }
        topRow.addView(voiceToggle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        topRow.addView(TextView(service).apply {
            text = "×"
            setTextColor(Color.LTGRAY)
            textSize = 21f
            gravity = Gravity.CENTER
            setPadding(dp(7), 0, 0, 0)
            setOnClickListener { suppressCurrentOffer("closed by user") }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        container.addView(topRow)

        economicsText = TextView(service).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(7), 0, 0)
        }.also {
            installGestureSurface(it)
            container.addView(it)
        }

        routeText = TextView(service).apply {
            setTextColor(Color.rgb(226, 232, 240))
            textSize = 13.5f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setPadding(0, dp(6), 0, 0)
            visibility = View.GONE
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
                    val clamped = clampOverlayY(current.y, container)
                    if (clamped != current.y) {
                        current.y = clamped
                        runCatching { windowManager.updateViewLayout(container, current) }
                    }
                }
            }
            .onFailure { error ->
                windowParams = null
                CaptureEventLog.append(
                    service,
                    stage = "overlay_add_failed",
                    platform = currentPlatform,
                    message = error.javaClass.simpleName + ": " + error.message.orEmpty(),
                )
            }
    }

    private fun installGestureSurface(view: View) {
        view.isClickable = true
        view.setOnTouchListener { _, event -> handleOverlayGesture(event) }
    }

    private fun handleOverlayGesture(event: MotionEvent): Boolean {
        val view = root
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureDownRawX = event.rawX
                gestureDownRawY = event.rawY
                gestureStartWindowY = windowParams?.y ?: dp(DEFAULT_Y_DP)
                gestureMode = GESTURE_NONE
                view?.animate()?.cancel()
                view?.translationX = 0f
                view?.alpha = 1f
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - gestureDownRawX
                val dy = event.rawY - gestureDownRawY
                if (gestureMode == GESTURE_NONE && (abs(dx) > overlayTouchSlop || abs(dy) > overlayTouchSlop)) {
                    gestureMode = if (abs(dx) >= abs(dy)) GESTURE_HORIZONTAL else GESTURE_VERTICAL
                }
                when (gestureMode) {
                    GESTURE_HORIZONTAL -> view?.let {
                        it.translationX = dx
                        it.alpha = (1f - abs(dx) / (it.width.coerceAtLeast(1) * 1.1f)).coerceIn(0.3f, 1f)
                    }
                    GESTURE_VERTICAL -> moveOverlayTo(gestureStartWindowY + dy.toInt())
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dx = event.rawX - gestureDownRawX
                val mode = gestureMode
                gestureMode = GESTURE_NONE
                if (mode == GESTURE_HORIZONTAL) {
                    val currentView = root
                    val dismissThreshold = currentView?.let {
                        maxOf(dp(SWIPE_MIN_DP).toFloat(), it.width * SWIPE_DISMISS_FRACTION)
                    } ?: dp(SWIPE_MIN_DP).toFloat()
                    if (event.actionMasked == MotionEvent.ACTION_UP && abs(dx) >= dismissThreshold) {
                        suppressCurrentOffer("swiped by user")
                        return true
                    }
                    currentView?.animate()?.translationX(0f)?.alpha(1f)?.setDuration(SNAP_BACK_MS)?.start()
                } else if (mode == GESTURE_VERTICAL) {
                    persistOverlayY()
                }
                return true
            }
        }
        return true
    }

    private fun startVisibilityWatchdog() {
        handler.removeCallbacks(visibilityWatchdog)
        handler.postDelayed(visibilityWatchdog, VISIBILITY_CHECK_MS)
    }

    private fun checkOfferStillVisible() {
        val expected = expectedPackageName
        if (expected.isBlank()) return

        val activeRoot = service.rootInActiveWindow
        val activePackage = activeRoot?.packageName?.toString().orEmpty()
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

            if (registerMissingOfferEvidence()) {
                suppressCurrentOffer("courier window disappeared; active=$activePackage")
            }
            return
        }

        resetMissingOfferEvidence()
        val visibleText = collectVisibleText(courierRoot)
        val lifecycle = DeliveryLifecycleTracking.detect(visibleText)
        val presence = CourierSignals.detectPresence(visibleText)
        if (lifecycle != null) {
            suppressCurrentOffer("offer ended: ${lifecycle.type}")
            return
        }
        if (presence != PresenceSignal.UNKNOWN) {
            suppressCurrentOffer("offer screen replaced by presence=$presence")
            return
        }

        val parsed = OfferParser.parse(visibleText)
        val hasOfferUi = CourierSignals.looksLikeOfferScreen(visibleText, parsed) || hasDecisionPair(visibleText)

        if (currentPlatform.equals("Bolt", ignoreCase = true)) {
            // Bolt's map/card is often almost invisible to Accessibility. As long as a Bolt window is
            // actually present, absence of semantic text is NOT evidence that the offer vanished.
            // We only hide on explicit lifecycle/presence text or when the Bolt window itself leaves.
            return
        }

        if (hasOfferUi) {
            resetMissingOfferEvidence()
            return
        }

        if (registerMissingOfferEvidence()) {
            suppressCurrentOffer("offer controls no longer visible")
        }
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

    private fun registerMissingOfferEvidence(nowElapsed: Long = SystemClock.elapsedRealtime()): Boolean {
        if (missingOfferSinceElapsed == 0L) missingOfferSinceElapsed = nowElapsed
        missingOfferChecks += 1
        return missingOfferChecks >= MIN_MISSING_CHECKS &&
            nowElapsed - missingOfferSinceElapsed >= OFFER_GONE_GRACE_MS
    }

    private fun resetMissingOfferEvidence() {
        missingOfferSinceElapsed = 0L
        missingOfferChecks = 0
    }

    private fun collectVisibleText(root: AccessibilityNodeInfo): String {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val pieces = mutableListOf<String>()
        queue.add(root)
        var visited = 0

        fun addPiece(value: CharSequence?) {
            val cleaned = value?.toString()?.trim()?.takeIf(String::isNotEmpty) ?: return
            if (pieces.lastOrNull() != cleaned) pieces += cleaned
        }

        while (queue.isNotEmpty() && visited < 500) {
            val node = queue.removeFirst()
            visited += 1
            addPiece(node.text)
            addPiece(node.contentDescription)
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

    private fun packageForPlatform(platform: String): String = when {
        platform.equals("Wolt", ignoreCase = true) -> CourierSignals.WOLT_PACKAGE
        platform.equals("Bolt", ignoreCase = true) -> CourierSignals.BOLT_PACKAGE
        else -> ""
    }

    private fun moveOverlayTo(targetY: Int) {
        val view = root ?: return
        val params = windowParams ?: return
        val clamped = clampOverlayY(targetY, view)
        if (params.y == clamped) return
        params.y = clamped
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun persistOverlayY() {
        val y = windowParams?.y ?: return
        LiveAdvisorSettings.setOverlayYPx(service, y)
    }

    private fun clampOverlayY(targetY: Int, view: View): Int {
        val minY = dp(MIN_Y_DP)
        val screenHeight = service.resources.displayMetrics.heightPixels
        val maxY = (screenHeight - view.height - dp(BOTTOM_MARGIN_DP)).coerceAtLeast(minY)
        return targetY.coerceIn(minY, maxY)
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()

    private fun controlText(horizontalPadding: Int): TextView = TextView(service).apply {
        setTextColor(Color.rgb(147, 197, 253))
        textSize = 10.5f
        setPadding(horizontalPadding, 3, horizontalPadding, 3)
        gravity = Gravity.CENTER
    }

    private fun refreshControls() {
        routeToggle?.apply {
            visibility = if (currentPlatform.equals("Wolt", true) || currentPlatform.equals("Bolt", true)) View.VISIBLE else View.GONE
            text = "Route ${if (LiveAdvisorSettings.routeEnabled(service, currentPlatform)) "ON" else "OFF"}"
        }
        voiceToggle?.text = if (LiveAdvisorSettings.voiceEnabled(service)) "🔊" else "🔇"
    }

    private fun formatEconomics(parsed: ParsedOffer): String {
        val economics = PlatformOfferEconomicsCalculator.calculate(parsed)
        return buildList {
            val lo = economics.moneyPerHourMin
            val hi = economics.moneyPerHourMax
            if (lo != null && hi != null) {
                add(
                    if (kotlin.math.abs(lo - hi) < 0.05) {
                        "${formatMoney(lo, economics.currencyCode, 1)}/h"
                    } else {
                        "${formatMoney(lo, economics.currencyCode, 1)}–${formatMoney(hi, economics.currencyCode, 1)}/h"
                    },
                )
            }
            economics.moneyPerKilometer?.let { add("${formatMoney(it, economics.currencyCode, 2)}/km") }
        }.joinToString("   •   ")
    }

    private fun formatMoney(value: Double, currencyCode: String?, decimals: Int): String {
        val amount = "% .${decimals}f".format(Locale.US, value).trim()
        return if (currencyCode == "EUR") "€$amount" else "${currencyCode ?: ""} $amount".trim()
    }

    private fun formatRouteComparison(pedestrian: RouteResult?, cycleway: RouteResult?): String {
        val walking = pedestrian?.let { formatKm(it.distanceMeters) } ?: "—"
        val cycling = cycleway?.let { formatKm(it.distanceMeters) } ?: "—"
        return "🚶 $walking      🚲 $cycling"
    }

    private fun baseSpeech(platform: String, parsed: ParsedOffer): String {
        val price = parsed.money?.let { "${it.major().toPlainString()} ${it.currencyCode}" }
            ?: parsed.priceCents?.let { "${it / 100}.${(it % 100).toString().padStart(2, '0')} EUR" }
            ?: "price unknown"
        val distance = parsed.distanceMeters?.let { "${"%.1f".format(Locale.US, it / 1000.0)} kilometers" }
        val eta = when {
            parsed.estimatedMinutesMin != null && parsed.estimatedMinutesMax != null ->
                "${parsed.estimatedMinutesMin} to ${parsed.estimatedMinutesMax} minutes"
            parsed.estimatedMinutesMin != null -> "${parsed.estimatedMinutesMin} minutes"
            else -> null
        }
        return listOfNotNull(platform, price, distance, eta).joinToString(". ") + "."
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

    private fun formatKm(meters: Int): String = "${"%.2f".format(Locale.US, meters / 1000.0)} km"

    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val VISIBILITY_CHECK_MS = 650L
        private const val OFFER_GONE_GRACE_MS = 1_800L
        private const val MIN_MISSING_CHECKS = 3
        private const val DEFAULT_Y_DP = 48
        private const val MIN_Y_DP = 12
        private const val BOTTOM_MARGIN_DP = 16
        private const val HORIZONTAL_MARGIN_DP = 12
        private const val SWIPE_MIN_DP = 44
        private const val SWIPE_DISMISS_FRACTION = 0.16f
        private const val SNAP_BACK_MS = 140L
        private const val GESTURE_NONE = 0
        private const val GESTURE_HORIZONTAL = 1
        private const val GESTURE_VERTICAL = 2
    }
}
