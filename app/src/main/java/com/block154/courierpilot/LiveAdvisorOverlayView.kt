package com.block154.courierpilot

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.abs

/**
 * Owns the Accessibility overlay view and its touch/drag presentation state.
 *
 * Offer lifetime, scoring, routing and cached decision state stay in [StableLiveOfferAdvisor]. This
 * class only renders values supplied by the advisor and reports explicit user dismissal/gesture-end
 * events back to it.
 */
internal class LiveAdvisorOverlayView(
    private val service: AccessibilityService,
    private val onDismiss: (String) -> Unit,
    private val onGestureFinished: () -> Unit,
    private val platformProvider: () -> String,
) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop

    private var root: LinearLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var decisionContainer: FrameLayout? = null
    private var decisionText: TextView? = null
    private var decisionSpinner: ProgressBar? = null
    private var routeText: TextView? = null
    private var captureSuppressed = false

    private var gestureDownX = 0f
    private var gestureDownY = 0f
    private var gestureStartY = 0
    private var gesturePendingY = 0
    private var gestureMode = GESTURE_NONE
    private var gestureTouchActive = false
    private var gestureStartedAtElapsed = 0L
    private var gestureLastMoveAtElapsed = 0L
    private var gestureMoveEvents = 0
    private var gestureMaxMoveGapMs = 0L
    private var gestureWindowRelayouts = 0
    private var gestureFrameMoveScheduled = false
    private var gestureLastWindowRelayoutAtElapsed = 0L

    private val gestureWindowMoveRunnable = object : Runnable {
        override fun run() {
            gestureFrameMoveScheduled = false
            val view = root ?: return
            if (!gestureTouchActive || gestureMode != GESTURE_VERTICAL) return
            val now = SystemClock.elapsedRealtime()
            val sinceLast = now - gestureLastWindowRelayoutAtElapsed
            if (sinceLast in 0 until GESTURE_WINDOW_RELAYOUT_MIN_INTERVAL_MS) {
                gestureFrameMoveScheduled = true
                view.postOnAnimationDelayed(this, GESTURE_WINDOW_RELAYOUT_MIN_INTERVAL_MS - sinceLast)
                return
            }
            applyPendingVerticalWindowPosition(view)
        }
    }

    val isAttached: Boolean
        get() = root != null

    val isGestureTouchActive: Boolean
        get() = gestureTouchActive

    fun ensure() {
        if (root != null) return

        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(5), dp(10), dp(7))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.argb(210, 15, 23, 36))
                setStroke(dp(1), Color.argb(105, 71, 85, 105))
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
            setOnClickListener { onDismiss("closed by user") }
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
            background = null
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
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
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
                    platform = platformProvider(),
                    message = "${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                )
            }
    }

    fun detach(animate: Boolean = true) {
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

    fun resetInteraction() {
        root?.removeCallbacks(gestureWindowMoveRunnable)
        gestureFrameMoveScheduled = false
        gestureTouchActive = false
    }

    fun applyDecision(line: String, band: OfferDecisionBand, loading: Boolean) {
        decisionSpinner?.visibility = if (loading) View.VISIBLE else View.GONE
        decisionText?.apply {
            visibility = if (loading) View.INVISIBLE else View.VISIBLE
            text = line
            setTextColor(decisionColor(band))
            when (band) {
                OfferDecisionBand.FIRE -> setShadowLayer(dp(5).toFloat(), 0f, 0f, Color.argb(210, 255, 112, 38))
                OfferDecisionBand.GOOD -> setShadowLayer(dp(3).toFloat(), 0f, 0f, Color.argb(120, 52, 211, 153))
                OfferDecisionBand.OK -> setShadowLayer(dp(2).toFloat(), 0f, 0f, Color.argb(75, 245, 158, 11))
                else -> setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            }
        }
        decisionContainer?.background = null
    }

    fun applyRoute(text: String, visible: Boolean) {
        routeText?.apply {
            visibility = if (visible) View.VISIBLE else View.INVISIBLE
            this.text = text
        }
    }

    fun setCaptureSuppressed(suppressed: Boolean) {
        if (!LiveAdvisorCapturePolicy.shouldSuppressOverlay(platformProvider())) {
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
                gesturePendingY = gestureStartY
                gestureMode = GESTURE_NONE
                gestureTouchActive = true
                gestureStartedAtElapsed = SystemClock.elapsedRealtime()
                gestureLastMoveAtElapsed = gestureStartedAtElapsed
                gestureMoveEvents = 0
                gestureMaxMoveGapMs = 0L
                gestureWindowRelayouts = 0
                gestureLastWindowRelayoutAtElapsed = 0L
                view?.removeCallbacks(gestureWindowMoveRunnable)
                gestureFrameMoveScheduled = false
                view?.animate()?.cancel()
                view?.translationX = 0f
                view?.translationY = 0f
                view?.alpha = 1f
            }
            MotionEvent.ACTION_MOVE -> {
                val now = SystemClock.elapsedRealtime()
                if (gestureMoveEvents > 0) {
                    gestureMaxMoveGapMs = maxOf(gestureMaxMoveGapMs, now - gestureLastMoveAtElapsed)
                }
                gestureLastMoveAtElapsed = now
                gestureMoveEvents++

                val dx = event.rawX - gestureDownX
                val dy = event.rawY - gestureDownY
                if (gestureMode == GESTURE_NONE) {
                    gestureMode = when (OverlayGestureAxisPolicy.classify(dx, dy, touchSlop)) {
                        OverlayGestureAxis.HORIZONTAL -> GESTURE_HORIZONTAL
                        OverlayGestureAxis.VERTICAL -> GESTURE_VERTICAL
                        null -> GESTURE_NONE
                    }
                }
                if (gestureMode == GESTURE_HORIZONTAL) {
                    view?.translationX = dx
                    view?.alpha = (1f - abs(dx) / ((view?.width ?: 1).coerceAtLeast(1) * 1.1f)).coerceIn(0.3f, 1f)
                } else if (gestureMode == GESTURE_VERTICAL && view != null) {
                    gesturePendingY = clampY(gestureStartY + dy.toInt(), view)
                    scheduleVerticalWindowMove(view)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dx = event.rawX - gestureDownX
                val dy = event.rawY - gestureDownY
                val mode = gestureMode
                view?.removeCallbacks(gestureWindowMoveRunnable)
                gestureFrameMoveScheduled = false
                gestureMode = GESTURE_NONE
                gestureTouchActive = false
                if (mode == GESTURE_HORIZONTAL) {
                    val threshold = maxOf(dp(SWIPE_MIN_DP).toFloat(), (view?.width ?: 1) * SWIPE_FRACTION)
                    if (event.actionMasked == MotionEvent.ACTION_UP && abs(dx) >= threshold) {
                        logGesturePerformance(mode, dy, event.actionMasked == MotionEvent.ACTION_CANCEL)
                        onGestureFinished()
                        onDismiss("swiped by user")
                        return true
                    }
                    view?.animate()?.translationX(0f)?.alpha(1f)?.setDuration(SNAP_BACK_MS)?.start()
                } else if (mode == GESTURE_VERTICAL) {
                    commitVerticalDrag(gesturePendingY)
                }
                logGesturePerformance(mode, dy, event.actionMasked == MotionEvent.ACTION_CANCEL)
                onGestureFinished()
            }
        }
        return true
    }

    private fun logGesturePerformance(mode: Int, dy: Float, cancelled: Boolean) {
        if (mode == GESTURE_NONE || gestureStartedAtElapsed <= 0L) return
        val durationMs = (SystemClock.elapsedRealtime() - gestureStartedAtElapsed).coerceAtLeast(0L)
        val axis = if (mode == GESTURE_VERTICAL) "vertical" else "horizontal"
        val distanceDp = (abs(dy) / service.resources.displayMetrics.density).toInt()
        CaptureEventLog.append(
            service,
            stage = "overlay_drag",
            platform = platformProvider(),
            message = "axis=$axis; duration_ms=$durationMs; moves=$gestureMoveEvents; max_move_gap_ms=$gestureMaxMoveGapMs; window_relayouts=$gestureWindowRelayouts; distance_dp=$distanceDp; cancelled=$cancelled",
            dedupeWindowMs = 250L,
        )
    }

    private fun scheduleVerticalWindowMove(view: View) {
        if (gestureFrameMoveScheduled) return
        gestureFrameMoveScheduled = true
        view.postOnAnimation(gestureWindowMoveRunnable)
    }

    private fun applyPendingVerticalWindowPosition(view: View? = root) {
        val targetView = view ?: return
        val params = windowParams ?: return
        val targetY = clampY(gesturePendingY, targetView)
        if (params.y == targetY) return
        params.y = targetY
        runCatching { windowManager.updateViewLayout(targetView, params) }
            .onSuccess {
                gestureWindowRelayouts += 1
                gestureLastWindowRelayoutAtElapsed = SystemClock.elapsedRealtime()
            }
    }

    private fun commitVerticalDrag(targetY: Int) {
        val view = root ?: return
        val params = windowParams ?: return
        val finalY = clampY(targetY, view)
        gesturePendingY = finalY
        if (params.y != finalY) applyPendingVerticalWindowPosition(view)
        view.translationY = 0f
        gestureStartY = finalY
        LiveAdvisorSettings.setOverlayYPx(service, finalY)
    }

    private fun clampY(targetY: Int, view: View): Int {
        val min = dp(MIN_Y_DP)
        val max = (service.resources.displayMetrics.heightPixels - view.height - dp(BOTTOM_MARGIN_DP)).coerceAtLeast(min)
        return targetY.coerceIn(min, max)
    }

    private fun decisionColor(band: OfferDecisionBand): Int = when (band) {
        OfferDecisionBand.FIRE -> Color.rgb(255, 139, 61)
        OfferDecisionBand.GOOD -> Color.rgb(110, 231, 183)
        OfferDecisionBand.OK -> Color.rgb(245, 190, 72)
        OfferDecisionBand.BAD -> Color.rgb(177, 143, 128)
        OfferDecisionBand.TERRIBLE -> Color.rgb(121, 132, 148)
        OfferDecisionBand.UNKNOWN -> Color.rgb(190, 200, 214)
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()

    private companion object {
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
        const val GESTURE_WINDOW_RELAYOUT_MIN_INTERVAL_MS = 16L
        const val GESTURE_NONE = 0
        const val GESTURE_HORIZONTAL = 1
        const val GESTURE_VERTICAL = 2
    }
}
