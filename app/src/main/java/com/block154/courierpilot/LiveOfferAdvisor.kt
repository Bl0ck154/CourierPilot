package com.block154.courierpilot

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs

internal data class PlatformOfferEconomics(
    val euroPerKilometer: Double?,
    val euroPerHourMin: Double?,
    val euroPerHourMax: Double?,
)

internal object PlatformOfferEconomicsCalculator {
    fun calculate(parsed: ParsedOffer): PlatformOfferEconomics {
        val euros = parsed.priceCents?.takeIf { it > 0 }?.div(100.0)
        val km = parsed.distanceMeters?.takeIf { it > 0 }?.div(1000.0)
        val minMinutes = parsed.estimatedMinutesMin?.takeIf { it > 0 }
        val maxMinutes = parsed.estimatedMinutesMax?.takeIf { it > 0 }
        val perKm = if (euros != null && km != null) euros / km else null
        val perHourMin = if (euros != null && maxMinutes != null) euros * 60.0 / maxMinutes else null
        val perHourMax = if (euros != null && minMinutes != null) euros * 60.0 / minMinutes else null
        return PlatformOfferEconomics(perKm, perHourMin, perHourMax)
    }
}

/**
 * Small TYPE_ACCESSIBILITY_OVERLAY card shown only after CourierPilot has persisted the original
 * offer screenshot. It never clicks, accepts, rejects or covers capture with its own UI.
 */
