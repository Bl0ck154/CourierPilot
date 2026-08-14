package com.block154.courierpilot

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReliabilityActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            if (!granted) HeartbeatSettings.setEnabled(this, false)
            render()
        }
    }

    private fun render() {
        val screen = buildScreen()
        setContentView(screen)
        screen.applySystemBarsPadding()
    }

    private fun buildScreen(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(34))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("‹", 28f, TEXT, true).apply {
                gravity = Gravity.CENTER
                background = solid(Color.WHITE, dp(14).toFloat())
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(46), dp(46)))
            addView(LinearLayout(this@ReliabilityActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text("Reliability", 24f, TEXT, true))
                addView(text("Background health and capture diagnostics", 12f, MUTED).top(dp(3)))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        }
        root.addView(header)

        root.addView(sectionTitle("Android access", "CourierPilot needs both services enabled").top(dp(22)))
        root.addView(healthCard(
            "Notification access",
            hasNotificationAccess(),
            if (hasNotificationAccess()) "Connected" else "Required for incoming offer detection",
            "Open",
        ) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }.top(dp(10)))
        root.addView(healthCard(
            "Accessibility capture",
            hasAccessibilityAccess(),
            if (hasAccessibilityAccess()) "Connected" else "Required for price reading and screenshots",
            "Open",
        ) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.top(dp(8)))

        root.addView(sectionTitle("Background reliability", "Android/OEM battery controls can stop background work").top(dp(24)))
        val power = getSystemService(POWER_SERVICE) as PowerManager
        val unrestricted = power.isIgnoringBatteryOptimizations(packageName)
        root.addView(healthCard(
            "Battery optimization",
            unrestricted,
            if (unrestricted) "CourierPilot is excluded from Doze optimization" else "Set CourierPilot to Unrestricted / Don't optimize",
            "Battery settings",
        ) { openBatterySettings() }.top(dp(10)))

        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val backgroundRestricted = if (Build.VERSION.SDK_INT >= 28) activityManager.isBackgroundRestricted else false
        root.addView(healthCard(
            "Background restriction",
            !backgroundRestricted,
            if (backgroundRestricted) "Android reports background activity as restricted" else "No Android background restriction reported",
            "App info",
        ) { openAppInfo() }.top(dp(8)))

        root.addView(card().apply {
            addView(text("Realme / ColorOS / OxygenOS", 15f, TEXT, true))
            addView(text("Also check App info → Battery usage for Allow background activity / Unrestricted, and Auto launch if your firmware exposes it. These OEM switches cannot be granted silently by CourierPilot.", 12f, MUTED).top(dp(5)))
            addView(linkButton("Open CourierPilot app info") { openAppInfo() }.top(dp(8)))
        }.top(dp(8)))

        root.addView(sectionTitle("Offer behavior", "Optional actions when a new offer arrives").top(dp(24)))
        root.addView(toggleCard(
            "Automatically open courier app",
            "Use the courier notification action when Android permits background launch.",
            OfferState.autoOpen(this),
        ) { OfferState.setAutoOpen(this, it) }.top(dp(10)))
        root.addView(toggleCard(
            "Wake screen for offers",
            "Best-effort 3-second screen wake. It does not unlock the phone or bypass the keyguard.",
            OfferState.wakeScreen(this),
        ) { OfferState.setWakeScreen(this, it) }.top(dp(8)))
        root.addView(toggleCard(
            "Periodic alive reminder",
            "Every ${HeartbeatScheduler.INTERVAL_HOURS} hours. A normal non-persistent notification confirms CourierPilot is alive; it never stays permanently in the shade.",
            HeartbeatSettings.enabled(this),
        ) { enabled ->
            if (
                enabled &&
                Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                HeartbeatSettings.setEnabled(this, true)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
            } else {
                HeartbeatSettings.setEnabled(this, enabled)
            }
        }.top(dp(8)))

        root.addView(sectionTitle("Current capture", "Useful when an offer was missed").top(dp(24)))
        val pending = OfferState.pending(this)
        root.addView(card().apply {
            addView(text("Pending offer", 12f, MUTED, true))
            addView(text(
                pending?.let { "${OfferState.platformLabel(it.packageName)} · armed ${formatTime(it.armedAt)}" } ?: "None",
                14f,
                TEXT,
                true,
            ).top(dp(4)))
            addView(text("Last screenshot", 12f, MUTED, true).top(dp(14)))
            addView(text(OfferState.lastCapture(this@ReliabilityActivity), 13f, TEXT).top(dp(4)))
            val error = OfferState.lastError(this@ReliabilityActivity)
            if (error.isNotBlank()) {
                addView(text("Last status / error", 12f, MUTED, true).top(dp(14)))
                addView(text(error, 13f, RED).top(dp(4)))
            }
        }.top(dp(10)))

        root.addView(sectionTitle("Capture event log", "No customer names, addresses or raw offer text are written here").top(dp(24)))
        val events = CaptureEventLog.recent(this, 60)
        root.addView(card().apply {
            if (events.isEmpty()) {
                addView(text("No diagnostic events yet.", 13f, MUTED))
            } else {
                events.forEachIndexed { index, event ->
                    if (index > 0) addView(divider().top(dp(9)))
                    val platform = event.platform.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                    addView(text("${formatTime(event.timestamp)} · ${event.stage}$platform", 11f, MUTED, true).top(if (index == 0) 0 else dp(9)))
                    addView(text(event.message, 12f, TEXT).top(dp(3)))
                }
            }
            addView(linkButton("Share diagnostics") { shareDiagnostics() }.top(dp(14)))
            addView(linkButton("Clear event log") {
                CaptureEventLog.clear(this@ReliabilityActivity)
                render()
            }.top(dp(4)))
        }.top(dp(10)))

        root.addView(text("CourierPilot · ${appVersion()}", 11f, MUTED).apply { gravity = Gravity.CENTER }.top(dp(26)))
        scroll.addView(root)
        return scroll
    }

    private fun healthCard(title: String, ok: Boolean, subtitle: String, actionLabel: String, action: () -> Unit): View = card().apply {
        val row = LinearLayout(this@ReliabilityActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(View(this@ReliabilityActivity).apply {
            background = solid(if (ok) GREEN else RED, dp(20).toFloat())
        }, LinearLayout.LayoutParams(dp(10), dp(10)))
        row.addView(LinearLayout(this@ReliabilityActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 15f, TEXT, true))
            addView(text(subtitle, 12f, MUTED).top(dp(3)))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        row.addView(linkButton(actionLabel, action))
        addView(row)
    }

    private fun toggleCard(title: String, subtitle: String, checked: Boolean, changed: (Boolean) -> Unit): View = card().apply {
        val row = LinearLayout(this@ReliabilityActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(LinearLayout(this@ReliabilityActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 15f, TEXT, true))
            addView(text(subtitle, 12f, MUTED).top(dp(3)))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Switch(this@ReliabilityActivity).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> changed(value) }
        })
        addView(row)
    }

    private fun shareDiagnostics() {
        val power = getSystemService(POWER_SERVICE) as PowerManager
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val pending = OfferState.pending(this)
        val body = buildString {
            appendLine("CourierPilot ${appVersion()}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Notification access: ${hasNotificationAccess()}")
            appendLine("Accessibility: ${hasAccessibilityAccess()}")
            appendLine("Ignoring battery optimizations: ${power.isIgnoringBatteryOptimizations(packageName)}")
            if (Build.VERSION.SDK_INT >= 28) appendLine("Background restricted: ${activityManager.isBackgroundRestricted}")
            appendLine("Alive reminder: ${HeartbeatSettings.enabled(this@ReliabilityActivity)}")
            appendLine("Pending: ${pending?.let { OfferState.platformLabel(it.packageName) } ?: "none"}")
            appendLine("Last screenshot: ${OfferState.lastCapture(this@ReliabilityActivity)}")
            appendLine("Last error: ${OfferState.lastError(this@ReliabilityActivity)}")
            appendLine()
            appendLine("Event log (privacy-safe):")
            append(CaptureEventLog.asText(this@ReliabilityActivity))
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "CourierPilot diagnostics")
            putExtra(Intent.EXTRA_TEXT, body)
        }, "Share CourierPilot diagnostics"))
    }

    private fun openBatterySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            .onFailure { openAppInfo() }
    }

    private fun openAppInfo() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it)?.packageName == packageName }
    }

    private fun hasAccessibilityAccess(): Boolean {
        if (Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) return false
        val target = ComponentName(this, OfferAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == target }
    }

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")

    private fun formatTime(timestamp: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

    private fun sectionTitle(title: String, subtitle: String): View = LinearLayout(this).apply {
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

    private fun divider(): View = View(this).apply { setBackgroundColor(BORDER) }.also {
        it.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun linkButton(label: String, click: () -> Unit): TextView = text(label, 12f, BLUE, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { click() }
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

    private fun solid(fill: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radius
    }

    private fun <T : View> T.top(value: Int): T {
        val current = layoutParams
        layoutParams = if (current is LinearLayout.LayoutParams) {
            current.apply { topMargin = value }
        } else {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = value }
        }
        return this
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_POST_NOTIFICATIONS = 1546
        private val BG = Color.parseColor("#F5F7FB")
        private val TEXT = Color.parseColor("#111827")
        private val MUTED = Color.parseColor("#6B7280")
        private val BORDER = Color.parseColor("#E5E7EB")
        private val BLUE = Color.parseColor("#2563EB")
        private val GREEN = Color.parseColor("#16A34A")
        private val RED = Color.parseColor("#DC2626")
    }
}
