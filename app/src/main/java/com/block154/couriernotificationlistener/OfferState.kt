package com.block154.couriernotificationlistener

import android.content.Context

internal data class PendingOffer(
    val packageName: String,
    val sourceName: String,
    val armedAt: Long,
    val notificationKey: String = "",
)

internal object OfferState {
    private const val PREFS = "courier_offer_capture"
    private const val KEY_LAST_CAPTURE = "last_capture"
    private const val KEY_LAST_UI_TEXT = "last_ui_text"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_AUTO_OPEN = "auto_open"
    private const val MAX_PENDING_AGE_MS = 180_000L

    private val courierPackages = listOf(
        "com.wolt.courierapp",
        "com.bolt.deliverycourier",
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun slot(packageName: String): String = when {
        packageName.contains("wolt", ignoreCase = true) -> "wolt"
        packageName.contains("bolt", ignoreCase = true) -> "bolt"
        else -> packageName.hashCode().toString()
    }

    private fun key(packageName: String, field: String): String = "pending_${slot(packageName)}_$field"

    /**
     * Existing listener-compatible entry point. If this platform already has a live
     * pending offer, notification updates do not restart its three-minute timer.
     */
    fun arm(context: Context, packageName: String, sourceName: String) {
        if (pending(context, packageName) != null) return
        writePending(context, packageName, sourceName, "")
    }

    fun arm(
        context: Context,
        packageName: String,
        sourceName: String,
        notificationKey: String,
    ): Boolean {
        val existing = pending(context, packageName)
        if (existing != null && existing.notificationKey == notificationKey) return false
        if (existing != null && existing.notificationKey.isBlank()) return false
        writePending(context, packageName, sourceName, notificationKey)
        return true
    }

    private fun writePending(
        context: Context,
        packageName: String,
        sourceName: String,
        notificationKey: String,
    ) {
        prefs(context).edit()
            .putString(key(packageName, "package"), packageName)
            .putString(key(packageName, "source"), sourceName)
            .putString(key(packageName, "notification_key"), notificationKey)
            .putLong(key(packageName, "armed_at"), System.currentTimeMillis())
            .putString(KEY_LAST_ERROR, "")
            .apply()
    }

    fun pending(context: Context, packageName: String): PendingOffer? {
        val p = prefs(context)
        val storedPackage = p.getString(key(packageName, "package"), null) ?: return null
        val armedAt = p.getLong(key(packageName, "armed_at"), 0L)
        if (armedAt == 0L || System.currentTimeMillis() - armedAt > MAX_PENDING_AGE_MS) {
            clearPackage(context, packageName)
            markError(context, "${platformLabel(packageName)} offer expired before a price was detected; no screenshot was saved")
            return null
        }
        return PendingOffer(
            packageName = storedPackage,
            sourceName = p.getString(key(packageName, "source"), storedPackage) ?: storedPackage,
            armedAt = armedAt,
            notificationKey = p.getString(key(packageName, "notification_key"), "") ?: "",
        )
    }

    /** Compatibility helper for older callers; newest live pending offer wins. */
    fun pending(context: Context): PendingOffer? = courierPackages
        .mapNotNull { pending(context, it) }
        .maxByOrNull { it.armedAt }

    fun isCurrent(context: Context, offer: PendingOffer): Boolean {
        val current = pending(context, offer.packageName) ?: return false
        return current.armedAt == offer.armedAt &&
            current.notificationKey == offer.notificationKey &&
            current.packageName == offer.packageName
    }

    /** A stale async callback can never clear a newer pending offer. */
    fun clearIfCurrent(context: Context, offer: PendingOffer): Boolean {
        if (!isCurrent(context, offer)) return false
        clearPackage(context, offer.packageName)
        return true
    }

    /** Compatibility helper; clears only the newest pending offer, never both platforms. */
    fun clear(context: Context) {
        pending(context)?.let { clearPackage(context, it.packageName) }
    }

    private fun clearPackage(context: Context, packageName: String) {
        prefs(context).edit()
            .remove(key(packageName, "package"))
            .remove(key(packageName, "source"))
            .remove(key(packageName, "notification_key"))
            .remove(key(packageName, "armed_at"))
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

    private fun platformLabel(packageName: String): String = when {
        packageName.contains("wolt", ignoreCase = true) -> "Wolt"
        packageName.contains("bolt", ignoreCase = true) -> "Bolt"
        else -> "Courier"
    }
}
