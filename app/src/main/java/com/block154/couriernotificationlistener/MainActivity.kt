package com.block154.couriernotificationlistener

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var notificationStatus: TextView
    private lateinit var accessibilityStatus: TextView
    private lateinit var lastCapture: TextView
    private lateinit var lastUiText: TextView
    private lateinit var lastError: TextView
    private lateinit var autoOpenSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }
        scroll.addView(root)

        root.addView(text("Courier Offer Capture", 24f, bold = true))
        root.addView(text(
            "A Wolt/Bolt offer notification arms the capture. The accessibility service then waits for the real offer screen and saves a PNG to Pictures/CourierOffers.",
            16f,
        ).withTop(dp(12)))

        notificationStatus = text("", 16f, bold = true).withTop(dp(24))
        root.addView(notificationStatus)
        root.addView(button("Open notification access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })

        accessibilityStatus = text("", 16f, bold = true).withTop(dp(20))
        root.addView(accessibilityStatus)
        root.addView(button("Open accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        autoOpenSwitch = Switch(this).apply {
            text = "Automatically open Wolt/Bolt when an offer notification arrives"
            isChecked = OfferState.autoOpen(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                OfferState.setAutoOpen(this@MainActivity, checked)
            }
        }
        root.addView(autoOpenSwitch.withTop(dp(24)))
        root.addView(text(
            "If Android allows the notification PendingIntent to launch, this removes the need to bring the courier app to the foreground with Tasker. Leave it off if you prefer to open offers yourself.",
            14f,
        ).withTop(dp(6)))

        root.addView(button("Refresh status") { refresh() }.withTop(dp(24)))

        lastCapture = text("", 15f).withTop(dp(24))
        lastError = text("", 15f).withTop(dp(10))
        lastUiText = text("", 14f).withTop(dp(18))
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

        lastCapture.text = "Last screenshot:\n${OfferState.lastCapture(this)}"
        val error = OfferState.lastError(this)
        lastError.visibility = if (error.isBlank()) View.GONE else View.VISIBLE
        lastError.text = "Last error:\n$error"
        lastUiText.text = "Last offer UI text seen by Accessibility:\n\n${OfferState.lastUiText(this)}"
    }

    private fun hasNotificationAccess(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.split(':').any {
            ComponentName.unflattenFromString(it)?.packageName == packageName
        }
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
