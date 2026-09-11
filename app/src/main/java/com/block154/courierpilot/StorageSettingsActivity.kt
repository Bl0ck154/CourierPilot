package com.block154.courierpilot

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Small storage screen kept independent from the main dashboard so maintenance stays low-risk. */
class StorageSettingsActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var saveToggle: Switch
    private lateinit var retentionLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "CourierPilot storage"

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
        }
        content.addView(TextView(this).apply {
            text = "Offer screenshot storage"
            textSize = 22f
        })
        content.addView(TextView(this).apply {
            text = "OCR still works when gallery screenshots are disabled. Retention only removes old PNG files from Pictures/CourierOffers; offer history and parsed data stay intact."
            textSize = 14f
            setPadding(0, dp(8), 0, dp(16))
        })

        saveToggle = Switch(this).apply {
            text = "Save offer screenshots"
            isChecked = CaptureStorageSettings.saveOfferScreenshots(this@StorageSettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                CaptureStorageSettings.setSaveOfferScreenshots(this@StorageSettingsActivity, checked)
                refreshStats()
            }
        }
        content.addView(saveToggle)

        retentionLabel = TextView(this).apply {
            textSize = 16f
            setPadding(0, dp(18), 0, dp(8))
        }
        content.addView(retentionLabel)

        val retentionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(7, 30, 90, CaptureStorageSettings.RETENTION_FOREVER).forEach { days ->
            retentionRow.addView(Button(this).apply {
                text = if (days == 0) "Forever" else "${days}d"
                setOnClickListener {
                    CaptureStorageSettings.setRetentionDays(this@StorageSettingsActivity, days)
                    refreshStats()
                    runCleanup(force = true)
                }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        content.addView(retentionRow)

        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(18), 0, dp(12))
        }
        content.addView(status)

        content.addView(Button(this).apply {
            text = "Clean now"
            setOnClickListener { runCleanup(force = true) }
        })
        content.addView(Button(this).apply {
            text = "Back"
            setOnClickListener { finish() }
        })

        setContentView(ScrollView(this).apply { addView(content) })
        refreshStats()
    }

    override fun onResume() {
        super.onResume()
        saveToggle.isChecked = CaptureStorageSettings.saveOfferScreenshots(this)
        refreshStats()
    }

    private fun runCleanup(force: Boolean) {
        status.text = "Scanning screenshots…"
        Thread({
            val result = ScreenshotRetentionManager.runIfDue(this, force)
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (result.deletedCount > 0) {
                        "Deleted ${result.deletedCount} old screenshots (${formatBytes(result.deletedBytes)})"
                    } else {
                        "Nothing old enough to delete"
                    },
                    Toast.LENGTH_SHORT,
                ).show()
                refreshStats()
            }
        }, "CourierPilot-storage-cleanup").apply { isDaemon = true }.start()
    }

    private fun refreshStats() {
        val retention = CaptureStorageSettings.retentionDays(this)
        retentionLabel.text = "Retention: ${if (retention == 0) "Forever" else "$retention days"}"
        Thread({
            val stats = ScreenshotRetentionManager.stats(this)
            val oldest = stats.oldestAt.takeIf { it > 0L }?.let {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
            } ?: "—"
            runOnUiThread {
                status.text = buildString {
                    append("Screenshots: ${stats.count}\n")
                    append("Storage: ${formatBytes(stats.bytes)}\n")
                    append("Oldest: $oldest\n")
                    append("Folder: Pictures/CourierOffers")
                }
            }
        }, "CourierPilot-storage-stats").apply { isDaemon = true }.start()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
