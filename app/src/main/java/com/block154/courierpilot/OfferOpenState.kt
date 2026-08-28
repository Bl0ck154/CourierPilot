package com.block154.courierpilot

import android.content.Context

/**
 * Cross-service handshake between NotificationListenerService and AccessibilityService.
 *
 * Window visibility and offer visibility are deliberately separate. Opening the generic Bolt/Wolt
 * home activity is not success: the notification listener only considers the open fully verified
 * when Accessibility/OCR recognises an actual offer screen.
 */
internal object OfferOpenState {
    private const val PREFS = "courier_offer_open_state"
    private const val KEY_PACKAGE = "package"
    private const val KEY_NOTIFICATION = "notification"
    private const val KEY_REQUESTED_AT = "requested_at"
    private const val KEY_WINDOW_VISIBLE_AT = "window_visible_at"
    private const val KEY_OFFER_VISIBLE_AT = "offer_visible_at"
    private const val MAX_ATTEMPT_AGE_MS = 30_000L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Returns a generation token used to invalidate delayed callbacks from an older attempt. */
    fun begin(context: Context, packageName: String, notificationKey: String): Long {
        val p = prefs(context)
        val previous = p.getLong(KEY_REQUESTED_AT, 0L)
        val requestedAt = maxOf(System.currentTimeMillis(), previous + 1L)
        p.edit()
            .putString(KEY_PACKAGE, packageName)
            .putString(KEY_NOTIFICATION, notificationKey)
            .putLong(KEY_REQUESTED_AT, requestedAt)
            .remove(KEY_WINDOW_VISIBLE_AT)
            .remove(KEY_OFFER_VISIBLE_AT)
            .apply()
        return requestedAt
    }

    fun isCurrent(
        context: Context,
        packageName: String,
        notificationKey: String,
        requestedAt: Long,
    ): Boolean {
        val p = prefs(context)
        if (p.getString(KEY_PACKAGE, "") != packageName) return false
        if (p.getString(KEY_NOTIFICATION, "") != notificationKey) return false
        if (p.getLong(KEY_REQUESTED_AT, 0L) != requestedAt) return false
        return System.currentTimeMillis() - requestedAt <= MAX_ATTEMPT_AGE_MS
    }

    fun wasWindowVisible(
        context: Context,
        packageName: String,
        notificationKey: String,
        requestedAt: Long,
    ): Boolean {
        if (!isCurrent(context, packageName, notificationKey, requestedAt)) return false
        return prefs(context).getLong(KEY_WINDOW_VISIBLE_AT, 0L) >= requestedAt
    }

    fun wasOfferVisible(
        context: Context,
        packageName: String,
        notificationKey: String,
        requestedAt: Long,
    ): Boolean {
        if (!isCurrent(context, packageName, notificationKey, requestedAt)) return false
        return prefs(context).getLong(KEY_OFFER_VISIBLE_AT, 0L) >= requestedAt
    }

    /** Returns true only for the first window acknowledgement of the current attempt. */
    fun markWindowVisible(context: Context, packageName: String): Boolean {
        val p = prefs(context)
        if (!matchesCurrentPackage(p, packageName)) return false
        val requestedAt = p.getLong(KEY_REQUESTED_AT, 0L)
        if (requestedAt == 0L || System.currentTimeMillis() - requestedAt > MAX_ATTEMPT_AGE_MS) return false
        if (p.getLong(KEY_WINDOW_VISIBLE_AT, 0L) >= requestedAt) return false
        p.edit().putLong(KEY_WINDOW_VISIBLE_AT, System.currentTimeMillis()).apply()
        return true
    }

    /** Marks a real offer screen; also implies that the courier window is visible. */
    fun markOfferVisible(context: Context, packageName: String): Boolean {
        val p = prefs(context)
        if (!matchesCurrentPackage(p, packageName)) return false
        val requestedAt = p.getLong(KEY_REQUESTED_AT, 0L)
        if (requestedAt == 0L || System.currentTimeMillis() - requestedAt > MAX_ATTEMPT_AGE_MS) return false
        if (p.getLong(KEY_OFFER_VISIBLE_AT, 0L) >= requestedAt) return false
        val now = System.currentTimeMillis()
        p.edit()
            .putLong(KEY_WINDOW_VISIBLE_AT, now)
            .putLong(KEY_OFFER_VISIBLE_AT, now)
            .apply()
        return true
    }

    private fun matchesCurrentPackage(
        prefs: android.content.SharedPreferences,
        packageName: String,
    ): Boolean = prefs.getString(KEY_PACKAGE, "") == packageName
}
