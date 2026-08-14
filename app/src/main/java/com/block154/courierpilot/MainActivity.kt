package com.block154.courierpilot

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : Activity() {

    private enum class Screen { HOME, HISTORY, STATS, SETTINGS }

    private lateinit var contentHost: FrameLayout
    private lateinit var navHome: TextView
    private lateinit var navHistory: TextView
    private lateinit var navStats: TextView
    private var screen = Screen.HOME
    private var diagnosticsExpanded = false

    private val database by lazy { OfferDatabase.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = SURFACE
        window.insetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
        )
        setContentView(buildShell())
        renderScreen()
    }

    override fun onResume() {
        super.onResume()
        if (::contentHost.isInitialized) renderScreen()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (screen == Screen.SETTINGS) {
            screen = Screen.HOME
            renderScreen()
        } else {
            super.onBackPressed()
        }
    }

    private fun buildShell(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        contentHost = FrameLayout(this)
        root.addView(
            contentHost,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        )

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(10))
            background = solidDrawable(SURFACE, 0f)
            elevation = dp(10).toFloat()
        }
        navHome = navItem("⌂", "Home") { navigate(Screen.HOME) }
        navHistory = navItem("≡", "History") { navigate(Screen.HISTORY) }
        navStats = navItem("▥", "Stats") { navigate(Screen.STATS) }
        nav.addView(navHome, weighted())
        nav.addView(navHistory, weighted())
        nav.addView(navStats, weighted())
        root.addView(nav, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(70)))
        return root
    }

    private fun navigate(target: Screen) {
        screen = target
        renderScreen()
    }

    private fun renderScreen() {
        contentHost.removeAllViews()
        updateNav()
        contentHost.addView(
            when (screen) {
                Screen.HOME -> homeScreen()
                Screen.HISTORY -> historyScreen()
                Screen.STATS -> statsScreen()
                Screen.SETTINGS -> settingsScreen()
            }
        )
    }

    private fun updateNav() {
        styleNav(navHome, screen == Screen.HOME)
        styleNav(navHistory, screen == Screen.HISTORY)
        styleNav(navStats, screen == Screen.STATS)
    }

    private fun homeScreen(): View = scrollScreen { root ->
        root.addView(topBar("CourierPilot", "Your Wolt + Bolt offer journal", showSettings = true))

        val notificationOk = hasNotificationAccess()
        val accessibilityOk = hasAccessibilityAccess()
        if (!notificationOk || !accessibilityOk) {
            root.addView(accessWarning(notificationOk, accessibilityOk).withTop(dp(18)))
        } else {
            root.addView(statusPill("● Capture active", GREEN, SOFT_GREEN).withTop(dp(16)))
        }

        val today = database.summarySince(startOfDay(0))
        val todayWolt = database.summarySince(startOfDay(0), "Wolt")
        val todayBolt = database.summarySince(startOfDay(0), "Bolt")

        root.addView(sectionTitle("Today", dayLabel()).withTop(dp(24)))
        val mainMetrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addMetric(mainMetrics, metricCard("OFFERS", today.count.toString(), "captured with price"), 0)
        addMetric(mainMetrics, metricCard("AVG OFFER", formatAveragePrice(today), "all platforms"), dp(10))
        root.addView(mainMetrics.withTop(dp(12)))

        val secondaryMetrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addMetric(secondaryMetrics, metricCard("AVG €/KM", formatPerKm(today), formatAverageDistance(today)), 0)
        addMetric(secondaryMetrics, metricCard("WOLT / BOLT", "${todayWolt.count} / ${todayBolt.count}", "today"), dp(10))
        root.addView(secondaryMetrics.withTop(dp(10)))

        val week = database.summarySince(startOfDay(-6))
        root.addView(sectionTitle("This week", "Last 7 days").withTop(dp(26)))
        root.addView(summaryCard(week).withTop(dp(12)))

        root.addView(sectionTitle("Offer activity", "Last 12 months").withTop(dp(26)))
        val heatmapCard = card().apply {
            addView(text("More offers = darker square", 12f, MUTED))
            addView(OfferHeatmapView(this@MainActivity).apply {
                setDays(database.dailyStats(365))
            }.withTop(dp(10)))
        }
        root.addView(heatmapCard.withTop(dp(12)))

        root.addView(sectionTitle("Recent offers", "Tap to open screenshot").withTop(dp(26)))
        val recent = database.recent(5)
        if (recent.isEmpty()) {
            root.addView(emptyCard("No priced offers captured yet.").withTop(dp(12)))
        } else {
            recent.forEach { root.addView(offerCard(it).withTop(dp(9))) }
            root.addView(linkButton("View full history") { navigate(Screen.HISTORY) }.withTop(dp(12)))
        }
    }

    private fun historyScreen(): View = scrollScreen { root ->
        root.addView(topBar("History", "Every priced offer and its screenshot", showSettings = true))
        val records = database.recent(200)
        if (records.isEmpty()) {
            root.addView(emptyCard("No offer history yet.").withTop(dp(22)))
            return@scrollScreen
        }

        val dayFormat = SimpleDateFormat("EEEE, d MMM", Locale.getDefault())
        val keyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        var lastDay = ""
        records.forEach { record ->
            val dayKey = keyFormat.format(Date(record.capturedAt))
            if (dayKey != lastDay) {
                root.addView(text(dayFormat.format(Date(record.capturedAt)), 15f, TEXT, bold = true).withTop(dp(if (lastDay.isEmpty()) 22 else 24)))
                lastDay = dayKey
            }
            root.addView(offerCard(record).withTop(dp(8)))
        }
    }

    private fun statsScreen(): View = scrollScreen { root ->
        root.addView(topBar("Statistics", "Patterns across your captured offers", showSettings = true))

        root.addView(sectionTitle("Overview", "Today · 7 days · 30 days").withTop(dp(22)))
        root.addView(periodCard("Today", database.summarySince(startOfDay(0))).withTop(dp(12)))
        root.addView(periodCard("Last 7 days", database.summarySince(startOfDay(-6))).withTop(dp(9)))
        root.addView(periodCard("Last 30 days", database.summarySince(startOfDay(-29))).withTop(dp(9)))

        root.addView(sectionTitle("Contribution calendar", "Captured offers per day").withTop(dp(26)))
        val heatmap = card().apply {
            addView(OfferHeatmapView(this@MainActivity).apply {
                setDays(database.dailyStats(365))
            })
            addView(legendRow().withTop(dp(4)))
        }
        root.addView(heatmap.withTop(dp(12)))

        val recentRecords = database.recent(200)
        root.addView(sectionTitle("Offer activity by hour", "Based on up to 200 recent captures · not work hours").withTop(dp(26)))
        val hourly = card().apply {
            addView(HourlyActivityView(this@MainActivity).apply { setOffers(recentRecords) })
            addView(text(activeHourSummary(recentRecords), 13f, MUTED).withTop(dp(4)))
        }
        root.addView(hourly.withTop(dp(12)))

        val wolt = database.summarySince(startOfDay(-29), "Wolt")
        val bolt = database.summarySince(startOfDay(-29), "Bolt")
        root.addView(sectionTitle("Platform split", "Last 30 days").withTop(dp(26)))
        val platforms = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addMetric(platforms, platformCard("Wolt", wolt, BLUE), 0)
        addMetric(platforms, platformCard("Bolt", bolt, GREEN), dp(10))
        root.addView(platforms.withTop(dp(12)))
    }

    private fun settingsScreen(): View = scrollScreen { root ->
        root.addView(topBar("Settings", "Capture permissions and diagnostics", showSettings = false, showBack = true))

        root.addView(sectionTitle("Capture health", "Required Android access").withTop(dp(22)))
        val notificationOk = hasNotificationAccess()
        val accessibilityOk = hasAccessibilityAccess()
        root.addView(permissionCard(
            "Notification access",
            notificationOk,
            if (notificationOk) "Listening for Wolt/Bolt offer notifications" else "Required to arm offer capture",
        ) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }.withTop(dp(12)))
        root.addView(permissionCard(
            "Accessibility screenshot service",
            accessibilityOk,
            if (accessibilityOk) "Ready to read/capture the visible offer screen" else "Required to detect price and take screenshots",
        ) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.withTop(dp(9)))

        root.addView(sectionTitle("Behavior", "How incoming offers are handled").withTop(dp(26)))
        val behavior = card().apply {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val labels = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text("Automatically open courier app", 15f, TEXT, bold = true))
                addView(text("Use the original Wolt/Bolt notification action when Android allows it.", 12f, MUTED).withTop(dp(3)))
            }
            row.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val autoOpen = Switch(this@MainActivity).apply {
                isChecked = OfferState.autoOpen(this@MainActivity)
                setOnCheckedChangeListener { _, checked -> OfferState.setAutoOpen(this@MainActivity, checked) }
            }
            row.addView(autoOpen)
            addView(row)
        }
        root.addView(behavior.withTop(dp(12)))

        root.addView(sectionTitle("Diagnostics", "Only needed when capture misbehaves").withTop(dp(26)))
        val diag = card().apply {
            addView(text("Last screenshot", 12f, MUTED, bold = true))
            addView(text(OfferState.lastCapture(this@MainActivity).ifBlank { "None yet" }, 13f, TEXT).withTop(dp(4)))
            val error = OfferState.lastError(this@MainActivity)
            if (error.isNotBlank()) {
                addView(text("Last status / error", 12f, MUTED, bold = true).withTop(dp(14)))
                addView(text(error, 13f, if (error.contains("error", true)) RED else TEXT).withTop(dp(4)))
            }
            addView(linkButton(if (diagnosticsExpanded) "Hide raw Accessibility / OCR text" else "Show raw Accessibility / OCR text") {
                diagnosticsExpanded = !diagnosticsExpanded
                renderScreen()
            }.withTop(dp(14)))
            if (diagnosticsExpanded) {
                addView(text(OfferState.lastUiText(this@MainActivity).ifBlank { "No raw text captured yet." }, 12f, MUTED).apply {
                    setTextIsSelectable(true)
                }.withTop(dp(12)))
            }
        }
        root.addView(diag.withTop(dp(12)))
        root.addView(text("CourierPilot · v0.4.0", 12f, MUTED).apply { gravity = Gravity.CENTER }.withTop(dp(28)))
    }

    private fun topBar(title: String, subtitle: String, showSettings: Boolean, showBack: Boolean = false): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (showBack) row.addView(iconButton("‹") { navigate(Screen.HOME) })
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 25f, TEXT, bold = true))
            addView(text(subtitle, 13f, MUTED).withTop(dp(3)))
        }
        row.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (showSettings) row.addView(iconButton("⚙") { navigate(Screen.SETTINGS) })
        return row
    }

    private fun accessWarning(notificationOk: Boolean, accessibilityOk: Boolean): View = card(SOFT_RED, RED_SOFT_BORDER).apply {
        addView(text("Action required", 16f, RED, bold = true))
        addView(text("Capture is paused until required Android access is restored.", 13f, MUTED).withTop(dp(5)))
        if (!notificationOk) {
            addView(actionButton("Enable notification access", true) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }.withTop(dp(12)))
        }
        if (!accessibilityOk) {
            addView(actionButton("Enable screenshot accessibility", true) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.withTop(dp(8)))
        }
    }

    private fun permissionCard(title: String, ok: Boolean, subtitle: String, action: () -> Unit): View = card().apply {
        val row = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(statusDot(ok))
        val labels = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 15f, TEXT, bold = true))
            addView(text(subtitle, 12f, MUTED).withTop(dp(3)))
        }
        row.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        row.addView(linkButton(if (ok) "Open" else "Fix", action))
        addView(row)
    }

    private fun summaryCard(summary: OfferSummary): View = card().apply {
        val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        addCompactMetric(row, "Offers", summary.count.toString())
        addCompactMetric(row, "Avg", formatAveragePrice(summary))
        addCompactMetric(row, "€/km", formatPerKm(summary))
        addView(row)
    }

    private fun periodCard(label: String, summary: OfferSummary): View = card().apply {
        val header = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(label, 15f, TEXT, bold = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(text("${summary.count} offers", 13f, MUTED))
        }
        addView(header)
        val metrics = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        addCompactMetric(metrics, "Average", formatAveragePrice(summary))
        addCompactMetric(metrics, "Distance", formatAverageDistance(summary))
        addCompactMetric(metrics, "€/km", formatPerKm(summary))
        addView(metrics.withTop(dp(14)))
    }

    private fun platformCard(label: String, summary: OfferSummary, accent: Int): View = card().apply {
        addView(text(label, 14f, accent, bold = true))
        addView(text(summary.count.toString(), 26f, TEXT, bold = true).withTop(dp(7)))
        addView(text("offers", 12f, MUTED))
        addView(text("Avg ${formatAveragePrice(summary)}", 13f, TEXT, bold = true).withTop(dp(12)))
        addView(text(formatPerKm(summary), 12f, MUTED).withTop(dp(2)))
    }

    private fun metricCard(label: String, value: String, subtitle: String): View = card().apply {
        addView(text(label, 11f, MUTED, bold = true))
        addView(text(value, 27f, TEXT, bold = true).withTop(dp(7)))
        addView(text(subtitle, 12f, MUTED).withTop(dp(2)))
    }

    private fun offerCard(record: OfferRecord): View = card().apply {
        isClickable = true
        isFocusable = true
        setOnClickListener { openScreenshot(record.screenshotUri) }
        val top = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(statusPill(record.platform, if (record.platform == "Wolt") BLUE else GREEN, if (record.platform == "Wolt") SOFT_BLUE else SOFT_GREEN))
        top.addView(View(this@MainActivity), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(text("€${formatCents(record.priceCents)}", 18f, TEXT, bold = true))
        addView(top)
        addView(text(displayRestaurant(record.restaurant) ?: "Restaurant not detected", 15f, TEXT, bold = true).withTop(dp(11)))
        val distance = record.distanceMeters?.let { String.format(Locale.US, "%.2f km", it / 1000.0) } ?: "Distance —"
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(record.capturedAt))
        addView(text("$time  ·  $distance", 12f, MUTED).withTop(dp(4)))
    }

    private fun activeHourSummary(records: List<OfferRecord>): String {
        if (records.isEmpty()) return "No captured offers in this period yet."
        val counts = IntArray(24)
        val cal = Calendar.getInstance()
        records.forEach {
            cal.timeInMillis = it.capturedAt
            counts[cal.get(Calendar.HOUR_OF_DAY)]++
        }
        val hour = counts.indices.maxByOrNull { counts[it] } ?: return "No captured offers in this period yet."
        return "Most active captured hour: ${hour.toString().padStart(2, '0')}:00 · ${counts[hour]} offers"
    }

    private fun legendRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        addView(text("Less", 11f, MUTED))
        listOf("#E5E7EB", "#BBF7D0", "#86EFAC", "#4ADE80", "#16A34A").forEach { color ->
            addView(View(this@MainActivity).apply {
                background = solidDrawable(Color.parseColor(color), dp(3).toFloat())
            }, LinearLayout.LayoutParams(dp(11), dp(11)).apply { leftMargin = dp(4) })
        }
        addView(text("More", 11f, MUTED).apply { setPadding(dp(6), 0, 0, 0) })
    }

    private fun sectionTitle(title: String, subtitle: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(title, 19f, TEXT, bold = true))
        addView(text(subtitle, 12f, MUTED).withTop(dp(3)))
    }

    private fun statusPill(label: String, color: Int, backgroundColor: Int): TextView = text(label, 12f, color, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = solidDrawable(backgroundColor, dp(30).toFloat())
    }

    private fun statusDot(ok: Boolean): View = View(this).apply {
        background = solidDrawable(if (ok) GREEN else RED, dp(20).toFloat())
        layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
    }

    private fun card(fill: Int = SURFACE, stroke: Int = BORDER): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(15), dp(16), dp(15))
        background = roundedDrawable(fill, stroke, dp(18).toFloat())
        elevation = dp(1).toFloat()
    }

    private fun emptyCard(message: String): View = card().apply {
        addView(text(message, 14f, MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)) })
    }

    private fun actionButton(label: String, primary: Boolean = false, click: () -> Unit): TextView = text(
        label,
        13f,
        if (primary) Color.WHITE else BLUE,
        bold = true,
    ).apply {
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(11), dp(14), dp(11))
        background = if (primary) solidDrawable(BLUE, dp(12).toFloat()) else roundedDrawable(SOFT_BLUE, BLUE_SOFT_BORDER, dp(12).toFloat())
        setOnClickListener { click() }
        isClickable = true
    }

    private fun linkButton(label: String, click: () -> Unit): TextView = text(label, 13f, BLUE, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { click() }
        isClickable = true
    }

    private fun iconButton(label: String, click: () -> Unit): TextView = text(label, 25f, TEXT, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = solidDrawable(SURFACE, dp(14).toFloat())
        setOnClickListener { click() }
        isClickable = true
    }

    private fun navItem(icon: String, label: String, click: () -> Unit): TextView = text("$icon\n$label", 12f, MUTED, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(6), dp(8), dp(6))
        setOnClickListener { click() }
        isClickable = true
    }

    private fun styleNav(view: TextView, selected: Boolean) {
        view.setTextColor(if (selected) BLUE else MUTED)
        view.background = if (selected) solidDrawable(SOFT_BLUE, dp(14).toFloat()) else solidDrawable(Color.TRANSPARENT, dp(14).toFloat())
    }

    private fun addMetric(row: LinearLayout, view: View, marginLeft: Int) {
        row.addView(view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = marginLeft })
    }

    private fun addCompactMetric(row: LinearLayout, label: String, value: String) {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(text(value, 17f, TEXT, bold = true).apply { gravity = Gravity.CENTER })
            addView(text(label, 11f, MUTED).apply { gravity = Gravity.CENTER }.withTop(dp(3)))
        }
        row.addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun scrollScreen(content: (LinearLayout) -> Unit): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(30))
        }
        content(root)
        scroll.addView(root)
        return scroll
    }

    private fun weighted() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
        leftMargin = dp(4)
        rightMargin = dp(4)
    }

    private fun dayLabel(): String = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())

    private fun formatAveragePrice(summary: OfferSummary): String = summary.averagePriceCents
        ?.let { String.format(Locale.US, "€%.2f", it / 100.0) } ?: "—"

    private fun formatAverageDistance(summary: OfferSummary): String = summary.averageDistanceMeters
        ?.let { String.format(Locale.US, "%.2f km", it / 1000.0) } ?: "distance —"

    private fun formatPerKm(summary: OfferSummary): String = summary.averageEurPerKm
        ?.let { String.format(Locale.US, "€%.2f/km", it) } ?: "—"

    private fun formatCents(cents: Int): String = String.format(Locale.US, "%.2f", cents / 100.0)

    private fun displayRestaurant(value: String?): String? {
        val cleaned = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val bad = setOf(
            "close drawer", "online", "offline", "delivery settings", "insights", "finances", "stats",
            "earn extra", "benefits and insurance", "referrals", "settings", "support", "help center",
            "info hub", "accept", "decline", "google map", "map marker", "timeline",
        )
        return cleaned.takeUnless { it.lowercase(Locale.getDefault()) in bad }
    }

    private fun openScreenshot(uriString: String) {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(uriString), "image/png")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        }.onFailure {
            OfferState.markError(this, "Could not open screenshot: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun startOfDay(dayOffset: Int): Long = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, dayOffset)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

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

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = false
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun roundedDrawable(fill: Int, stroke: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radius
        setStroke(dp(1), stroke)
    }

    private fun solidDrawable(fill: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radius
    }

    private fun <T : View> T.withTop(px: Int): T {
        val current = layoutParams
        layoutParams = if (current is LinearLayout.LayoutParams) {
            current.apply { topMargin = px }
        } else {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px }
        }
        return this
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val BG = Color.parseColor("#F5F7FB")
        private val SURFACE = Color.parseColor("#FFFFFF")
        private val TEXT = Color.parseColor("#111827")
        private val MUTED = Color.parseColor("#6B7280")
        private val BORDER = Color.parseColor("#E5E7EB")
        private val BLUE = Color.parseColor("#2563EB")
        private val GREEN = Color.parseColor("#16A34A")
        private val RED = Color.parseColor("#DC2626")
        private val SOFT_BLUE = Color.parseColor("#EFF6FF")
        private val SOFT_GREEN = Color.parseColor("#F0FDF4")
        private val SOFT_RED = Color.parseColor("#FEF2F2")
        private val BLUE_SOFT_BORDER = Color.parseColor("#BFDBFE")
        private val RED_SOFT_BORDER = Color.parseColor("#FECACA")
    }
}

