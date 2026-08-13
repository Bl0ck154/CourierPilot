package com.block154.couriernotificationlistener

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var notificationStatus: TextView
    private lateinit var accessibilityStatus: TextView
    private lateinit var lastCapture: TextView
    private lateinit var lastUiText: TextView
    private lateinit var lastError: TextView
    private lateinit var autoOpenSwitch: Switch
    private lateinit var todaySummary: TextView
    private lateinit var weekSummary: TextView
    private lateinit var monthSummary: TextView
    private lateinit var daysContainer: LinearLayout
    private lateinit var recentContainer: LinearLayout

    private val database by lazy { OfferDatabase.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(36))
        }
        scroll.addView(root)

        root.addView(text("Courier Offer Archive", 24f, bold = true))
        root.addView(text(
            "Wolt/Bolt notifications arm capture. The app waits for a real price, uses Accessibility or OCR, then saves exactly that priced screenshot and adds it to local history.",
            15f,
        ).withTop(dp(10)))

        notificationStatus = text("", 15f, bold = true).withTop(dp(22))
        root.addView(notificationStatus)
        root.addView(button("Open notification access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })

        accessibilityStatus = text("", 15f, bold = true).withTop(dp(16))
        root.addView(accessibilityStatus)
        root.addView(button("Open accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        autoOpenSwitch = Switch(this).apply {
            text = "Automatically open Wolt/Bolt on offer notification"
            isChecked = OfferState.autoOpen(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> OfferState.setAutoOpen(this@MainActivity, checked) }
        }
        root.addView(autoOpenSwitch.withTop(dp(18)))
        root.addView(button("Refresh") { refresh() }.withTop(dp(14)))

        root.addView(text("Statistics", 21f, bold = true).withTop(dp(28)))
        todaySummary = text("", 15f).withTop(dp(12))
        weekSummary = text("", 15f).withTop(dp(12))
        monthSummary = text("", 15f).withTop(dp(12))
        root.addView(todaySummary)
        root.addView(weekSummary)
        root.addView(monthSummary)

        root.addView(text("Daily history", 19f, bold = true).withTop(dp(26)))
        daysContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(daysContainer.withTop(dp(8)))

        root.addView(text("Recent offers", 19f, bold = true).withTop(dp(26)))
        root.addView(text("Tap an offer to open its original screenshot.", 13f).withTop(dp(4)))
        recentContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(recentContainer.withTop(dp(8)))

        root.addView(text("Capture status", 19f, bold = true).withTop(dp(28)))
        lastCapture = text("", 14f).withTop(dp(8))
        lastError = text("", 14f).withTop(dp(8))
        lastUiText = text("", 12f).withTop(dp(12))
        root.addView(lastCapture)
        root.addView(lastError)
        root.addView(lastUiText)

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        notificationStatus.text = if (hasNotificationAccess()) {
            "✓ Notification access enabled"
        } else {
            "✗ Notification access is OFF"
        }

        accessibilityStatus.text = if (hasAccessibilityAccess()) {
            "✓ Screenshot accessibility service enabled"
        } else {
            "✗ Screenshot accessibility service is OFF"
        }

        todaySummary.text = buildSummary("Today", startOfDay(0))
        weekSummary.text = buildSummary("Last 7 days", startOfDay(-6))
        monthSummary.text = buildSummary("Last 30 days", startOfDay(-29))
        renderDays()
        renderRecent()

        lastCapture.text = "Last screenshot: ${OfferState.lastCapture(this)}"
        val error = OfferState.lastError(this)
        lastError.visibility = if (error.isBlank()) View.GONE else View.VISIBLE
        lastError.text = "Last status/error: $error"
        lastUiText.text = "Last text seen by Accessibility/OCR:\n${OfferState.lastUiText(this)}"
    }

    private fun buildSummary(title: String, since: Long): String {
        val all = database.summarySince(since)
        val wolt = database.summarySince(since, "Wolt")
        val bolt = database.summarySince(since, "Bolt")
        return buildString {
            append(title).append('\n')
            append(summaryLine("All", all)).append('\n')
            append(summaryLine("Wolt", wolt)).append('\n')
            append(summaryLine("Bolt", bolt))
        }
    }

    private fun summaryLine(label: String, value: OfferSummary): String {
        if (value.count == 0) return "$label: 0 offers"
        val avgPrice = value.averagePriceCents?.let { String.format(Locale.US, "€%.2f", it / 100.0) } ?: "—"
        val avgDistance = value.averageDistanceMeters?.let { String.format(Locale.US, "%.2f km", it / 1000.0) } ?: "—"
        val perKm = value.averageEurPerKm?.let { String.format(Locale.US, "€%.2f/km", it) } ?: "—"
        return "$label: ${value.count} offers · avg $avgPrice · $avgDistance · $perKm"
    }

    private fun renderDays() {
        daysContainer.removeAllViews()
        val days = database.dailyStats(30)
        if (days.isEmpty()) {
            daysContainer.addView(text("No captured offers yet.", 14f))
            return
        }
        days.forEach { day ->
            val avg = day.averagePriceCents?.let { String.format(Locale.US, "€%.2f", it / 100.0) } ?: "—"
            val perKm = day.averageEurPerKm?.let { String.format(Locale.US, "€%.2f/km", it) } ?: "—"
            daysContainer.addView(
                text(
                    "${day.day}  ·  ${day.count} offers  ·  W ${day.woltCount} / B ${day.boltCount}\navg $avg  ·  $perKm",
                    14f,
                ).withTop(dp(8))
            )
        }
    }

    private fun renderRecent() {
        recentContainer.removeAllViews()
        val records = database.recent(40)
        if (records.isEmpty()) {
            recentContainer.addView(text("No priced screenshots captured yet.", 14f))
            return
        }
        val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        records.forEach { record ->
            val distance = record.distanceMeters?.let { String.format(Locale.US, "%.2f km", it / 1000.0) } ?: "distance —"
            val restaurant = record.restaurant?.takeIf { it.isNotBlank() } ?: "restaurant —"
            val label = "${timeFormat.format(Date(record.capturedAt))} · ${record.platform} · €${formatCents(record.priceCents)}\n$distance · $restaurant"
            recentContainer.addView(button(label) { openScreenshot(record.screenshotUri) }.withTop(dp(7)))
        }
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
            refresh()
        }
    }

    private fun startOfDay(dayOffset: Int): Long {
        return Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, dayOffset)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun formatCents(cents: Int): String = String.format(Locale.US, "%.2f", cents / 100.0)

    private fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it)?.packageName == packageName }
    }

    private fun hasAccessibilityAccess(): Boolean {
        if (Settings.Secure.getInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) return false
        val target = ComponentName(this, OfferAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == target }
    }

    private fun text(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setTextIsSelectable(true)
    }

    private fun button(label: String, click: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { click() }
    }

    private fun <T : View> T.withTop(px: Int): T {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = px }
        return this
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
