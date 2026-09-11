package com.block154.courierpilot

import android.content.Context

/** User-facing storage preferences. OCR still works when gallery screenshots are disabled. */
internal object CaptureStorageSettings {
    private const val PREFS = "courierpilot_capture_storage"
    private const val KEY_SAVE_SCREENSHOTS = "save_offer_screenshots"
    private const val KEY_RETENTION_DAYS = "screenshot_retention_days"

    const val RETENTION_FOREVER = 0
    const val DEFAULT_RETENTION_DAYS = 90
    val SUPPORTED_RETENTION_DAYS = listOf(7, 30, 90, RETENTION_FOREVER)

    /**
     * Persisting offer screenshots to Pictures/CourierOffers is optional but enabled by default.
     * Accessibility/OCR capture may still use an in-memory bitmap and recycle it immediately.
     */
    fun saveOfferScreenshots(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SAVE_SCREENSHOTS, true)

    fun setSaveOfferScreenshots(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SAVE_SCREENSHOTS, enabled)
            .apply()
    }

    /** 0 means keep gallery screenshots forever. New installs default to 90 days. */
    fun retentionDays(context: Context): Int =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)
            .takeIf { it in SUPPORTED_RETENTION_DAYS }
            ?: DEFAULT_RETENTION_DAYS

    fun setRetentionDays(context: Context, days: Int) {
        require(days in SUPPORTED_RETENTION_DAYS) { "Unsupported screenshot retention: $days" }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_RETENTION_DAYS, days)
            .apply()
    }
}
