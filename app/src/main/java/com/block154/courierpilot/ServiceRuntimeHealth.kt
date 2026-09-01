package com.block154.courierpilot

import android.content.Context
import android.os.SystemClock

/**
 * Runtime liveness markers for the two Android services that power background capture.
 * Permission state alone is not enough: Android/OEM firmware can leave a service enabled in
 * Settings while the process/service is no longer receiving callbacks.
 */
internal object ServiceRuntimeHealth {
    private const val PREFS = "courierpilot_runtime_health"
    private const val KEY_NOTIFICATION_CONNECTED_AT = "notification_connected_at"
    private const val KEY_NOTIFICATION_EVENT_AT = "notification_event_at"
    private const val KEY_ACCESSIBILITY_CONNECTED_AT = "accessibility_connected_at"
    private const val KEY_ACCESSIBILITY_EVENT_AT = "accessibility_event_at"

    data class Snapshot(
        val notificationConnectedAt: Long,
        val notificationEventAt: Long,
        val accessibilityConnectedAt: Long,
        val accessibilityEventAt: Long,
    ) {
        fun notificationLastSeenAt(): Long = maxOf(notificationConnectedAt, notificationEventAt)
        fun accessibilityLastSeenAt(): Long = maxOf(accessibilityConnectedAt, accessibilityEventAt)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markNotificationConnected(context: Context) = write(context, KEY_NOTIFICATION_CONNECTED_AT)
    fun markNotificationEvent(context: Context) = write(context, KEY_NOTIFICATION_EVENT_AT)
    fun markAccessibilityConnected(context: Context) = write(context, KEY_ACCESSIBILITY_CONNECTED_AT)
    fun markAccessibilityEvent(context: Context) = write(context, KEY_ACCESSIBILITY_EVENT_AT)

    fun snapshot(context: Context): Snapshot {
        val p = prefs(context)
        return Snapshot(
            notificationConnectedAt = p.getLong(KEY_NOTIFICATION_CONNECTED_AT, 0L),
            notificationEventAt = p.getLong(KEY_NOTIFICATION_EVENT_AT, 0L),
            accessibilityConnectedAt = p.getLong(KEY_ACCESSIBILITY_CONNECTED_AT, 0L),
            accessibilityEventAt = p.getLong(KEY_ACCESSIBILITY_EVENT_AT, 0L),
        )
    }

    fun ageDescription(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        if (timestamp <= 0L) return "never"
        val ageMs = (now - timestamp).coerceAtLeast(0L)
        return when {
            ageMs < 60_000L -> "just now"
            ageMs < 60L * 60L * 1000L -> "${ageMs / 60_000L} min ago"
            ageMs < 24L * 60L * 60L * 1000L -> "${ageMs / (60L * 60L * 1000L)} h ago"
            else -> "${ageMs / (24L * 60L * 60L * 1000L)} d ago"
        }
    }

    private fun write(context: Context, key: String) {
        prefs(context).edit().putLong(key, System.currentTimeMillis()).apply()
    }
}