private class OfferHeatmapView(context: Context) : View(context) {
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val monthFormat = SimpleDateFormat("MMM", Locale.getDefault())
    private var counts: Map<String, Int> = emptyMap()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B7280")
        textSize = sp(10f)
    }

    fun setDays(days: List<DaySummary>) {
        counts = days.associate { it.day to it.count }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(84))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cell = dp(5).toFloat()
        val gap = dp(2).toFloat()
        val step = cell + gap
        val top = dp(24).toFloat()
        val left = dp(2).toFloat()
        val weeks = max(1, ((width - left * 2) / step).toInt())
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val endOfWeek = today.clone() as Calendar
        endOfWeek.add(Calendar.DAY_OF_YEAR, Calendar.SATURDAY - endOfWeek.get(Calendar.DAY_OF_WEEK))
        val start = endOfWeek.clone() as Calendar
        start.add(Calendar.DAY_OF_YEAR, -(weeks * 7 - 1))

        var maxCount = 1
        counts.values.forEach { maxCount = max(maxCount, it) }
        var lastMonth = -1
        val cursor = start.clone() as Calendar
        for (week in 0 until weeks) {
            for (row in 0 until 7) {
                val future = cursor.after(today)
                val count = if (future) 0 else counts[dayFormat.format(cursor.time)] ?: 0
                paint.color = when {
                    future -> Color.parseColor("#F3F4F6")
                    count <= 0 -> Color.parseColor("#E5E7EB")
                    count <= max(1, maxCount / 4) -> Color.parseColor("#BBF7D0")
                    count <= max(2, maxCount / 2) -> Color.parseColor("#86EFAC")
                    count < maxCount -> Color.parseColor("#4ADE80")
                    else -> Color.parseColor("#16A34A")
                }
                val x = left + week * step
                val y = top + row * step
                canvas.drawRoundRect(RectF(x, y, x + cell, y + cell), dp(1.5f), dp(1.5f), paint)
                val month = cursor.get(Calendar.MONTH)
                if (row == 0 && month != lastMonth && cursor.get(Calendar.DAY_OF_MONTH) <= 7) {
                    canvas.drawText(monthFormat.format(cursor.time), x, dp(12).toFloat(), textPaint)
                    lastMonth = month
                }
                cursor.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}

private class HourlyActivityView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B7280")
        textSize = sp(10f)
        textAlign = Paint.Align.CENTER
    }
    private var counts = IntArray(24)

    fun setOffers(records: List<OfferRecord>) {
        counts = IntArray(24)
        val calendar = Calendar.getInstance()
        records.forEach {
            calendar.timeInMillis = it.capturedAt
            counts[calendar.get(Calendar.HOUR_OF_DAY)]++
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(132))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = dp(4).toFloat()
        val right = width - dp(4).toFloat()
        val chartTop = dp(10).toFloat()
        val chartBottom = height - dp(28).toFloat()
        val maxCount = max(1, counts.maxOrNull() ?: 1)
        val slot = (right - left) / 24f
        val barWidth = max(dp(3).toFloat(), slot * 0.58f)
        counts.forEachIndexed { hour, count ->
            val barHeight = (chartBottom - chartTop) * count.toFloat() / maxCount.toFloat()
            val cx = left + slot * hour + slot / 2f
            paint.color = if (count == 0) Color.parseColor("#E5E7EB") else Color.parseColor("#2563EB")
            canvas.drawRoundRect(
                RectF(cx - barWidth / 2f, chartBottom - max(dp(3).toFloat(), barHeight), cx + barWidth / 2f, chartBottom),
                dp(2f), dp(2f), paint,
            )
        }
        listOf(0, 6, 12, 18, 23).forEach { hour ->
            val cx = left + slot * hour + slot / 2f
            canvas.drawText(hour.toString().padStart(2, '0'), cx, height - dp(8).toFloat(), textPaint)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Float = value * resources.displayMetrics.density
    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
