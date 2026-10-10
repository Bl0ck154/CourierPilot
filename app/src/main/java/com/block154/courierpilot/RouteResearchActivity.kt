package com.block154.courierpilot

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.block154.courierpilot.ui.ActionRow
import com.block154.courierpilot.ui.CourierPilotTheme
import com.block154.courierpilot.ui.CourierPilotToggleRow
import com.block154.courierpilot.ui.DetailHeader
import com.block154.courierpilot.ui.Footnote
import com.block154.courierpilot.ui.GroupedBlock
import com.block154.courierpilot.ui.GroupedRow
import com.block154.courierpilot.ui.LocalCourierPalette
import com.block154.courierpilot.ui.SectionLabel
import com.block154.courierpilot.ui.SettingsDivider
import com.block154.courierpilot.ui.SettingsGroup
import java.util.ArrayList
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Manual route-validation harness. Production offer capture never waits for this screen. */
class RouteResearchActivity : ComponentActivity() {

    private val executor = Executors.newSingleThreadExecutor()
    private var runningRequest: Future<*>? = null
    private var currentComparison: RouteComparison? = null
    private var currentStart: RoutePoint? = null
    private var currentEnd: RoutePoint? = null

    // Form and status state. Logic below reads/writes these exactly where the View version used
    // EditText/TextView; Compose only renders them.
    private var endpointUrl by mutableStateOf("")
    private var token by mutableStateOf("")
    private var requestsEnabled by mutableStateOf(false)
    private var endpointStatus by mutableStateOf<Pair<String, ResearchTone>>("" to ResearchTone.MUTED)
    private var fromLat by mutableStateOf("54.6872")
    private var fromLon by mutableStateOf("25.2797")
    private var toLat by mutableStateOf("54.7005")
    private var toLon by mutableStateOf("25.3030")
    private var destinationAddress by mutableStateOf("")
    private var runEnabled by mutableStateOf(true)
    private var status by mutableStateOf<Pair<String, ResearchTone>>("" to ResearchTone.MUTED)
    private var resultText by mutableStateOf("No comparison run yet.")
    private var notes by mutableStateOf("")
    private var validationStatus by mutableStateOf("Run a comparison before saving a verdict.")
    private var boltSampleStatus by mutableStateOf<Pair<String, ResearchTone>>("" to ResearchTone.MUTED)
    private lateinit var previewView: RoutePreviewView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = RouteEndpointSettings.load(this)
        endpointUrl = config.baseUrl
        token = config.bearerToken
        requestsEnabled = config.enabled
        refreshEndpointStatus(config)
        status = "Ready. ${RouteResearchDatabase.get(this).comparisonCount()} route comparisons saved locally." to ResearchTone.MUTED
        previewView = RoutePreviewView(this)
        refreshBoltSampleStatus()
        enableEdgeToEdge()
        setContent {
            CourierPilotTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RouteResearchScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshEndpointStatus()
        refreshBoltSampleStatus()
    }

