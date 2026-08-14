package com.block154.courierpilot

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
import androidx.core.content.FileProvider
import java.util.ArrayList
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Manual route-validation harness. Production offer capture never waits for this screen. */
class RouteResearchActivity : Activity() {

    private val executor = Executors.newSingleThreadExecutor()
    private var runningRequest: Future<*>? = null
    private var currentComparison: RouteComparison? = null
    private var currentStart: RoutePoint? = null
    private var currentEnd: RoutePoint? = null

    private lateinit var endpointField: EditText
    private lateinit var tokenField: EditText
    private lateinit var enabledSwitch: Switch
    private lateinit var fromLatField: EditText
    private lateinit var fromLonField: EditText
    private lateinit var toLatField: EditText
    private lateinit var toLonField: EditText
    private lateinit var destinationAddressField: EditText
    private lateinit var runButton: Button
    private lateinit var statusText: TextView
    private lateinit var resultText: TextView
    private lateinit var previewView: RoutePreviewView
    private lateinit var notesField: EditText
    private lateinit var validationStatusText: TextView
    private lateinit var boltSampleStatusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(buildScreen().also { it.applySystemBarsPadding() })
    }

    override fun onResume() {
        super.onResume()
        if (::boltSampleStatusText.isInitialized) refreshBoltSampleStatus()
    }

    override fun onDestroy() {
        runningRequest?.cancel(true)
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) useCurrentLocation()
            else showStatus("Location permission was not granted.", true)
        }
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
                addView(text("Real Vilnius route validation", 12f, MUTED).top(dp(3)))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        })

        root.addView(card().apply {
            addView(text("How to test", 15f, TEXT, true))
            addView(text(
                "1. Save the Valhalla token once. 2. Tap Use my location. 3. Enter a destination address or coordinates. 4. Compare. 5. Mark which candidate you would actually ride.",
                12f, MUTED,
            ).top(dp(6)))
            addView(text(
                "Orange = pedestrian shortcut; blue = cycleway-biased. The preview is geometry-only, so use your local knowledge when rating it.",
                12f, AMBER, true,
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
                text = "Enable route research requests"
                isChecked = config.enabled
                setTextColor(TEXT)
            }
            addView(enabledSwitch.top(dp(10)))
            addView(button("Save endpoint") { saveEndpoint() }.top(dp(8)))
        }.top(dp(8)))

        root.addView(section("Start", "Use a fresh phone fix instead of typing latitude/longitude").top(dp(20)))
        root.addView(card().apply {
            fromLatField = coordinateField("Start latitude", "54.6872")
            fromLonField = coordinateField("Start longitude", "25.2797")
            addView(fromLatField)
            addView(fromLonField.top(dp(7)))
            addView(button("📍 Use my current location") { useCurrentLocation() }.top(dp(9)))
        }.top(dp(8)))

        root.addView(section("Destination", "Type an address you know or paste coordinates").top(dp(20)))
        root.addView(card().apply {
            destinationAddressField = field("Vilnius address, e.g. Gedimino pr. 9", "")
            addView(destinationAddressField)
            addView(button("Resolve address to coordinates") { geocodeDestination() }.top(dp(7)))
            toLatField = coordinateField("End latitude", "54.7005")
            toLonField = coordinateField("End longitude", "25.3030")
            addView(toLatField.top(dp(10)))
            addView(toLonField.top(dp(7)))
            runButton = button("Compare pedestrian vs cycleway") { runComparison() }
            addView(runButton.top(dp(12)))
            statusText = text("Ready. ${RouteResearchDatabase.get(this@RouteResearchActivity).comparisonCount()} route comparisons saved locally.", 12f, MUTED)
            addView(statusText.top(dp(10)))
        }.top(dp(8)))

        root.addView(section("Route shape", "Geometry preview; start/end are black dots").top(dp(20)))
        previewView = RoutePreviewView(this)
        root.addView(card().apply { addView(previewView) }.top(dp(8)))

        root.addView(section("Result", "Distance is the primary signal; Valhalla ETA is still generic").top(dp(20)))
        resultText = text("No comparison run yet.", 12f, TEXT).apply { setTextIsSelectable(true) }
        root.addView(card().apply {
            addView(resultText)
            addView(button("Share comparison as GeoJSON") { shareComparison() }.top(dp(10)))
        }.top(dp(8)))

        root.addView(section("Your verdict", "This creates the real Vilnius validation corpus").top(dp(20)))
        root.addView(card().apply {
            notesField = field("Optional note: stairs, useless detour, shortcut…", "").apply { setSingleLine(false); minLines = 2 }
            addView(notesField)
            addView(button("🟠 Pedestrian is better") { saveVerdict(RouteComparisonVerdict.PEDESTRIAN_BETTER) }.top(dp(8)))
            addView(button("🔵 Cycleway is better") { saveVerdict(RouteComparisonVerdict.CYCLEWAY_BETTER) }.top(dp(3)))
            addView(button("Both are usable") { saveVerdict(RouteComparisonVerdict.BOTH_OK) }.top(dp(3)))
            addView(button("Both are bad") { saveVerdict(RouteComparisonVerdict.BOTH_BAD) }.top(dp(3)))
            validationStatusText = text("Run a comparison before saving a verdict.", 12f, MUTED)
            addView(validationStatusText.top(dp(8)))
        }.top(dp(8)))

        root.addView(section("Bolt map sample", "One arm captures tree + screenshot + available cached phone GPS").top(dp(20)))
        root.addView(card().apply {
            boltSampleStatusText = text("", 12f, MUTED)
            addView(boltSampleStatusText)
            addView(button("Open Android Accessibility settings") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.top(dp(8)))
            addView(button("Arm next Bolt offer/map screen") {
                BoltAccessibilityDiagnostics.arm(this@RouteResearchActivity)
                refreshBoltSampleStatus()
            }.top(dp(4)))
            addView(button("Disarm") {
                BoltAccessibilityDiagnostics.disarm(this@RouteResearchActivity)
                refreshBoltSampleStatus()
            }.top(dp(3)))
            addView(button("Share full Bolt sample") { shareBoltSample() }.top(dp(3)))
            addView(button("Clear Bolt sample") {
                BoltAccessibilityDiagnostics.clear(this@RouteResearchActivity)
                refreshBoltSampleStatus()
            }.top(dp(3)))
            addView(text(
                "For GPS metadata, grant location once with Use my current location. The Bolt research service only reads the best cached fix; it does not start background tracking.",
                11f, MUTED,
            ).top(dp(8)))
        }.top(dp(8)))
        refreshBoltSampleStatus()

        scroll.addView(root)
        return scroll
    }

    private fun saveEndpoint() {
        val candidate = RouteEndpointConfig(enabledSwitch.isChecked, endpointField.text.toString(), tokenField.text.toString())
        runCatching { RouteEndpointSettings.save(this, candidate) }
            .onSuccess {
                val saved = RouteEndpointSettings.load(this)
                endpointField.setText(saved.baseUrl)
                tokenField.setText(saved.bearerToken)
                showStatus(if (saved.enabled) "Protected endpoint enabled." else "Endpoint saved but disabled.", false)
            }
            .onFailure { showStatus(it.message ?: "Could not save endpoint.", true) }
    }

    private fun useCurrentLocation() {
        if (!RouteResearchLocation.hasPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION)
            return
        }
        showStatus("Getting current location…", false)
        RouteResearchLocation.requestCurrent(this) { result ->
            result.onSuccess { fix ->
                fromLatField.setText(String.format(Locale.US, "%.7f", fix.point.latitude))
                fromLonField.setText(String.format(Locale.US, "%.7f", fix.point.longitude))
                showStatus("Location: ±${fix.accuracyMeters?.toInt() ?: "?"} m · ${fix.provider}", false)
            }.onFailure { showStatus(it.message ?: "Could not obtain location.", true) }
        }
    }

    private fun geocodeDestination() {
        showStatus("Resolving destination address…", false)
        RouteResearchGeocoder.resolve(this, destinationAddressField.text.toString()) { result ->
            result.onSuccess { point ->
                toLatField.setText(String.format(Locale.US, "%.7f", point.latitude))
                toLonField.setText(String.format(Locale.US, "%.7f", point.longitude))
                showStatus("Address resolved. Ready to compare.", false)
            }.onFailure { showStatus(it.message ?: "Could not resolve address.", true) }
        }
    }

    private fun runComparison() {
        if (runningRequest?.isDone == false) return
        val config = runCatching { RouteEndpointSettings.load(this).validated() }.getOrElse {
            showStatus(it.message ?: "Configure and enable the endpoint first.", true); return
        }
        val points = runCatching {
            listOf(
                RoutePoint(parseCoordinate(fromLatField, "start latitude"), parseCoordinate(fromLonField, "start longitude")),
                RoutePoint(parseCoordinate(toLatField, "end latitude"), parseCoordinate(toLonField, "end longitude")),
            ).also { RouteIntelligencePolicy.validate(RouteRequest(it, RouteProfile.PEDESTRIAN_SHORTCUT)) }
        }.getOrElse { showStatus(it.message ?: "Invalid coordinates.", true); return }

        runButton.isEnabled = false
        showStatus("Requesting both candidates…", false)
        resultText.text = "Waiting for Valhalla…"
        previewView.setRoutes(null, null)
        runningRequest = executor.submit {
            val comparison = RouteComparisonEngine(ValhallaRouteProvider(config)).compare(points)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                runButton.isEnabled = true
                currentComparison = comparison
                currentStart = points.first()
                currentEnd = points.last()
                previewView.setRoutes(comparison.pedestrian.getOrNull(), comparison.cycleway.getOrNull())
                resultText.text = formatComparison(comparison)
                validationStatusText.text = "Choose the route you would actually ride."
                val succeeded = listOf(comparison.pedestrian, comparison.cycleway).count { it.isSuccess }
                showStatus("$succeeded/2 candidates returned successfully.", succeeded != 2)
            }
        }
    }

    private fun saveVerdict(verdict: RouteComparisonVerdict) {
        val comparison = currentComparison ?: run { validationStatusText.text = "Run a comparison first."; return }
        val start = currentStart ?: return
        val end = currentEnd ?: return
        val id = RouteResearchDatabase.get(this).recordComparison(start, end, comparison, verdict, notesField.text.toString())
        validationStatusText.text = "Saved validation #$id · ${verdict.name.lowercase().replace('_', ' ')}"
        notesField.setText("")
    }

    private fun shareComparison() {
        val comparison = currentComparison ?: run { showStatus("Run a comparison first.", true); return }
        val body = buildString {
            appendLine("CourierPilot route research")
            currentStart?.let { appendLine("Start: ${it.latitude},${it.longitude}") }
            currentEnd?.let { appendLine("End: ${it.latitude},${it.longitude}") }
            appendLine(formatComparison(comparison))
            appendLine(); appendLine("GeoJSON:"); append(RoutePolyline.comparisonGeoJson(comparison))
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "CourierPilot route comparison")
            putExtra(Intent.EXTRA_TEXT, body)
        }, "Share route comparison"))
    }

    private fun refreshBoltSampleStatus() {
        val armed = BoltAccessibilityDiagnostics.isArmed(this)
        val sample = BoltAccessibilityDiagnostics.summary(this)
        boltSampleStatusText.text = buildString {
            append(if (armed) "ARMED — switch to Bolt and wait for the offer/map screen." else "Not armed.")
            if (sample != null) {
                append("\nLast sample: ${sample.nodeCount} nodes")
                if (sample.truncated) append(" · tree truncated")
                append(if (sample.screenshotAvailable) " · screenshot ✓" else " · screenshot missing")
                append(if (sample.locationAvailable) " · GPS ✓") else append(" · GPS missing")
                sample.locationAgeMillis?.let { append(" (${it / 1000}s old)") }
            } else append("\nNo saved Bolt sample yet.")
        }
        boltSampleStatusText.setTextColor(if (armed) AMBER else MUTED)
    }

    private fun shareBoltSample() {
        val files = BoltAccessibilityDiagnostics.sampleFiles(this)
        if (files.isEmpty()) {
            boltSampleStatusText.text = "No Bolt sample to share yet."
            return
        }
        val uris = ArrayList<Uri>()
        files.forEach { file -> uris += FileProvider.getUriForFile(this, "$packageName.researchfiles", file) }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, "CourierPilot Bolt research sample")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(contentResolver, "CourierPilot Bolt research", uris.first()).also { clip ->
                uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            }
        }
        startActivity(Intent.createChooser(intent, "Share private Bolt research sample"))
    }

    private fun formatComparison(comparison: RouteComparison): String = listOf(
        RouteProfile.PEDESTRIAN_SHORTCUT to comparison.pedestrian,
        RouteProfile.CYCLEWAY_BIASED to comparison.cycleway,
    ).joinToString("\n\n") { (profile, result) -> formatResult(profile, result) }

    private fun formatResult(profile: RouteProfile, result: Result<RouteResult>): String {
        val label = if (profile == RouteProfile.PEDESTRIAN_SHORTCUT) "🟠 Pedestrian shortcut" else "🔵 Cycleway biased"
        return result.fold(
            onSuccess = { route ->
                val distanceKm = route.distanceMeters / 1_000.0
                val durationMinutes = route.durationSeconds / 60.0
                val warnings = route.warnings.ifEmpty { listOf("none") }.joinToString("; ")
                val decodedPoints = RoutePolyline.decodeRoute(route).size
                "$label\nDistance: ${"%.3f".format(Locale.US, distanceKm)} km\nGeneric ETA: ${"%.1f".format(Locale.US, durationMinutes)} min\nShape points: $decodedPoints\nWarnings: $warnings"
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
        this.hint = hint; setText(value); setTextColor(TEXT); setHintTextColor(MUTED); setSingleLine(true); textSize = 14f
        setPadding(dp(12), dp(10), dp(12), dp(10)); background = rounded(Color.WHITE, BORDER, dp(10).toFloat())
    }

    private fun button(label: String, click: () -> Unit): Button = Button(this).apply {
        text = label; textSize = 12f; isAllCaps = false; setTextColor(BLUE); setOnClickListener { click() }
    }

    private fun section(title: String, subtitle: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; addView(text(title, 18f, TEXT, true)); addView(text(subtitle, 12f, MUTED).top(dp(3)))
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(15), dp(16), dp(15))
        background = rounded(Color.WHITE, BORDER, dp(18).toFloat()); elevation = dp(1).toFloat()
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); includeFontPadding = false
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun rounded(fill: Int, stroke: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(fill); cornerRadius = radius; setStroke(dp(1), stroke)
    }

    private fun <T : View> T.top(value: Int): T {
        layoutParams = (layoutParams as? LinearLayout.LayoutParams)?.apply { topMargin = value }
            ?: LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = value }
        return this
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_LOCATION = 41
        private val BG = Color.parseColor("#F5F7FB")
        private val TEXT = Color.parseColor("#111827")
        private val MUTED = Color.parseColor("#6B7280")
        private val BORDER = Color.parseColor("#E5E7EB")
        private val BLUE = Color.parseColor("#2563EB")
        private val AMBER = Color.parseColor("#D97706")
        private val RED = Color.parseColor("#DC2626")
    }
}
