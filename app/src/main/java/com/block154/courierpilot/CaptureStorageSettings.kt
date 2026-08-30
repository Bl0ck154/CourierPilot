package com.block154.courierpilot

import android.content.Context

/** User-facing storage preferences. OCR still works when gallery screenshots are disabled. */
internal object CaptureStorageSettings {
    private const val PREFS = "courierpilot_capture_storage"
    private const val KEY_SAVE_SCREENSHOTS = "save_offer_screenshots"

    /**
     * Persisting offer screenshots to Pictures/CourierOffers is optional but enabled by default because offer history is expected to retain the captured frame.
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
}
