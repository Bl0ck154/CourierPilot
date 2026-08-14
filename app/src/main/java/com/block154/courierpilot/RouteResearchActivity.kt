package com.block154.courierpilot

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.Locale

/** Manual research harness. It is never called from the production offer-capture pipeline. */
class RouteResearchActivity : Activity() {

    private val executor = Executors.newSingleThreadExecutor()
    private var runningRequest: Future<*>? = null
    private lateinit var endpointField: EditText
    private lateinit var tokenField: EditText
    private lateinit var enabledSwitch: Switch
    private lateinit var fromLatField: EditText
    private lateinit var fromLonField: EditText
    private lateinit var toLatField: EditText
    private lateinit var toLonField: EditText
    private lateinit var runButton: Button
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(buildScreen().also { it.applySystemBarsPadding() })
    }

    override fun onDestroy() {
        runningRequest?.cancel(true)
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun buildScreen(): View {
        val config = RouteEndpointSettings.load(this)
        val scroll = ScrollView(this).apply { setBackgroundColor(BG) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(36))
        }

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(button("Back") { finish() })
            addView(LinearLayout(this@RouteResearchActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text("Route research", 24f, TEXT, true))
                addView(text("Compare stock Valhalla candidates", 12f, MUTED).top(dp(3)))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(10)
            })
        })

        root.addView(card().apply {
            addView(text("Research-only boundary", 15f, TEXT, true))
            addView(text(
                "Requests run only when you press Compare. CourierPilot offer capture never waits for routing, and no GPS/background location permission is used here.",
                12f,
                MUTED,
            ).top(dp(6)))
            addView(text(
                "Coordinates leave the phone for your self-hosted HTTPS endpoint. Results are experimental; generic ETA is not a scooter prediction.",
                12f,
                AMBER,
                true,
            ).top(dp(8)))
        }.top(dp(20)))

        root.addView(section("Protected endpoint", "Saved privately on this device and excluded from backup").top(dp(20)))
        root.addView(card().apply {
            endpointField = field("HTTPS base URL", config.baseUrl)
            addView(endpointField)
            tokenField = field("Bearer token", config.bearerToken).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                transformationMethod = PasswordTransformationMethod.getInstance()
            }
            addView(tokenField.top(dp(8)))
            enabledSwitch = Switch(this@RouteResearchActivity).apply {
                text = "Enable manual route research requests"
                isChecked = config.enabled
                setTextColor(TEXT)
            }
            addView(enabledSwitch.top(dp(10)))
            addView(button("Save endpoint") { saveEndpoint() }.top(dp(8)))
            addView(button("Clear token and disable") {
                RouteEndpointSettings.clear(this@RouteResearchActivity)
                endpointField.setText(RouteEndpointConfig.DEFAULT_BASE_URL)
                tokenField.setText("")
                enabledSwitch.isChecked = false
                showStatus("Endpoint configuration cleared.", false)
            }.top(dp(5)))
        }.top(dp(8)))

        root.addView(section("Vilnius comparison", "Manual start/end coordinates; no automatic location access").top(dp(20)))
        root.addView(card().apply {
            fromLatField = coordinateField("Start latitude", "54.6872")
            fromLonField = coordinateField("Start longitude", "25.2797")
            toLatField = coordinateField("End latitude", "54.7005")
            toLonField = coordinateField("End longitude", "25.3030")
            addView(fromLatField)
            addView(fromLonField.top(dp(7)))
            addView(toLatField.top(dp(7)))
            addView(toLonField.top(dp(7)))

            runButton = button("Compare pedestrian and bicycle") { runComparison() }
            addView(runButton.top(dp(12)))
            statusText = text("Ready. Endpoint requests are disabled until explicitly enabled above.", 12f, MUTED)
            addView(statusText.top(dp(10)))
        }.top(dp(8)))

        root.addView(section("Result", "Distance and geometry are the primary research signals").top(dp(20)))
        resultText = text("No comparison run yet.", 12f, TEXT).apply { setTextIsSelectable(true) }
        root.addView(card().apply { addView(resultText) }.top(dp(8)))

        scroll.addView(root)
        return scroll
    }

    private fun saveEndpoint() {
        val candidate = RouteEndpointConfig(
            enabled = enabledSwitch.isChecked,
            baseUrl = endpointField.text.toString(),
            bearerToken = tokenField.text.toString(),
        )
        runCatching { RouteEndpointSettings.save(this, candidate) }
            .onSuccess {
                val saved = RouteEndpointSettings.load(this)
                endpointField.setText(saved.baseUrl)
                tokenField.setText(saved.bearerToken)
                showStatus(if (saved.enabled) "Protected endpoint enabled." else "Endpoint saved but requests remain disabled.", false)
            }
            .onFailure { showStatus(it.message ?: "Could not save endpoint.", true) }
    }

    private fun runComparison() {
        if (runningRequest?.isDone == false) return
        val config = runCatching { RouteEndpointSettings.load(this).validated() }
            .getOrElse {
                showStatus(it.message ?: "Configure and enable the endpoint first.", true)
                return
            }
        val points = runCatching {
            listOf(
                RoutePoint(parseCoordinate(fromLatField, "start latitude"), parseCoordinate(fromLonField, "start longitude")),
                RoutePoint(parseCoordinate(toLatField, "end latitude"), parseCoordinate(toLonField, "end longitude")),
            ).also { RouteIntelligencePolicy.validate(RouteRequest(it, RouteProfile.PEDESTRIAN_SHORTCUT)) }
        }.getOrElse {
            showStatus(it.message ?: "Invalid coordinates.", true)
            return
        }

        runButton.isEnabled = false
        showStatus("Requesting both candidates…", false)
        resultText.text = "Waiting for Valhalla…"
        runningRequest = executor.submit {
            val provider = ValhallaRouteProvider(config)
            val results = RouteProfile.entries.map { profile ->
                profile to provider.route(RouteRequest(points, profile))
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                runButton.isEnabled = true
                resultText.text = results.joinToString("\n\n") { (profile, result) ->
                    formatResult(profile, result)
                }
                val succeeded = results.count { it.second.isSuccess }
                showStatus("$succeeded/${results.size} candidates returned successfully.", succeeded != results.size)
            }
        }
    }

    private fun formatResult(profile: RouteProfile, result: Result<RouteResult>): String {
        val label = when (profile) {
            RouteProfile.PEDESTRIAN_SHORTCUT -> "Candidate A — pedestrian shortcut"
            RouteProfile.CYCLEWAY_BIASED -> "Candidate B — cycleway biased"
        }
        return result.fold(
            onSuccess = { route ->
                val distanceKm = route.distanceMeters / 1_000.0
                val durationMinutes = route.durationSeconds / 60.0
                val warnings = route.warnings.ifEmpty { listOf("none") }.joinToString("; ")
                val shapes = route.legShapes.ifEmpty { listOf("<missing>") }.joinToString("\n")
                buildString {
                    appendLine(label)
                    appendLine("HTTP: ${route.httpStatus ?: "unknown"}")
                    appendLine("Distance: ${"%.3f".format(Locale.US, distanceKm)} km")
                    appendLine("Generic ETA: ${"%.2f".format(Locale.US, durationMinutes)} min")
                    appendLine("Warnings: $warnings")
                    append("Encoded polyline(s):\n$shapes")
                }
            },
            onFailure = { failure -> "$label\nFailed: ${failure.message ?: failure.javaClass.simpleName}" },
        )
    }

    private fun parseCoordinate(field: EditText, label: String): Double =
        field.text.toString().trim().toDoubleOrNull() ?: error("Invalid $label")

    private fun showStatus(message: String, error: Boolean) {
        statusText.text = message
        statusText.setTextColor(if (error) RED else MUTED)
    }

    private fun coordinateField(hint: String, value: String) = field(hint, value).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
    }

    private fun field(hint: String, value: String): EditText = EditText(this).apply {
        this.hint = hint
        setText(value)
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        setSingleLine(true)
        textSize = 14f
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(Color.WHITE, BORDER, dp(10).toFloat())
    }

    private fun button(label: String, click: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        setTextColor(BLUE)
        setOnClickListener { click() }
    }

    private fun section(title: String, subtitle: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(title, 18f, TEXT, true))
        addView(text(subtitle, 12f, MUTED).top(dp(3)))
    }

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

    private fun rounded(fill: Int, stroke: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radius
        setStroke(dp(1), stroke)
    }

    private fun <T : View> T.top(value: Int): T {
        layoutParams = (layoutParams as? LinearLayout.LayoutParams)?.apply { topMargin = value }
            ?: LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = value }
        return this
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val BG = Color.parseColor("#F5F7FB")
        private val TEXT = Color.parseColor("#111827")
        private val MUTED = Color.parseColor("#6B7280")
        private val BORDER = Color.parseColor("#E5E7EB")
        private val BLUE = Color.parseColor("#2563EB")
        private val AMBER = Color.parseColor("#D97706")
        private val RED = Color.parseColor("#DC2626")
    }
}
