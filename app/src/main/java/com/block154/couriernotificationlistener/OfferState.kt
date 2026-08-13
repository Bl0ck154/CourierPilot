package com.block154.couriernotificationlistener

import android.content.Context

internal data class PendingOffer(
    val packageName: String,
    val sourceName: String,
    val armedAt: Long,
)

internal object OfferState {
    private const val PREFS = "courier_offer_capture"
    private const val KEY_PACKAGE = "pending_package"
    private const val KEY_SOURCE = "pending_source"
    private const val KEY_ARMED_AT = "pending_armed_at"
    private const val KEY_LAST_CAPTURE = "last_capture"
    private const val KEY_LAST_UI_TEXT = "last_ui_text"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_AUTO_OPEN = "auto_open"
    private const val MAX_PENDING_AGE_MS = 180_000L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun arm(context: Context, packageName: String, sourceName: String) {
        prefs(context).edit()
            .putString(KEY_PACKAGE, packageName)
            .putString(KEY_SOURCE, sourceName)
            .putLong(KEY_ARMED_AT, System.currentTimeMillis())
            .putString(KEY_LAST_ERROR, "")
            .apply()
    }

    fun pending(context: Context): PendingOffer? {
        val p = prefs(context)
        val packageName = p.getString(KEY_PACKAGE, null) ?: return null
        val armedAt = p.getLong(KEY_ARMED_AT, 0L)
        if (armedAt == 0L || System.currentTimeMillis() - armedAt > MAX_PENDING_AGE_MS) {
            clear(context)
            markError(context, "Offer expired before a price was detected; no screenshot was saved")
            return null
        }
        return PendingOffer(
            packageName = packageName,
            sourceName = p.getString(KEY_SOURCE, packageName) ?: packageName,
            armedAt = armedAt,
        )
    }

    fun clear(context: Context) {
        prefs(context).edit()
            .remove(KEY_PACKAGE)
            .remove(KEY_SOURCE)
            .remove(KEY_ARMED_AT)
            .apply()
    }

    fun saveUiText(context: Context, text: String) {
        prefs(context).edit().putString(KEY_LAST_UI_TEXT, text.take(12000)).apply()
    }

    fun markCapture(context: Context, filename: String) {
        prefs(context).edit()
            .putString(KEY_LAST_CAPTURE, filename)
            .putString(KEY_LAST_ERROR, "")
            .apply()
    }

    fun markError(context: Context, message: String) {
        prefs(context).edit().putString(KEY_LAST_ERROR, message).apply()
    }

    fun lastCapture(context: Context): String =
        prefs(context).getString(KEY_LAST_CAPTURE, "No screenshots yet") ?: "No screenshots yet"

    fun lastUiText(context: Context): String =
        prefs(context).getString(KEY_LAST_UI_TEXT, "No offer UI/OCR text captured yet")
            ?: "No offer UI/OCR text captured yet"

    fun lastError(context: Context): String =
        prefs(context).getString(KEY_LAST_ERROR, "") ?: ""

    fun autoOpen(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_OPEN, false)

    fun setAutoOpen(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_OPEN, enabled).apply()
    }
}
