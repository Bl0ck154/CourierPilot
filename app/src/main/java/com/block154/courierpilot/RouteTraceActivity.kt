package com.block154.courierpilot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RouteTraceActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var latestText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var shareButton: Button

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 2_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        val screen = buildScreen()
        setContentView(screen)
        screen.applySystemBarsPadding()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            startTrace()
        } else {
            refreshStatus("Location permission is required to record a route trace.")
        }
    }

    private fun buildScreen(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(36))
        }

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(button("Back") { finish() })
            addView(LinearLayout(this@RouteTraceActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text("Ride trace", 24f, TEXT, true))
                addView(text("Explicit GPS recording for personal route learning", 12f, MUTED).top(dp(3)))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        })

        root.addView(card().apply {
            addView(text("How this works", 15f, TEXT, true))
            addView(text(
                "Start creates one local route-learning session and a visible foreground-service notification. You can leave CourierPilot while it records. Stop ends the session; the service is not silently restarted after a kill or reboot.",
                12f,
                MUTED,
            ).top(dp(6)))
            addView(text(
                "Default sampling request: about every 2 seconds / 2 meters. Points worse than ±80 m accuracy and extreme GPS jumps are ignored.",
                12f,
                MUTED,
            ).top(dp(7)))
        }.top(dp(20)))

        root.addView(card().apply {
            addView(text("Current recording", 15f, TEXT, true))
            statusText = text("Checking…", 13f, TEXT)
            addView(statusText.top(dp(7)))
            startButton = button("▶ Start ride trace") { requestStartTrace() }
            addView(startButton.top(dp(10)))
            stopButton = button("■ Stop trace") { stopTrace() }
            addView(stopButton.top(dp(4)))
        }.top(dp(12)))

        root.addView(card().apply {
            addView(text("Latest local trace", 15f, TEXT, true))
            latestText = text("No trace yet.", 13f, TEXT)
            addView(latestText.top(dp(7)))
            shareButton = button("Share latest as GeoJSON") { shareLatest() }
            addView(shareButton.top(dp(9)))
        }.top(dp(10)))

        root.addView(card().apply {
            addView(text("Privacy / scope", 15f, TEXT, true))
            addView(text(
                "Raw GPS samples stay in route_research.db. Recording begins only from this visible screen and remains visibly represented by Android's foreground-service notification. 0.11 does not upload traces or map-match them automatically.",
                12f,
                MUTED,
            ).top(dp(6)))
        }.top(dp(10)))

        scroll.addView(root)
        return scroll
    }

    private fun requestStartTrace() {
        if (!RouteResearchLocation.hasPermission(this)) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQUEST_LOCATION,
            )
            return
        }
        startTrace()
    }

    private fun startTrace() {
        val status = GpsTraceState.status(this)
        if (status.recording) {
            refreshStatus("A route trace is already recording.")
            return
        }
        runCatching {
            startForegroundService(Intent(this, GpsTraceService::class.java).setAction(GpsTraceService.ACTION_START))
        }.onFailure {
            refreshStatus("Could not start route trace: ${it.javaClass.simpleName}")
            return
        }
        refreshStatus("Starting route trace…")
    }

    private fun stopTrace() {
        runCatching {
            startService(Intent(this, GpsTraceService::class.java).setAction(GpsTraceService.ACTION_STOP))
        }
        refreshStatus("Stopping route trace…")
    }

    private fun refreshStatus(override: String? = null) {
        if (!::statusText.isInitialized) return
        val state = GpsTraceState.status(this)
        val status = override ?: when {
            state.recording -> buildString {
                append("RECORDING")
                state.startedAt?.let { append(" · started ${formatTime(it)}") }
                append("\n${state.sampleCount} points · ${"%.2f".format(Locale.US, state.distanceMeters / 1000.0)} km")
                state.lastSampleAt?.let { append(" · last ${secondsAgo(it)}s ago") }
            }
            state.stale -> "Previous recording stopped updating. Starting a new trace will close that stale DB session first."
            else -> "Not recording."
        }
        statusText.text = status
        statusText.setTextColor(if (state.recording) GREEN else if (state.stale) AMBER else TEXT)
        startButton.isEnabled = !state.recording
        stopButton.isEnabled = state.recording || state.stale

        val latest = RouteResearchDatabase.get(this).latestGpsSessionSummary()
        if (latest == null) {
            latestText.text = "No trace yet."
            shareButton.isEnabled = false
        } else {
            latestText.text = buildString {
                append("Session #${latest.sessionId} · ${formatDateTime(latest.startedAt)}")
                append("\n${latest.sampleCount} points · ${"%.2f".format(Locale.US, latest.distanceMeters / 1000.0)} km")
                latest.averageSpeedMetersPerSecond?.let {
                    append(" · avg ${"%.1f".format(Locale.US, it * 3.6)} km/h")
                }
                append(if (latest.endedAt == null) " · open" else " · finished")
            }
            shareButton.isEnabled = latest.sampleCount >= 2
        }
    }

    private fun shareLatest() {
        val db = RouteResearchDatabase.get(this)
        val id = db.latestGpsSessionId() ?: return
        val points = db.gpsSamples(id)
        if (points.size < 2) return
        val body = buildString {
            appendLine("CourierPilot GPS route trace #$id")
            appendLine("Samples: ${points.size}")
            appendLine()
            append(GpsTraceExport.geoJson(id, points))
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/geo+json"
            putExtra(Intent.EXTRA_SUBJECT, "CourierPilot route trace #$id")
            putExtra(Intent.EXTRA_TEXT, body)
        }, "Share private GPS trace"))
    }

    private fun secondsAgo(timestamp: Long): Long = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0L) / 1000L)

    private fun formatTime(timestamp: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    private fun formatDateTime(timestamp: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = rounded(Color.WHITE, BORDER, dp(18).toFloat())
        elevation = dp(1).toFloat()
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun button(label: String, click: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        setTextColor(BLUE)
        setOnClickListener { click() }
    }

    private fun rounded(fill: Int, stroke: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radius
        setStroke(dp(1), stroke)
    }

    private fun <T : View> T.top(value: Int): T {
        layoutParams = (layoutParams as? LinearLayout.LayoutParams)?.apply { topMargin = value }
            ?: LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = value }
        return this
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_LOCATION = 1713
        private val BG = Color.parseColor("#F5F7FB")
        private val TEXT = Color.parseColor("#111827")
        private val MUTED = Color.parseColor("#6B7280")
        private val BORDER = Color.parseColor("#E5E7EB")
        private val BLUE = Color.parseColor("#2563EB")
        private val GREEN = Color.parseColor("#15803D")
        private val AMBER = Color.parseColor("#D97706")
    }
}