    override fun onDestroy() {
        runningRequest?.cancel(true)
        executor.shutdownNow()
        super.onDestroy()
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) useCurrentLocation()
            else showStatus("Location permission was not granted.", true)
        }
    }

    @Composable
    private fun RouteResearchScreen() {
        val palette = LocalCourierPalette.current
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp),
        ) {
            DetailHeader("Route research", "Real Vilnius route validation", ::finish)

            SectionLabel("How to test")
            GroupedBlock {
                Column(
                    Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "1. Save the Valhalla token once. 2. Tap Use my location. 3. Enter a destination address or coordinates. 4. Compare. 5. Mark which candidate you would actually ride.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                    )
                    Text(
                        "Orange = pedestrian shortcut; blue = cycleway-biased. The preview is geometry-only, so use your local knowledge when rating it.",
                        color = palette.rateFire,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                    )
                }
            }

            SectionLabel("Protected endpoint")
            SettingsGroup {
                Column(
                    Modifier.padding(horizontal = 2.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ResearchField(endpointUrl, "HTTPS base URL") { endpointUrl = it }
                    ResearchField(token, "Bearer token", password = true) { token = it }
                }
                SettingsDivider()
                CourierPilotToggleRow(
                    title = "Enable route research requests",
                    subtitle = "Takes effect after Save endpoint.",
                    checked = requestsEnabled,
                    onCheckedChange = { requestsEnabled = it },
                )
                SettingsDivider()
                Column(
                    Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(endpointStatus.first, color = endpointStatus.second.color(), fontWeight = FontWeight.Bold, fontSize = 12.5.sp, lineHeight = 17.sp)
                    Text(
                        "If CourierPilot is reinstalled or its app data is cleared, paste the private token again. Normal app updates keep it.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.5.sp,
                        lineHeight = 17.sp,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            ActionRow(0, 1, "Save endpoint") { saveEndpoint() }
            Footnote("The private token is stored only on this app install and intentionally excluded from Android backup.")

            SectionLabel("Start")
            GroupedRow(index = 0, count = 2) {
                CoordinateFields(fromLat, fromLon, "Start latitude", "Start longitude", { fromLat = it }, { fromLon = it })
            }
            ActionRow(1, 2, "📍 Use my current location") { useCurrentLocation() }
            Footnote("Use a fresh phone fix instead of typing latitude/longitude.")

            SectionLabel("Destination")
            GroupedRow(index = 0, count = 3) {
                Column(Modifier.weight(1f)) {
                    ResearchField(destinationAddress, "Vilnius address, e.g. Gedimino pr. 9") { destinationAddress = it }
                }
            }
            ActionRow(1, 3, "Resolve address to coordinates") { geocodeDestination() }
            GroupedRow(index = 2, count = 3) {
                CoordinateFields(toLat, toLon, "End latitude", "End longitude", { toLat = it }, { toLon = it })
            }
            Footnote("Type an address you know or paste coordinates.")
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { runComparison() },
                enabled = runEnabled,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                Text("Compare pedestrian vs cycleway", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Text(
                status.first,
                modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp),
                color = status.second.color(),
                fontSize = 12.5.sp,
                lineHeight = 17.sp,
            )

            SectionLabel("Route shape")
            GroupedBlock {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)),
                )
            }
            Footnote("Geometry preview; start/end are black dots.")

            SectionLabel("Result")
            GroupedRow(index = 0, count = 2) {
                SelectionContainer(Modifier.weight(1f)) {
                    Text(resultText, fontSize = 12.5.sp, lineHeight = 17.sp)
                }
            }
            ActionRow(1, 2, "Share comparison as GeoJSON") { shareComparison() }
            Footnote("Distance is the primary signal; Valhalla ETA is still generic.")

            SectionLabel("Your verdict")
            GroupedRow(index = 0, count = 6) {
                Column(Modifier.weight(1f)) {
                    ResearchField(notes, "Optional note: stairs, useless detour, shortcut…", singleLine = false) { notes = it }
                }
            }
            ActionRow(1, 6, "🟠 Pedestrian is better") { saveVerdict(RouteComparisonVerdict.PEDESTRIAN_BETTER) }
            ActionRow(2, 6, "🔵 Cycleway is better") { saveVerdict(RouteComparisonVerdict.CYCLEWAY_BETTER) }
            ActionRow(3, 6, "Both are usable") { saveVerdict(RouteComparisonVerdict.BOTH_OK) }
            ActionRow(4, 6, "Both are bad") { saveVerdict(RouteComparisonVerdict.BOTH_BAD) }
            GroupedRow(index = 5, count = 6) {
                Text(validationStatus, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.5.sp, lineHeight = 17.sp)
            }
            Footnote("This creates the real Vilnius validation corpus.")

            SectionLabel("Bolt map sample")
            GroupedRow(index = 0, count = 6) {
                Text(
                    boltSampleStatus.first,
                    Modifier.weight(1f),
                    color = boltSampleStatus.second.color(),
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                )
            }
            ActionRow(1, 6, "Open Android Accessibility settings") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            ActionRow(2, 6, "Arm next Bolt offer/map screen") {
                BoltAccessibilityDiagnostics.arm(this@RouteResearchActivity)
                refreshBoltSampleStatus()
            }
            ActionRow(3, 6, "Disarm") {
                BoltAccessibilityDiagnostics.disarm(this@RouteResearchActivity)
                refreshBoltSampleStatus()
            }
            ActionRow(4, 6, "Share full Bolt sample") { shareBoltSample() }
            ActionRow(5, 6, "Clear Bolt sample", color = MaterialTheme.colorScheme.error) {
                BoltAccessibilityDiagnostics.clear(this@RouteResearchActivity)
                refreshBoltSampleStatus()
            }
            Footnote(
                "One arm captures tree + screenshot + available cached phone GPS. For GPS metadata, grant location once with Use my current location. The Bolt research service only reads the best cached fix; it does not start background tracking.",
            )
        }
    }

    private fun saveEndpoint() {
        val candidate = currentEndpointInput()
        if (candidate.enabled && candidate.bearerToken.isBlank()) {
            refreshEndpointStatus(candidate)
            showStatus("Route token missing. Paste the private server token, then tap Save endpoint.", true)
            return
        }
        runCatching { RouteEndpointSettings.save(this, candidate) }
            .onSuccess {
                val saved = RouteEndpointSettings.load(this)
                endpointUrl = saved.baseUrl
                token = saved.bearerToken
                refreshEndpointStatus(saved)
                showStatus(if (saved.enabled) "Protected route service enabled." else "Endpoint saved; route requests remain disabled.", false)
            }
            .onFailure {
                refreshEndpointStatus(candidate)
                showStatus(it.message ?: "Could not save endpoint.", true)
            }
    }

    private fun currentEndpointInput() = RouteEndpointConfig(
        enabled = requestsEnabled,
        baseUrl = endpointUrl,
        bearerToken = token,
    )

    private fun refreshEndpointStatus(config: RouteEndpointConfig = RouteEndpointSettings.load(this)) {
        endpointStatus = when {
            config.bearerToken.isBlank() ->
                "TOKEN MISSING — paste the private server token, enable requests, then Save endpoint." to ResearchTone.ERROR
            !config.enabled ->
                "ROUTE REQUESTS DISABLED — enable the switch and tap Save endpoint." to ResearchTone.WARNING
            runCatching { config.validated() }.isFailure ->
                "ENDPOINT CONFIG INVALID — check the HTTPS URL/token and save again." to ResearchTone.ERROR
            else ->
                "READY — protected Valhalla route service is configured on this device." to ResearchTone.GOOD
        }
    }

    private fun useCurrentLocation() {
        if (!RouteResearchLocation.hasPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION)
            return
        }
        showStatus("Getting current location…", false)
        RouteResearchLocation.requestCurrent(this) { result ->
            result.onSuccess { fix ->
                fromLat = String.format(Locale.US, "%.7f", fix.point.latitude)
                fromLon = String.format(Locale.US, "%.7f", fix.point.longitude)
                showStatus("Location: ±${fix.accuracyMeters?.toInt() ?: "?"} m · ${fix.provider}", false)
            }.onFailure { showStatus(it.message ?: "Could not obtain location.", true) }
        }
    }

    private fun geocodeDestination() {
        showStatus("Resolving destination address…", false)
        RouteResearchGeocoder.resolve(this, destinationAddress) { result ->
            result.onSuccess { point ->
                toLat = String.format(Locale.US, "%.7f", point.latitude)
                toLon = String.format(Locale.US, "%.7f", point.longitude)
                showStatus("Address resolved. Ready to compare.", false)
            }.onFailure { showStatus(it.message ?: "Could not resolve address.", true) }
        }
    }

    private fun runComparison() {
        if (runningRequest?.isDone == false) return
        val savedConfig = RouteEndpointSettings.load(this)
        val config = runCatching { savedConfig.validated() }.getOrElse { failure ->
            refreshEndpointStatus(savedConfig)
            val message = when {
                savedConfig.bearerToken.isBlank() ->
                    "Route token missing. Paste the private server token under Protected endpoint, enable requests, and tap Save endpoint."
                !savedConfig.enabled ->
                    "Route requests are disabled. Enable route research requests and tap Save endpoint."
                else -> failure.message ?: "Route endpoint configuration is invalid."
            }
            showStatus(message, true)
            return
        }
        val points = runCatching {
            listOf(
                RoutePoint(parseCoordinate(fromLat, "start latitude"), parseCoordinate(fromLon, "start longitude")),
                RoutePoint(parseCoordinate(toLat, "end latitude"), parseCoordinate(toLon, "end longitude")),
            ).also { RouteIntelligencePolicy.validate(RouteRequest(it, RouteProfile.PEDESTRIAN_SHORTCUT)) }
        }.getOrElse {
            showStatus(it.message ?: "Invalid coordinates.", true)
            return
        }

        runEnabled = false
        showStatus("Requesting both candidates…", false)
        resultText = "Waiting for Valhalla…"
        previewView.setRoutes(null, null)
        runningRequest = executor.submit {
            val comparison = RouteComparisonEngine(ValhallaRouteProvider(config)).compare(points)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                runEnabled = true
                currentComparison = comparison
                currentStart = points.first()
                currentEnd = points.last()
                previewView.setRoutes(comparison.pedestrian.getOrNull(), comparison.cycleway.getOrNull())
                resultText = formatComparison(comparison)
                validationStatus = "Choose the route you would actually ride."
                val succeeded = listOf(comparison.pedestrian, comparison.cycleway).count { it.isSuccess }
                showStatus("$succeeded/2 candidates returned successfully.", succeeded != 2)
            }
        }
    }

    private fun saveVerdict(verdict: RouteComparisonVerdict) {
        val comparison = currentComparison ?: run {
            validationStatus = "Run a comparison first."
            return
        }
        val start = currentStart ?: return
        val end = currentEnd ?: return
        val id = RouteResearchDatabase.get(this).recordComparison(start, end, comparison, verdict, notes)
        validationStatus = "Saved validation #$id · ${verdict.name.lowercase().replace('_', ' ')}"
        notes = ""
    }

    private fun shareComparison() {
        val comparison = currentComparison ?: run {
            showStatus("Run a comparison first.", true)
            return
        }
        val body = buildString {
            appendLine("CourierPilot route research")
            currentStart?.let { appendLine("Start: ${it.latitude},${it.longitude}") }
            currentEnd?.let { appendLine("End: ${it.latitude},${it.longitude}") }
            appendLine(formatComparison(comparison))
            appendLine()
            appendLine("GeoJSON:")
            append(RoutePolyline.comparisonGeoJson(comparison))
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
        boltSampleStatus = buildString {
            append(if (armed) "ARMED — switch to Bolt and wait for the offer/map screen." else "Not armed.")
            if (sample != null) {
                append("\nLast sample: ${sample.nodeCount} nodes")
                if (sample.truncated) append(" · tree truncated")
                append(if (sample.screenshotAvailable) " · screenshot ✓" else " · screenshot missing")
                append(if (sample.locationAvailable) " · GPS ✓" else " · GPS missing")
                sample.locationAgeMillis?.let { append(" (${it / 1000}s old)") }
            } else {
                append("\nNo saved Bolt sample yet.")
            }
        } to if (armed) ResearchTone.WARNING else ResearchTone.MUTED
    }

    private fun shareBoltSample() {
        val files = BoltAccessibilityDiagnostics.sampleFiles(this)
        if (files.isEmpty()) {
            boltSampleStatus = "No Bolt sample to share yet." to boltSampleStatus.second
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

    private fun parseCoordinate(value: String, label: String): Double =
        value.trim().toDoubleOrNull() ?: error("Invalid $label")

    private fun showStatus(message: String, error: Boolean) {
        status = message to if (error) ResearchTone.ERROR else ResearchTone.MUTED
    }

    companion object {
        private const val REQUEST_LOCATION = 41
    }
}

private enum class ResearchTone { MUTED, GOOD, WARNING, ERROR }

@Composable
private fun ResearchTone.color(): Color = when (this) {
    ResearchTone.MUTED -> MaterialTheme.colorScheme.onSurfaceVariant
    ResearchTone.GOOD -> LocalCourierPalette.current.onlineText
    ResearchTone.WARNING -> LocalCourierPalette.current.rateFire
    ResearchTone.ERROR -> MaterialTheme.colorScheme.error
}

@Composable
private fun ResearchField(
    value: String,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    password: Boolean = false,
    numeric: Boolean = false,
    singleLine: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    val palette = LocalCourierPalette.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = if (singleLine) ({ Text(label, maxLines = 1) }) else null,
        placeholder = if (singleLine) null else ({ Text(label) }),
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        shape = RoundedCornerShape(14.dp),
        textStyle = MaterialTheme.typography.bodyMedium,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = when {
            password -> KeyboardOptions(keyboardType = KeyboardType.Password)
            numeric -> KeyboardOptions(keyboardType = KeyboardType.Decimal)
            else -> KeyboardOptions.Default
        },
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedBorderColor = palette.chipBorder,
        ),
    )
}

@Composable
private fun RowScope.CoordinateFields(
    lat: String,
    lon: String,
    latLabel: String,
    lonLabel: String,
    onLat: (String) -> Unit,
    onLon: (String) -> Unit,
) {
    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ResearchField(lat, latLabel, Modifier.weight(1f), numeric = true, onValueChange = onLat)
        ResearchField(lon, lonLabel, Modifier.weight(1f), numeric = true, onValueChange = onLon)
    }
}
