package com.block154.courierpilot

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.AbsoluteSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.VelocityTracker
import android.view.WindowInsets
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
    private val obstacleFinder = OverlayObstacleFinder(service)
    private var screenHeightPx = 0
    private var topInsetPx = 0

    private var root: LinearLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var decisionContainer: FrameLayout? = null
    private var decisionText: TextView? = null
    private var decisionSpinner: ProgressBar? = null
    private var routeText: TextView? = null
    private var debugText: TextView? = null
    private var captureSuppressed = false
    private var velocityTracker: VelocityTracker? = null

    var isSwipeExitRunning: Boolean = false
        private set

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

    /** Actual window coordinates; excludes detached and not-yet-laid-out views. */
    fun screenRect(): Rect? {
        val view = root ?: return null
        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return null
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    fun applyDebugLines(lines: List<String>) {
        debugText?.apply {
            text = lines.filter { it.isNotBlank() }.take(3).joinToString("\n")
            visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    fun ensure() {
        if (root != null) {
            refreshWidth()
            return
        }
        screenHeightPx = screenHeight()
        topInsetPx = topInset()
        obstacleFinder.reset()

        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.argb(210, 15, 23, 36))
                setStroke(dp(1), Color.argb(105, 71, 85, 105))
            }
            elevation = dp(9).toFloat()
        }
        installGestureSurface(container)

        // The old title/header row wasted a full line above every offer.
        val body = FrameLayout(service)
        val mainRow = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(CLOSE_TOUCH_DP), 0)
        }
        installGestureSurface(mainRow)

        routeText = TextView(service).apply {
            setTextColor(Color.rgb(190, 200, 214))
            textSize = 11f
            includeFontPadding = false
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            maxLines = 2
        }.also { view ->
            installGestureSurface(view)
            mainRow.addView(
                view,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(3)
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
            setPadding(dp(1), 0, dp(1), 0)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            setAutoSizeTextTypeUniformWithConfiguration(
                16, 24, 1, TypedValue.COMPLEX_UNIT_SP,
            )
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
                FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER),
            )
        }

        mainRow.addView(
            rateFrame,
            LinearLayout.LayoutParams(0, dp(RATE_MIN_HEIGHT_DP), 0.55f),
        )
        body.addView(
            mainRow,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        body.addView(
            TextView(service).apply {
                text = "×"
                setTextColor(Color.rgb(148, 163, 184))
                textSize = 14f
                includeFontPadding = false
                gravity = Gravity.CENTER
                contentDescription = "Close live advisor"
                setOnClickListener { onDismiss("closed by user") }
            },
            FrameLayout.LayoutParams(dp(CLOSE_TOUCH_DP), dp(CLOSE_TOUCH_DP), Gravity.TOP or Gravity.END),
        )
        container.addView(body)

        debugText = TextView(service).apply {
            textSize = 8.5f
            includeFontPadding = false
            typeface = Typeface.MONOSPACE
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Color.rgb(148, 163, 184))
            visibility = View.GONE
        }.also { container.addView(it) }

        val screenWidth = screenWidth()
        val params = WindowManager.LayoutParams(
            OverlayGeometryPolicy.widthPx(screenWidth, service.resources.displayMetrics.density),
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
            y = LiveAdvisorSettings.overlayYPx(service) ?: OverlayGeometryPolicy.defaultYPx(
                obstacleFinder.topClickableBottomPx(screenHeightPx),
                topInsetPx,
                screenHeightPx,
                service.resources.displayMetrics.density,
            )
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
                debugText = null
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
        // WS-A may hide the session synchronously inside onDismiss. Never cancel the
        // exit animation or snap the just-swiped window back onto the display.
        if (isSwipeExitRunning) return
        val view = root
        root = null
        windowParams = null
        decisionContainer = null
        decisionText = null
        decisionSpinner = null
        routeText = null
        debugText = null
        gestureMode = GESTURE_NONE
        gestureTouchActive = false
        velocityTracker?.recycle()
        velocityTracker = null
        captureSuppressed = false
        obstacleFinder.reset()
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
        val routeLine = text
        routeText?.apply {
            visibility = if (visible) View.VISIBLE else View.INVISIBLE
            if (DeveloperModeSettings.enabled(service) && visible) {
                val suffix = " · v${BuildConfig.VERSION_NAME}"
                this.text = SpannableString(routeLine + suffix).apply {
                    setSpan(
                        AbsoluteSizeSpan(8, true),
                        routeLine.length,
                        length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            } else {
                this.text = routeLine
            }
        }
    }

    fun setCaptureSuppressed(suppressed: Boolean) {
        // A screenshot callback can arrive in the middle of the courier's finger movement.
        if (gestureTouchActive || isSwipeExitRunning) return
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
        if (isSwipeExitRunning) return true
        val view = root
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                recordRawMotion(event)
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
                recordRawMotion(event)
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
                recordRawMotion(event)
                velocityTracker?.computeCurrentVelocity(1000)
                val horizontalVelocity = velocityTracker?.xVelocity ?: 0f
                velocityTracker?.recycle()
                velocityTracker = null
                val dx = event.rawX - gestureDownX
                val dy = event.rawY - gestureDownY
                val mode = gestureMode
                view?.removeCallbacks(gestureWindowMoveRunnable)
                gestureFrameMoveScheduled = false
                gestureMode = GESTURE_NONE
                gestureTouchActive = false
                if (mode == GESTURE_HORIZONTAL) {
                    if (event.actionMasked == MotionEvent.ACTION_UP && view != null &&
                        OverlaySwipePolicy.shouldDismiss(
                            dx, horizontalVelocity, view.width, service.resources.displayMetrics.density,
                        )
                    ) {
                        logGesturePerformance(mode, dy, cancelled = false)
                        isSwipeExitRunning = true
                        // The window remains attached until the exit animation has finished.
                        val direction = if (dx >= 0f) 1f else -1f
                        view.animate().cancel()
                        view.animate()
                            .translationX(direction * (view.width + dp(SWIPE_EXIT_MARGIN_DP)))
                            .alpha(0f)
                            .setInterpolator(DecelerateInterpolator())
                            .setDuration(SWIPE_EXIT_MS)
                            .withEndAction { finishSwipeExit(view) }
                            .start()
                        onGestureFinished()
                        onDismiss("swiped by user")
                        return true
                    }
                    view?.animate()
                        ?.translationX(0f)
                        ?.alpha(1f)
                        ?.setInterpolator(DecelerateInterpolator())
                        ?.setDuration(SNAP_BACK_MS)
                        ?.start()
                } else if (mode == GESTURE_VERTICAL) {
                    commitVerticalDrag(gesturePendingY)
                }
                logGesturePerformance(mode, dy, event.actionMasked == MotionEvent.ACTION_CANCEL)
                onGestureFinished()
            }
        }
        return true
    }

    private fun recordRawMotion(event: MotionEvent) {
        // Raw coordinates are stable even while translationX moves the touched view.
        val raw = MotionEvent.obtain(event)
        raw.setLocation(event.rawX, event.rawY)
        velocityTracker?.addMovement(raw)
        raw.recycle()
    }

    private fun finishSwipeExit(view: View) {
        if (!isSwipeExitRunning) return
        isSwipeExitRunning = false
        if (root === view) {
            detach(animate = false)
        } else {
            runCatching { windowManager.removeView(view) }
        }
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
        val min = topInsetPx + dp(4)
        val max = (screenHeightPx - view.height - dp(BOTTOM_MARGIN_DP)).coerceAtLeast(min)
        return targetY.coerceIn(min, max)
    }

    private fun screenWidth(): Int = runCatching {
        if (Build.VERSION.SDK_INT >= 30) windowManager.currentWindowMetrics.bounds.width()
        else service.resources.displayMetrics.widthPixels
    }.getOrDefault(service.resources.displayMetrics.widthPixels).coerceAtLeast(1)

    private fun screenHeight(): Int = runCatching {
        if (Build.VERSION.SDK_INT >= 30) windowManager.currentWindowMetrics.bounds.height()
        else service.resources.displayMetrics.heightPixels
    }.getOrDefault(service.resources.displayMetrics.heightPixels).coerceAtLeast(1)

    private fun topInset(): Int {
        val measured = runCatching {
            if (Build.VERSION.SDK_INT >= 30) {
                windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout(),
                ).top
            } else 0
        }.getOrDefault(0)
        if (measured > 0) return measured
        val statusBarId = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (statusBarId != 0) {
            runCatching { service.resources.getDimensionPixelSize(statusBarId) }.getOrDefault(0)
        } else 0
    }

    private fun refreshWidth() {
        if (gestureTouchActive || isSwipeExitRunning) return
        val view = root ?: return
        val params = windowParams ?: return
        val width = OverlayGeometryPolicy.widthPx(
            screenWidth(), service.resources.displayMetrics.density,
        )
        if (params.width == width) return
        params.width = width
        screenHeightPx = screenHeight()
        topInsetPx = topInset()
        runCatching { windowManager.updateViewLayout(view, params) }
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
        const val BOTTOM_MARGIN_DP = 16
        const val CLOSE_TOUCH_DP = 32
        const val RATE_MIN_WIDTH_DP = 112
        const val RATE_MIN_HEIGHT_DP = 36
        const val SWIPE_EXIT_MARGIN_DP = 24
        const val SWIPE_EXIT_MS = 160L
        const val SNAP_BACK_MS = 180L
        const val GESTURE_WINDOW_RELAYOUT_MIN_INTERVAL_MS = 16L
        const val GESTURE_NONE = 0
        const val GESTURE_HORIZONTAL = 1
        const val GESTURE_VERTICAL = 2
    }
}