internal class LiveOfferAdvisor(
    private val service: AccessibilityService,
    private val onRouteToggleChanged: ((platform: String, enabled: Boolean) -> Unit)? = null,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: LinearLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var routeText: TextView? = null
    private var platformText: TextView? = null
    private var routeToggle: TextView? = null
    private var voiceToggle: TextView? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private var currentPlatform: String = ""
    private var dismissedCurrentOffer = false
    private var gestureStartWindowY = 0

    fun showBase(platform: String, parsed: ParsedOffer) {
        if (!LiveAdvisorSettings.enabled(service)) {
            hide()
            return
        }
        currentPlatform = platform
        dismissedCurrentOffer = false
        handler.post {
            ensureView()
            refreshControls()
            platformText?.text = formatBase(platform, parsed)
            val routeEnabled = LiveAdvisorSettings.routeEnabled(service, platform)
            routeText?.apply {
                visibility = if (routeEnabled) View.VISIBLE else View.GONE
                text = when {
                    !routeEnabled -> ""
                    platform.equals("Wolt", ignoreCase = true) -> "Route · resolving GPS + stops…"
                    platform.equals("Bolt", ignoreCase = true) -> "Route · locating pickup + map markers…"
                    else -> ""
                }
            }
            scheduleHide()
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
            routeText?.text = buildString {
                append("Route · $waypointCount points")
                pedestrian?.let { append("\n🟠 ${formatKm(it.distanceMeters)}") }
                    ?: append("\n🟠 failed")
                cycleway?.let { append("   🔵 ${formatKm(it.distanceMeters)}") }
                    ?: append("   🔵 failed")
            }
            scheduleHide()
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
                routeText?.text = "Route unavailable · ${outcome.failureReason?.take(100) ?: "unknown failure"}"
                scheduleHide()
                return@post
            }
            val pedestrian = comparison.pedestrian.getOrNull()
            val cycleway = comparison.cycleway.getOrNull()
            val scopeLabel = if (outcome.scope == BoltRouteScope.FULL) "Full route" else "To pickup"
            routeText?.text = buildString {
                append(scopeLabel)
                pedestrian?.let { append(" · 🟠 ${formatKm(it.distanceMeters)}") }
                cycleway?.let { append(" · 🔵 ${formatKm(it.distanceMeters)}") }
                if (outcome.scope == BoltRouteScope.PICKUP_ONLY) {
                    append("\nCustomer distance pending Bolt map marker")
                }
            }
            scheduleHide()
        }
    }

    fun updateRouteUnavailable(reason: String) {
        if (!LiveAdvisorSettings.enabled(service) || dismissedCurrentOffer) return
        handler.post {
            if (dismissedCurrentOffer) return@post
            ensureView()
            routeText?.visibility = View.VISIBLE
            routeText?.text = "Route unavailable · ${reason.take(100)}"
            scheduleHide()
        }
    }

    fun hide() {
        handler.removeCallbacks(hideRunnable)
        val view = root ?: return
        runCatching { windowManager.removeView(view) }
        root = null
        windowParams = null
        routeText = null
        platformText = null
        routeToggle = null
        voiceToggle = null
    }

    fun destroy() {
        hide()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        pendingSpeech = null
    }

    private fun dismissCurrentOffer() {
        dismissedCurrentOffer = true
        hide()
    }

    private fun ensureView() {
        if (root != null) return

        val container = object : LinearLayout(service) {
            private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop
            private var downRawX = 0f
            private var downRawY = 0f
            private var gestureMode = GESTURE_NONE

            override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        gestureMode = GESTURE_NONE
                        gestureStartWindowY = windowParams?.y ?: dp(DEFAULT_Y_DP)
                        return false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (gestureMode == GESTURE_NONE) {
                            val dx = event.rawX - downRawX
                            val dy = event.rawY - downRawY
                            if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                                gestureMode = if (abs(dx) > abs(dy)) GESTURE_HORIZONTAL else GESTURE_VERTICAL
                            }
                        }
                        return gestureMode != GESTURE_NONE
                    }
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL,
                    -> {
                        gestureMode = GESTURE_NONE
                        return false
                    }
                }
                return false
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> when (gestureMode) {
                        GESTURE_HORIZONTAL -> {
                            translationX = dx
                            alpha = (1f - abs(dx) / (width.coerceAtLeast(1) * 1.25f)).coerceIn(0.35f, 1f)
                        }
                        GESTURE_VERTICAL -> moveOverlayTo(gestureStartWindowY + dy.toInt())
                    }
                    MotionEvent.ACTION_UP -> {
                        when (gestureMode) {
                            GESTURE_HORIZONTAL -> {
                                val dismissThreshold = kotlin.math.max(
                                    dp(SWIPE_MIN_DP).toFloat(),
                                    width * SWIPE_DISMISS_FRACTION,
                                )
                                if (abs(dx) >= dismissThreshold) {
                                    dismissCurrentOffer()
                                } else {
                                    animate().translationX(0f).alpha(1f).setDuration(SNAP_BACK_MS).start()
                                }
                            }
                            GESTURE_VERTICAL -> persistOverlayY()
                        }
                        gestureMode = GESTURE_NONE
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        if (gestureMode == GESTURE_HORIZONTAL) {
                            animate().translationX(0f).alpha(1f).setDuration(SNAP_BACK_MS).start()
                        } else if (gestureMode == GESTURE_VERTICAL) {
                            persistOverlayY()
                        }
                        gestureMode = GESTURE_NONE
                    }
                }
                return true
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(9), dp(14), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.argb(242, 17, 24, 39))
                setStroke(dp(1), Color.argb(180, 75, 85, 99))
            }
            elevation = dp(10).toFloat()
        }

        val topRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(TextView(service).apply {
            text = "CourierPilot"
            setTextColor(Color.WHITE)
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        routeToggle = controlText(dp(7)).apply {
            setOnClickListener {
                if (currentPlatform.isBlank()) return@setOnClickListener
                val enabled = !LiveAdvisorSettings.routeEnabled(service, currentPlatform)
                LiveAdvisorSettings.setRouteEnabled(service, currentPlatform, enabled)
                refreshControls()
                routeText?.apply {
                    if (enabled) {
                        visibility = View.VISIBLE
                        text = "Route · starting…"
                    } else {
                        visibility = View.GONE
                        text = ""
                    }
                }
                onRouteToggleChanged?.invoke(currentPlatform, enabled)
                scheduleHide()
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
            setOnClickListener { dismissCurrentOffer() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        container.addView(topRow)

        platformText = TextView(service).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(5), 0, 0)
        }.also(container::addView)
        routeText = TextView(service).apply {
            setTextColor(Color.rgb(209, 213, 219))
            textSize = 11.5f
            setPadding(0, dp(5), 0, 0)
            visibility = View.GONE
        }.also(container::addView)

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
                        LiveAdvisorSettings.setOverlayYPx(service, clamped)
                    }
                }
            }
            .onFailure { windowParams = null }
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

    private fun scheduleHide() {
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, DISPLAY_MS)
    }

    private val hideRunnable = Runnable { hide() }

    private fun formatBase(platform: String, parsed: ParsedOffer): String {
        val economics = PlatformOfferEconomicsCalculator.calculate(parsed)
        val price = parsed.priceCents?.let { "€${"%.2f".format(Locale.US, it / 100.0)}" } ?: "€?"
        val eta = when {
            parsed.estimatedMinutesMin != null && parsed.estimatedMinutesMax != null && parsed.estimatedMinutesMin != parsed.estimatedMinutesMax ->
                "${parsed.estimatedMinutesMin}–${parsed.estimatedMinutesMax} min"
            parsed.estimatedMinutesMin != null -> "${parsed.estimatedMinutesMin} min"
            else -> null
        }
        val distance = parsed.distanceMeters?.let(::formatKm)
        val primary = buildList {
            add(platform.uppercase(Locale.ROOT))
            add(price)
            eta?.let(::add)
            distance?.let(::add)
        }.joinToString("  ·  ")
        val metrics = buildList {
            val lo = economics.euroPerHourMin
            val hi = economics.euroPerHourMax
            if (lo != null && hi != null) {
                add(if (kotlin.math.abs(lo - hi) < 0.05) "€${"%.1f".format(Locale.US, lo)}/h" else "€${"%.1f".format(Locale.US, lo)}–${"%.1f".format(Locale.US, hi)}/h")
            }
            economics.euroPerKilometer?.let { add("€${"%.2f".format(Locale.US, it)}/km") }
        }.joinToString("  ·  ")
        return if (metrics.isBlank()) primary else "$primary\n$metrics"
    }

    private fun baseSpeech(platform: String, parsed: ParsedOffer): String {
        val price = parsed.priceCents?.let { "${it / 100} euro ${it % 100}" } ?: "price unknown"
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
        private const val DISPLAY_MS = 28_000L
        private const val DEFAULT_Y_DP = 28
        private const val MIN_Y_DP = 8
        private const val BOTTOM_MARGIN_DP = 16
        private const val HORIZONTAL_MARGIN_DP = 8
        private const val SWIPE_MIN_DP = 72
        private const val SWIPE_DISMISS_FRACTION = 0.28f
        private const val SNAP_BACK_MS = 160L
        private const val GESTURE_NONE = 0
        private const val GESTURE_HORIZONTAL = 1
        private const val GESTURE_VERTICAL = 2
    }
}
