package com.block154.courierpilot

import android.content.Context

/**
 * Tiny cross-service handshake between NotificationListenerService and AccessibilityService.
 *
 * Sending a notification PendingIntent only proves that Android accepted the request; it does not
 * prove that the courier activity actually became visible. The notification listener records an
 * attempt here, and the accessibility service acknowledges it when it observes the target package.
 */
internal object OfferOpenState {
    private const val PREFS = "courier_offer_open_state"
    private const val KEY_PACKAGE = "package"
    private const val KEY_NOTIFICATION = "notification"
    private const val KEY_REQUESTED_AT = "requested_at"
    private const val KEY_VISIBLE_AT = "visible_at"
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
            .remove(KEY_VISIBLE_AT)
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

    fun wasVisible(
        context: Context,
        packageName: String,
        notificationKey: String,
        requestedAt: Long,
    ): Boolean {
        if (!isCurrent(context, packageName, notificationKey, requestedAt)) return false
        return prefs(context).getLong(KEY_VISIBLE_AT, 0L) >= requestedAt
    }

    /** Returns true only for the first visibility acknowledgement of the current attempt. */
    fun markVisible(context: Context, packageName: String): Boolean {
        val p = prefs(context)
        if (p.getString(KEY_PACKAGE, "") != packageName) return false
        val requestedAt = p.getLong(KEY_REQUESTED_AT, 0L)
        if (requestedAt == 0L || System.currentTimeMillis() - requestedAt > MAX_ATTEMPT_AGE_MS) return false
        if (p.getLong(KEY_VISIBLE_AT, 0L) >= requestedAt) return false
        p.edit().putLong(KEY_VISIBLE_AT, System.currentTimeMillis()).apply()
        return true
    }
}
