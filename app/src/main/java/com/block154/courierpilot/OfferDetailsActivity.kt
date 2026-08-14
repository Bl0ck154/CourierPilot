package com.block154.courierpilot

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OfferDetailsActivity : Activity() {

    private val database by lazy { OfferDatabase.get(this) }
    private var rawExpanded = false
    private var offerId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        offerId = intent.getLongExtra(EXTRA_OFFER_ID, -1L)
        render()
    }

    private fun render() {
        val record = database.findById(offerId)?.withCurrentParsedStructure()
        if (record == null) {
            val screen = messageScreen("Offer not found")
            setContentView(screen)
            screen.applySystemBarsPadding()
            return
        }
        val screen = buildScreen(record)
        setContentView(screen)
        screen.applySystemBarsPadding()
    }

    private fun buildScreen(record: OfferRecord): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(BG) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(34))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(button("‹") { finish() }, LinearLayout.LayoutParams(dp(46), dp(46)))
            addView(LinearLayout(this@OfferDetailsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text("Offer details", 24f, TEXT, true))
                addView(text(formatDate(record.capturedAt), 12f, MUTED).top(dp(3)))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8) })
        }
        root.addView(header)

        val hero = card().apply {
            val top = LinearLayout(this@OfferDetailsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(pill(record.platform))
                addView(View(this@OfferDetailsActivity), LinearLayout.LayoutParams(0, 1, 1f))
                addView(text("€${formatCents(record.priceCents)}", 30f, TEXT, true))
            }
            addView(top)
            val merchant = record.merchantNames.takeIf { it.isNotEmpty() }?.joinToString(", ")
                ?: record.restaurant
                ?: "Venue not detected"
            addView(text(merchant, 18f, TEXT, true).top(dp(14)))

            val facts = mutableListOf<String>()
            record.distanceMeters?.let { facts += String.format(Locale.US, "%.2f km", it / 1000.0) }
            record.deliveryCount?.let { facts += "$it ${if (it == 1) "delivery" else "deliveries"}" }
            eta(record)?.let { facts += "ETA $it" }
            eurPerKm(record)?.let { facts += String.format(Locale.US, "€%.2f/km", it) }
            if (facts.isNotEmpty()) addView(text(facts.joinToString("  ·  "), 13f, MUTED).top(dp(8)))
        }
        root.addView(hero.top(dp(22)))

        if (record.merchantNames.isNotEmpty() || record.pickupAddresses.isNotEmpty()) {
            root.addView(sectionTitle("Pickup", "Venue${if (record.merchantNames.size > 1) "s" else ""} and pickup addresses").top(dp(24)))
            val count = maxOf(record.merchantNames.size, record.pickupAddresses.size)
            for (i in 0 until count) {
                root.addView(stopCard(
                    badge = "P${if (count > 1) i + 1 else ""}",
                    title = record.merchantNames.getOrNull(i) ?: "Pickup",
                    subtitle = record.pickupAddresses.getOrNull(i),
                    accent = BLUE,
                ).top(dp(9)))
            }
        }

        if (record.customerNames.isNotEmpty() || record.dropoffAddresses.isNotEmpty()) {
            root.addView(sectionTitle("Drop-off", "Customer and destination from the courier app").top(dp(24)))
            val count = maxOf(record.customerNames.size, record.dropoffAddresses.size)
            for (i in 0 until count) {
                root.addView(stopCard(
                    badge = "D${if (count > 1) i + 1 else ""}",
                    title = record.customerNames.getOrNull(i) ?: "Customer",
                    subtitle = record.dropoffAddresses.getOrNull(i),
                    accent = GREEN,
                ).top(dp(9)))
            }
            root.addView(text("Customer names and addresses stay in CourierPilot's local database on this device.", 11f, MUTED).top(dp(8)))
        }

        if (record.customerNames.isEmpty() && record.dropoffAddresses.isEmpty() && record.pickupAddresses.isEmpty()) {
            root.addView(card().apply {
                addView(text("Route details were not exposed clearly enough to classify this offer.", 13f, MUTED))
                addView(text("The original screenshot and raw capture text are still available below.", 11f, MUTED).top(dp(4)))
            }.top(dp(24)))
        }

        root.addView(sectionTitle("Original", "Saved evidence and parser diagnostics").top(dp(24)))
        root.addView(actionButton("Open original screenshot") { openScreenshot(record.screenshotUri) }.top(dp(10)))
        root.addView(linkButton(if (rawExpanded) "Hide raw Accessibility / OCR text" else "Show raw Accessibility / OCR text") {
            rawExpanded = !rawExpanded
            render()
        }.top(dp(8)))
        if (rawExpanded) {
            root.addView(card().apply {
                addView(text(record.rawText.ifBlank { "No raw text stored." }, 12f, MUTED).apply { setTextIsSelectable(true) })
            }.top(dp(8)))
        }

        scroll.addView(root)
        return scroll
    }

    private fun stopCard(badge: String, title: String, subtitle: String?, accent: Int): View = card().apply {
        val row = LinearLayout(this@OfferDetailsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        row.addView(text(badge, 11f, accent, true).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.WHITE, accent, dp(20).toFloat())
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        row.addView(LinearLayout(this@OfferDetailsActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 15f, TEXT, true))
            subtitle?.takeIf { it.isNotBlank() }?.let { addView(text(it, 13f, MUTED).top(dp(4))) }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
        addView(row)
    }

    private fun sectionTitle(title: String, subtitle: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(title, 18f, TEXT, true))
        addView(text(subtitle, 12f, MUTED).top(dp(3)))
    }

    private fun pill(platform: String): View = text(platform, 12f, if (platform == "Wolt") BLUE else GREEN, true).apply {
        setPadding(dp(11), dp(6), dp(11), dp(6))
        background = solid(if (platform == "Wolt") SOFT_BLUE else SOFT_GREEN, dp(30).toFloat())
    }

    private fun actionButton(label: String, click: () -> Unit): View = text(label, 14f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(13), dp(14), dp(13))
        background = solid(BLUE, dp(14).toFloat())
        setOnClickListener { click() }
    }

    private fun linkButton(label: String, click: () -> Unit): View = text(label, 13f, BLUE, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setOnClickListener { click() }
    }

    private fun button(label: String, click: () -> Unit): TextView = text(label, 28f, TEXT, true).apply {
        gravity = Gravity.CENTER
        background = solid(Color.WHITE, dp(14).toFloat())
        setOnClickListener { click() }
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

    private fun solid(fill: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radius
    }

    private fun <T : View> T.top(value: Int): T {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = value }
        return this
    }

    private fun openScreenshot(uriString: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(uriString), "image/png")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure {
            CaptureEventLog.append(this, "ui_error", "Could not open stored screenshot: ${it.javaClass.simpleName}")
        }
    }

    private fun messageScreen(message: String): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        setBackgroundColor(BG)
        addView(text(message, 18f, TEXT, true))
    }

    private fun eta(record: OfferRecord): String? = when {
        record.estimatedMinutesMin != null && record.estimatedMinutesMax != null -> "${record.estimatedMinutesMin}–${record.estimatedMinutesMax} min"
        record.estimatedMinutesMin != null -> "${record.estimatedMinutesMin} min"
        else -> null
    }

    private fun eurPerKm(record: OfferRecord): Double? {
        val distance = record.distanceMeters ?: return null
        if (distance <= 0) return null
        return record.priceCents * 10.0 / distance
    }

    private fun formatDate(timestamp: Long): String = SimpleDateFormat("EEE, d MMM · HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    private fun formatCents(cents: Int): String = String.format(Locale.US, "%.2f", cents / 100.0)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_OFFER_ID = "offer_id"
        private val BG = Color.parseColor("#F5F7FB")
        private val TEXT = Color.parseColor("#111827")
        private val MUTED = Color.parseColor("#6B7280")
        private val BORDER = Color.parseColor("#E5E7EB")
        private val BLUE = Color.parseColor("#2563EB")
        private val GREEN = Color.parseColor("#16A34A")
        private val SOFT_BLUE = Color.parseColor("#EFF6FF")
        private val SOFT_GREEN = Color.parseColor("#F0FDF4")
    }
}
