package com.block154.courierpilot

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class CourierPilotHomeActivity : Activity() {

    private enum class Screen { HOME, HISTORY, STATS, SETTINGS }

    private lateinit var contentHost: FrameLayout
    private lateinit var navHome: TextView
    private lateinit var navHistory: TextView
    private lateinit var navStats: TextView
    private var screen = Screen.HOME
    private var historyQuery = ""
    private val database by lazy { OfferDatabase.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shell = buildShell()
        setContentView(shell)
        shell.applySystemBarsPadding(top = true, bottom = true)
        window.statusBarColor = BG
        window.navigationBarColor = SURFACE
        window.decorView.windowInsetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
        renderScreen()
    }

    override fun onResume() {
        super.onResume()
        if (::contentHost.isInitialized) renderScreen()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (screen != Screen.HOME) {
            navigate(Screen.HOME)
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
        root.addView(contentHost, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(7), dp(12), dp(8))
            background = solidDrawable(SURFACE, 0f)
            elevation = dp(10).toFloat()
        }
        navHome = navItem("⌂", "Home") { navigate(Screen.HOME) }
        navHistory = navItem("≡", "History") { navigate(Screen.HISTORY) }
        navStats = navItem("▥", "Stats") { navigate(Screen.STATS) }
        nav.addView(navHome, weighted())
        nav.addView(navHistory, weighted())
        nav.addView(navStats, weighted())
        root.addView(nav, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(66)))
        return root
    }

    private fun navigate(target: Screen) {
        screen = target
        renderScreen()
    }

    private fun renderScreen() {
        contentHost.removeAllViews()
        styleNav(navHome, screen == Screen.HOME)
        styleNav(navHistory, screen == Screen.HISTORY)
        styleNav(navStats, screen == Screen.STATS)
        contentHost.addView(
            when (screen) {
                Screen.HOME -> homeScreen()
                Screen.HISTORY -> historyScreen()
                Screen.STATS -> statsScreen()
                Screen.SETTINGS -> settingsScreen()
            }
        )
    }

    private fun homeScreen(): View = scrollScreen { root ->
        val notificationOk = hasNotificationAccess()
        val accessibilityOk = hasAccessibilityAccess()
        root.addView(topBar(
            title = "CourierPilot",
            subtitle = "Wolt + Bolt offer journal",
            showSettings = true,
            healthy = notificationOk && accessibilityOk,
        ))

        if (!notificationOk || !accessibilityOk) {
            root.addView(accessWarning(notificationOk, accessibilityOk).withTop(dp(16)))
        }

        root.addView(shiftCard().withTop(dp(18)))

        val today = database.summarySince(startOfDay(0))
        val todayWolt = database.summarySince(startOfDay(0), "Wolt")
        val todayBolt = database.summarySince(startOfDay(0), "Bolt")
        root.addView(sectionTitle("Today", dayLabel()).withTop(dp(24)))
        root.addView(metricGrid(
            metricCard("OFFERS", today.count.toString(), "captured with price") { navigate(Screen.HISTORY) },
            metricCard("AVG OFFER", formatAveragePrice(today), "all offers") { navigate(Screen.STATS) },
        ).withTop(dp(12)))
        root.addView(metricGrid(
            metricCard("AVG €/KM", formatPerKm(today), formatAverageDistance(today)) { navigate(Screen.STATS) },
            metricCard("WOLT / BOLT", "${todayWolt.count} / ${todayBolt.count}", "today") { navigate(Screen.STATS) },
        ).withTop(dp(10)))

        val week = database.summarySince(startOfDay(-6))
        root.addView(sectionTitle("This week", "Last 7 days").withTop(dp(26)))
        root.addView(summaryCard(week) { navigate(Screen.STATS) }.withTop(dp(12)))

        root.addView(sectionTitle("Offer activity", "Recent 16 weeks · tap a day").withTop(dp(26)))
        val selected = text("Tap a day to inspect it", 12f, MUTED)
        root.addView(card().apply {
            addView(PilotHeatmapView(this@CourierPilotHomeActivity).apply {
                setWeeks(16)
                setDays(database.dailyStats(365))
                onDaySelected = { day ->
                    selected.text = day?.let {
                        val avg = it.averagePriceCents?.let { cents -> String.format(Locale.US, " · avg €%.2f", cents / 100.0) }.orEmpty()
                        "${it.day} · ${it.count} offers · W ${it.woltCount} / B ${it.boltCount}$avg"
                    } ?: "No data"
                }
            })
            addView(selected.withTop(dp(8)))
            addView(linkButton("Open full statistics") { navigate(Screen.STATS) }.withTop(dp(6)))
        }.withTop(dp(12)))

        root.addView(sectionTitle("Recent offers", "Tap any offer for route, customer and screenshot").withTop(dp(26)))
        val recent = database.recent(5).map { it.withCurrentParsedStructure() }
        if (recent.isEmpty()) {
            root.addView(emptyCard("No priced offers captured yet.").withTop(dp(12)))
        } else {
            recent.forEach { root.addView(offerCard(it).withTop(dp(9))) }
            root.addView(linkButton("View full history") { navigate(Screen.HISTORY) }.withTop(dp(10)))
        }
    }

    private fun shiftCard(): View {
        val active = database.activeShift()
        val today = database.shiftSummarySince(startOfDay(0))
        return card(if (active != null) SOFT_GREEN else SURFACE, if (active != null) GREEN_SOFT_BORDER else BORDER).apply {
            val top = LinearLayout(this@CourierPilotHomeActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val info = LinearLayout(this@CourierPilotHomeActivity).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                isFocusable = true
                setOnClickListener { navigate(Screen.STATS) }
                addView(text(if (active != null) "● Shift active" else "Work time", 15f, if (active != null) GREEN else TEXT, true))
                addView(text(
                    if (active != null) "Started ${formatClock(active.startedAt)} · today ${formatDuration(today.totalMillis)}"
                    else "Today tracked: ${formatDuration(today.totalMillis)}",
                    12f,
                    MUTED,
                ).withTop(dp(4)))
            }
            top.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(actionButton(if (active != null) "End shift" else "Start shift", active == null) {
                if (active == null) {
                    database.startShift()
                    CaptureEventLog.append(this@CourierPilotHomeActivity, "shift", "Manual work shift started")
                } else {
                    database.endActiveShift()
                    CaptureEventLog.append(this@CourierPilotHomeActivity, "shift", "Manual work shift ended")
                }
                renderScreen()
            })
            addView(top)
        }
    }

    private fun historyScreen(): View = scrollScreen { root ->
        root.addView(topBar("History", "Search every captured field", showSettings = true))

        val allRecords = database.recordsSince(0L, 5000).map { it.withCurrentParsedStructure() }
        val search = EditText(this).apply {
            hint = "Search venue, address, customer, price…"
            setText(historyQuery)
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            textSize = 14f
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = roundedDrawable(SURFACE, BORDER, dp(15).toFloat())
            setSelectAllOnFocus(false)
        }
        root.addView(search.withTop(dp(18)))

        val countLabel = text("", 12f, MUTED)
        root.addView(countLabel.withTop(dp(8)))
        val resultsHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(resultsHost)

        fun renderResults(query: String) {
            historyQuery = query
            resultsHost.removeAllViews()
            val filtered = allRecords.filter { matchesSearch(it, query) }
            countLabel.text = if (query.isBlank()) "${filtered.size} captured offers" else "${filtered.size} results for “$query”"
            if (filtered.isEmpty()) {
                resultsHost.addView(emptyCard(if (query.isBlank()) "No offer history yet." else "No offers match this search.").withTop(dp(12)))
                return
            }
            val dayFormat = SimpleDateFormat("EEEE, d MMM", Locale.getDefault())
            val keyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            var lastDay = ""
            filtered.forEach { record ->
                val dayKey = keyFormat.format(Date(record.capturedAt))
                if (dayKey != lastDay) {
                    resultsHost.addView(text(dayFormat.format(Date(record.capturedAt)), 15f, TEXT, true).withTop(dp(if (lastDay.isEmpty()) 16 else 24)))
                    lastDay = dayKey
                }
                resultsHost.addView(offerCard(record).withTop(dp(8)))
            }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderResults(s?.toString() ?: "")
            override fun afterTextChanged(s: Editable?) = Unit
        })
        renderResults(historyQuery)
    }

    private fun statsScreen(): View = scrollScreen { root ->
        root.addView(topBar("Statistics", "Offer patterns and tracked work time", showSettings = true))

        root.addView(sectionTitle("Offers", "Today · 7 days · 30 days").withTop(dp(22)))
        root.addView(periodCard("Today", database.summarySince(startOfDay(0))).withTop(dp(12)))
        root.addView(periodCard("Last 7 days", database.summarySince(startOfDay(-6))).withTop(dp(9)))
        root.addView(periodCard("Last 30 days", database.summarySince(startOfDay(-29))).withTop(dp(9)))

        root.addView(sectionTitle("Tracked work time", "Manual Start / End shift sessions").withTop(dp(26)))
        val workRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addMetric(workRow, compactCard("TODAY", formatDuration(database.shiftSummarySince(startOfDay(0)).totalMillis)), 0)
        addMetric(workRow, compactCard("7 DAYS", formatDuration(database.shiftSummarySince(startOfDay(-6)).totalMillis)), dp(8))
        addMetric(workRow, compactCard("30 DAYS", formatDuration(database.shiftSummarySince(startOfDay(-29)).totalMillis)), dp(8))
        root.addView(workRow.withTop(dp(12)))

        val records30 = database.recordsSince(startOfDay(-29)).map { it.withCurrentParsedStructure() }
        val shift30 = database.shiftSummarySince(startOfDay(-29))
        if (shift30.totalMillis > 0L) {
            val hours = shift30.totalMillis / 3_600_000.0
            root.addView(card().apply {
                addView(text("Offer arrival rate", 13f, MUTED, true))
                addView(text(String.format(Locale.US, "%.1f offers / tracked hour", records30.size / hours), 20f, TEXT, true).withTop(dp(5)))
                addView(text("Offer frequency, not completed deliveries or earnings per hour.", 11f, MUTED).withTop(dp(4)))
            }.withTop(dp(9)))
        }

        root.addView(sectionTitle("Contribution calendar", "Recent 20 weeks · tap a day").withTop(dp(26)))
        val selected = text("Tap a day to inspect it", 12f, MUTED)
        root.addView(card().apply {
            addView(PilotHeatmapView(this@CourierPilotHomeActivity).apply {
                setWeeks(20)
                setDays(database.dailyStats(365))
                onDaySelected = { day ->
                    selected.text = day?.let {
                        val avg = it.averagePriceCents?.let { cents -> String.format(Locale.US, " · avg €%.2f", cents / 100.0) }.orEmpty()
                        val perKm = it.averageEurPerKm?.let { value -> String.format(Locale.US, " · €%.2f/km", value) }.orEmpty()
                        "${it.day} · ${it.count} offers · W ${it.woltCount} / B ${it.boltCount}$avg$perKm"
                    } ?: "No data"
                }
            })
            addView(selected.withTop(dp(8)))
        }.withTop(dp(12)))

        root.addView(sectionTitle("Offer activity by hour", "Captured offers · not inferred work hours").withTop(dp(26)))
        val recent = database.recent(500)
        root.addView(card().apply {
            addView(PilotHourlyView(this@CourierPilotHomeActivity).apply { setOffers(recent) })
            addView(text(activeHourSummary(recent), 13f, MUTED).withTop(dp(4)))
        }.withTop(dp(12)))

        root.addView(sectionTitle("Single vs stacked", "Last 30 days").withTop(dp(26)))
        val stacked = records30.count { (it.deliveryCount ?: 1) > 1 }
        val single = records30.size - stacked
        val totalStops = records30.sumOf { (it.deliveryCount ?: 1).coerceAtLeast(1) }
        root.addView(card().apply {
            val row = LinearLayout(this@CourierPilotHomeActivity).apply { orientation = LinearLayout.HORIZONTAL }
            addCompactMetric(row, "Single", single.toString())
            addCompactMetric(row, "Stacked", stacked.toString())
            addCompactMetric(row, "Delivery stops", totalStops.toString())
            addView(row)
        }.withTop(dp(12)))

        val topVenues = venueCounts(records30).take(8)
        root.addView(sectionTitle("Top venues", "By captured offers in last 30 days").withTop(dp(26)))
        root.addView(card().apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { navigate(Screen.HISTORY) }
            if (topVenues.isEmpty()) {
                addView(text("No venue names detected yet.", 13f, MUTED))
            } else {
                topVenues.forEachIndexed { index, entry ->
                    if (index > 0) addView(divider().withTop(dp(10)))
                    val row = LinearLayout(this@CourierPilotHomeActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(text(entry.first, 14f, TEXT, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                        addView(text("${entry.second} offers", 12f, MUTED))
                    }
                    addView(row.withTop(if (index == 0) 0 else dp(10)))
                }
            }
        }.withTop(dp(12)))

        val wolt = database.summarySince(startOfDay(-29), "Wolt")
        val bolt = database.summarySince(startOfDay(-29), "Bolt")
        root.addView(sectionTitle("Platform split", "Last 30 days").withTop(dp(26)))
        root.addView(metricGrid(
            platformCard("Wolt", wolt, BLUE) { navigate(Screen.HISTORY) },
            platformCard("Bolt", bolt, GREEN) { navigate(Screen.HISTORY) },
        ).withTop(dp(12)))
    }

    private fun settingsScreen(): View = scrollScreen { root ->
        root.addView(topBar("Settings", "Capture behavior and reliability", showSettings = false, showBack = true))

        val notificationOk = hasNotificationAccess()
        val accessibilityOk = hasAccessibilityAccess()
        root.addView(sectionTitle("Capture health", "Required Android access").withTop(dp(22)))
        root.addView(permissionCard(
            "Notification access",
            notificationOk,
            if (notificationOk) "Connected" else "Required to detect Wolt/Bolt offers",
        ) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }.withTop(dp(12)))
        root.addView(permissionCard(
            "Accessibility capture",
            accessibilityOk,
            if (accessibilityOk) "Connected" else "Required to read prices and save screenshots",
        ) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.withTop(dp(9)))

        root.addView(sectionTitle("Reliability", "Battery, background and lock-screen behavior").withTop(dp(26)))
        root.addView(card().apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { startActivity(Intent(this@CourierPilotHomeActivity, ReliabilityActivity::class.java)) }
            addView(text("Reliability center", 16f, TEXT, true))
            addView(text("Battery optimization, background restrictions, auto-open, wake-screen and privacy-safe event log.", 12f, MUTED).withTop(dp(5)))
            addView(text("Open  ›", 13f, BLUE, true).apply { gravity = Gravity.END }.withTop(dp(10)))
        }.withTop(dp(12)))

        root.addView(text("CourierPilot · ${appVersion()}", 12f, MUTED).apply { gravity = Gravity.CENTER }.withTop(dp(28)))
    }

    private fun topBar(
        title: String,
        subtitle: String,
        showSettings: Boolean,
        showBack: Boolean = false,
        healthy: Boolean? = null,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (showBack) row.addView(backButton { navigate(Screen.HOME) })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 25f, TEXT, true))
            val subRow = LinearLayout(this@CourierPilotHomeActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text(subtitle, 13f, MUTED))
                if (healthy != null) {
                    addView(text(if (healthy) "● Active" else "● Needs attention", 11f, if (healthy) GREEN else RED, true).apply {
                        setPadding(dp(8), 0, 0, 0)
                    })
                }
            }
            addView(subRow.withTop(dp(4)))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (showBack) leftMargin = dp(8)
        })
        if (showSettings) row.addView(settingsButton { navigate(Screen.SETTINGS) })
        return row
    }

    private fun settingsButton(click: () -> Unit): View = ImageButton(this).apply {
        setImageResource(R.drawable.ic_settings_24)
        setBackgroundColor(Color.TRANSPARENT)
        background = roundedDrawable(SURFACE, BORDER, dp(16).toFloat())
        contentDescription = "Settings"
        setPadding(dp(13), dp(13), dp(13), dp(13))
        setOnClickListener { click() }
        elevation = dp(1).toFloat()
    }.also { it.layoutParams = LinearLayout.LayoutParams(dp(50), dp(50)) }

    private fun backButton(click: () -> Unit): View = text("‹", 29f, TEXT, true).apply {
        gravity = Gravity.CENTER
        background = roundedDrawable(SURFACE, BORDER, dp(16).toFloat())
        setOnClickListener { click() }
    }.also { it.layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)) }

    private fun accessWarning(notificationOk: Boolean, accessibilityOk: Boolean): View = card(SOFT_RED, RED_SOFT_BORDER).apply {
        addView(text("Action required", 16f, RED, true))
        addView(text("Capture is paused until required Android access is restored.", 13f, MUTED).withTop(dp(5)))
        if (!notificationOk) addView(actionButton("Enable notification access", true) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }.withTop(dp(12)))
        if (!accessibilityOk) addView(actionButton("Enable accessibility capture", true) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.withTop(dp(8)))
    }

    private fun offerCard(rawRecord: OfferRecord): View {
        val record = rawRecord.withCurrentParsedStructure()
        return card().apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { openOfferDetails(record.id) }
            val top = LinearLayout(this@CourierPilotHomeActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            top.addView(statusPill(record.platform, if (record.platform == "Wolt") BLUE else GREEN, if (record.platform == "Wolt") SOFT_BLUE else SOFT_GREEN))
            if ((record.deliveryCount ?: 1) > 1) {
                top.addView(statusPill("${record.deliveryCount} deliveries", PURPLE, SOFT_PURPLE).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) }
                })
            }
            top.addView(View(this@CourierPilotHomeActivity), LinearLayout.LayoutParams(0, 1, 1f))
            top.addView(text("€${formatCents(record.priceCents)}", 18f, TEXT, true))
            addView(top)

            val merchant = record.merchantNames.takeIf { it.isNotEmpty() }?.joinToString(", ")
                ?: displayRestaurant(record.restaurant)
                ?: "Venue not detected"
            addView(text(merchant, 15f, TEXT, true).withTop(dp(11)))

            if (record.customerNames.isNotEmpty()) {
                addView(text("→ ${record.customerNames.joinToString(" · ")}", 13f, GREEN, true).withTop(dp(5)))
            }
            val routePreview = buildList {
                record.pickupAddresses.firstOrNull()?.let { add("Pickup: $it") }
                record.dropoffAddresses.firstOrNull()?.let { add("Drop-off: $it") }
            }
            if (routePreview.isNotEmpty()) {
                addView(text(routePreview.joinToString("\n"), 11f, MUTED).withTop(dp(5)))
            }

            val details = mutableListOf(formatClock(record.capturedAt))
            record.distanceMeters?.let { details += String.format(Locale.US, "%.2f km", it / 1000.0) }
            eta(record)?.let { details += it }
            addView(text(details.joinToString("  ·  ") + "  ›", 12f, MUTED).withTop(dp(6)))
        }
    }

    private fun matchesSearch(record: OfferRecord, rawQuery: String): Boolean {
        val query = rawQuery.trim().lowercase(Locale.ROOT)
        if (query.isBlank()) return true
        val priceDot = String.format(Locale.US, "%.2f", record.priceCents / 100.0)
        val priceComma = priceDot.replace('.', ',')
        val distanceKm = record.distanceMeters?.let { String.format(Locale.US, "%.2f km", it / 1000.0) }.orEmpty()
        val searchable = buildString {
            appendLine(record.platform)
            appendLine(record.packageName)
            appendLine(record.restaurant.orEmpty())
            appendLine(record.merchantNames.joinToString(" "))
            appendLine(record.pickupAddresses.joinToString(" "))
            appendLine(record.customerNames.joinToString(" "))
            appendLine(record.dropoffAddresses.joinToString(" "))
            appendLine(record.rawText)
            appendLine(record.screenshotFilename)
            appendLine("€$priceDot $priceDot $priceComma")
            appendLine(distanceKm)
            appendLine(record.distanceMeters?.toString().orEmpty())
            appendLine(record.deliveryCount?.toString().orEmpty())
            appendLine(record.estimatedMinutesMin?.toString().orEmpty())
            appendLine(record.estimatedMinutesMax?.toString().orEmpty())
            appendLine(SimpleDateFormat("yyyy-MM-dd EEEE d MMMM HH:mm", Locale.getDefault()).format(Date(record.capturedAt)))
        }.lowercase(Locale.ROOT)
        return query.split(Regex("\\s+")).filter(String::isNotBlank).all { term -> term in searchable }
    }

    private fun permissionCard(title: String, ok: Boolean, subtitle: String, action: () -> Unit): View = card().apply {
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
        val row = LinearLayout(this@CourierPilotHomeActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(statusDot(ok))
        row.addView(LinearLayout(this@CourierPilotHomeActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 15f, TEXT, true))
            addView(text(subtitle, 12f, MUTED).withTop(dp(3)))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10) })
        row.addView(text(if (ok) "Open  ›" else "Fix  ›", 12f, BLUE, true))
        addView(row)
    }

    private fun summaryCard(summary: OfferSummary, click: () -> Unit): View = card().apply {
        isClickable = true
        isFocusable = true
        setOnClickListener { click() }
        val row = LinearLayout(this@CourierPilotHomeActivity).apply { orientation = LinearLayout.HORIZONTAL }
        addCompactMetric(row, "Offers", summary.count.toString())
        addCompactMetric(row, "Avg", formatAveragePrice(summary))
        addCompactMetric(row, "€/km", formatPerKm(summary))
        addView(row)
        addView(text("View statistics  ›", 11f, BLUE, true).apply { gravity = Gravity.END }.withTop(dp(8)))
    }

    private fun periodCard(label: String, summary: OfferSummary): View = card().apply {
        val header = LinearLayout(this@CourierPilotHomeActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(label, 15f, TEXT, true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(text("${summary.count} offers", 13f, MUTED))
        }
        addView(header)
        val metrics = LinearLayout(this@CourierPilotHomeActivity).apply { orientation = LinearLayout.HORIZONTAL }
        addCompactMetric(metrics, "Average", formatAveragePrice(summary))
        addCompactMetric(metrics, "Distance", formatAverageDistance(summary))
        addCompactMetric(metrics, "€/km", formatPerKm(summary))
        addView(metrics.withTop(dp(14)))
    }

    private fun platformCard(label: String, summary: OfferSummary, accent: Int, click: () -> Unit): View = card().apply {
        isClickable = true
        isFocusable = true
        setOnClickListener { click() }
        addView(text(label, 14f, accent, true))
        addView(text(summary.count.toString(), 26f, TEXT, true).withTop(dp(7)))
        addView(text("offers", 12f, MUTED))
        addView(text("Avg ${formatAveragePrice(summary)}", 13f, TEXT, true).withTop(dp(12)))
        addView(text("${formatPerKm(summary)}  ›", 12f, MUTED).withTop(dp(2)))
    }

    private fun metricCard(label: String, value: String, subtitle: String, click: () -> Unit): View = card().apply {
        isClickable = true
        isFocusable = true
        setOnClickListener { click() }
        addView(text(label, 11f, MUTED, true))
        addView(text(value, 27f, TEXT, true).withTop(dp(7)))
        addView(text("$subtitle  ›", 12f, MUTED).withTop(dp(2)))
    }

    private fun compactCard(label: String, value: String): View = card().apply {
        gravity = Gravity.CENTER
        addView(text(value, 17f, TEXT, true).apply { gravity = Gravity.CENTER })
        addView(text(label, 10f, MUTED, true).apply { gravity = Gravity.CENTER }.withTop(dp(3)))
    }

    private fun metricGrid(left: View, right: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addMetric(this, left, 0)
        addMetric(this, right, dp(10))
    }

    private fun sectionTitle(title: String, subtitle: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(title, 19f, TEXT, true))
        addView(text(subtitle, 12f, MUTED).withTop(dp(3)))
    }

    private fun statusPill(label: String, color: Int, backgroundColor: Int): TextView = text(label, 12f, color, true).apply {
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

    private fun divider(): View = View(this).apply {
        setBackgroundColor(BORDER)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
    }

    private fun actionButton(label: String, primary: Boolean = false, click: () -> Unit): TextView = text(label, 13f, if (primary) Color.WHITE else BLUE, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(11), dp(14), dp(11))
        background = if (primary) solidDrawable(BLUE, dp(12).toFloat()) else roundedDrawable(SOFT_BLUE, BLUE_SOFT_BORDER, dp(12).toFloat())
        setOnClickListener { click() }
    }

    private fun linkButton(label: String, click: () -> Unit): TextView = text(label, 13f, BLUE, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { click() }
    }

    private fun navItem(icon: String, label: String, click: () -> Unit): TextView = text("$icon\n$label", 12f, MUTED, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(5), dp(8), dp(5))
        setOnClickListener { click() }
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
            addView(text(value, 17f, TEXT, true).apply { gravity = Gravity.CENTER })
            addView(text(label, 11f, MUTED).apply { gravity = Gravity.CENTER }.withTop(dp(3)))
        }
        row.addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun scrollScreen(content: (LinearLayout) -> Unit): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            clipToPadding = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(32))
        }
        content(root)
        scroll.addView(root)
        return scroll
    }

    private fun weighted() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
        leftMargin = dp(4)
        rightMargin = dp(4)
    }

    private fun openOfferDetails(id: Long) {
        startActivity(Intent(this, OfferDetailsActivity::class.java).putExtra(OfferDetailsActivity.EXTRA_OFFER_ID, id))
    }

    private fun venueCounts(records: List<OfferRecord>): List<Pair<String, Int>> {
        val counts = linkedMapOf<String, Int>()
        records.forEach { record ->
            val names = record.merchantNames.takeIf { it.isNotEmpty() }
                ?: record.restaurant?.let { listOf(it) }.orEmpty()
            names.mapNotNull(::displayRestaurant).distinct().forEach { name -> counts[name] = (counts[name] ?: 0) + 1 }
        }
        return counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase(Locale.getDefault()) })
            .map { it.key to it.value }
    }

    private fun activeHourSummary(records: List<OfferRecord>): String {
        if (records.isEmpty()) return "No captured offers yet."
        val counts = IntArray(24)
        val cal = Calendar.getInstance()
        records.forEach { cal.timeInMillis = it.capturedAt; counts[cal.get(Calendar.HOUR_OF_DAY)]++ }
        val hour = counts.indices.maxByOrNull { counts[it] } ?: return "No captured offers yet."
        return "Most active captured hour: ${hour.toString().padStart(2, '0')}:00 · ${counts[hour]} offers"
    }

    private fun eta(record: OfferRecord): String? = when {
        record.estimatedMinutesMin != null && record.estimatedMinutesMax != null -> "${record.estimatedMinutesMin}–${record.estimatedMinutesMax} min"
        record.estimatedMinutesMin != null -> "${record.estimatedMinutesMin} min"
        else -> null
    }

    private fun displayRestaurant(value: String?): String? {
        val cleaned = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val bad = setOf("close drawer", "online", "offline", "delivery settings", "insights", "finances", "stats", "earn extra", "benefits and insurance", "referrals", "settings", "support", "help center", "info hub", "accept", "decline", "google map", "map marker", "timeline")
        return cleaned.takeUnless { it.lowercase(Locale.getDefault()) in bad }
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

    private fun appVersion(): String = runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown" }.getOrDefault("unknown")
    private fun dayLabel(): String = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date())
    private fun formatClock(timestamp: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    private fun formatAveragePrice(summary: OfferSummary): String = summary.averagePriceCents?.let { String.format(Locale.US, "€%.2f", it / 100.0) } ?: "—"
    private fun formatAverageDistance(summary: OfferSummary): String = summary.averageDistanceMeters?.let { String.format(Locale.US, "%.2f km", it / 1000.0) } ?: "distance —"
    private fun formatPerKm(summary: OfferSummary): String = summary.averageEurPerKm?.let { String.format(Locale.US, "€%.2f/km", it) } ?: "—"
    private fun formatCents(cents: Int): String = String.format(Locale.US, "%.2f", cents / 100.0)
    private fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "0m"
        val minutes = ms / 60_000L
        val hours = minutes / 60
        val rest = minutes % 60
        return if (hours > 0) "${hours}h ${rest}m" else "${rest}m"
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
        layoutParams = if (current is LinearLayout.LayoutParams) current.apply { topMargin = px }
        else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px }
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
        private val PURPLE = Color.parseColor("#7C3AED")
        private val SOFT_BLUE = Color.parseColor("#EFF6FF")
        private val SOFT_GREEN = Color.parseColor("#F0FDF4")
        private val SOFT_RED = Color.parseColor("#FEF2F2")
        private val SOFT_PURPLE = Color.parseColor("#F5F3FF")
        private val BLUE_SOFT_BORDER = Color.parseColor("#BFDBFE")
        private val GREEN_SOFT_BORDER = Color.parseColor("#BBF7D0")
        private val RED_SOFT_BORDER = Color.parseColor("#FECACA")
    }
}
