package com.block154.courierpilot

import android.content.Context

internal data class PlatformPresence(
    val platform: String,
    val state: PresenceSignal,
    val source: String,
    val updatedAt: Long,
)

/**
 * Tracks Wolt/Bolt online state without a manual Start shift button.
 *
 * Ongoing notifications are useful positive evidence, but notification disappearance is deliberately
 * treated as UNKNOWN because users can swipe notifications and Bolt can occasionally leave stale
 * ones behind. A strong on-screen OFFLINE signal is what removes a platform from the active set.
 */
internal object CourierPresence {
    private const val PREFS = "courierpilot_presence"
    private const val KEY_ACTIVE_PLATFORMS = "active_platforms"
    private const val STRONG_SCREEN_HOLD_MS = 2L * 60L * 1000L

    fun markNotificationOnline(context: Context, packageName: String, now: Long = System.currentTimeMillis()) {
        update(context, packageName, PresenceSignal.ONLINE, "persistent notification", strong = false, now = now)
    }

    fun markNotificationUnknown(context: Context, packageName: String, now: Long = System.currentTimeMillis()) {
        update(context, packageName, PresenceSignal.UNKNOWN, "notification disappeared", strong = false, now = now)
    }

    fun markScreen(context: Context, packageName: String, signal: PresenceSignal, now: Long = System.currentTimeMillis()) {
        if (signal == PresenceSignal.UNKNOWN) return
        update(context, packageName, signal, "courier screen", strong = true, now = now)
    }

    fun markExplicitNotification(context: Context, packageName: String, signal: PresenceSignal, now: Long = System.currentTimeMillis()) {
        if (signal == PresenceSignal.UNKNOWN) return
        update(context, packageName, signal, "notification text", strong = true, now = now)
    }

    fun platformPresence(context: Context, packageName: String): PlatformPresence {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = prefix(packageName)
        val raw = prefs.getString("${prefix}_state", PresenceSignal.UNKNOWN.name) ?: PresenceSignal.UNKNOWN.name
        return PlatformPresence(
            platform = OfferState.platformLabel(packageName),
            state = runCatching { PresenceSignal.valueOf(raw) }.getOrDefault(PresenceSignal.UNKNOWN),
            source = prefs.getString("${prefix}_source", "No signal yet") ?: "No signal yet",
            updatedAt = prefs.getLong("${prefix}_updated_at", 0L),
        )
    }

    fun all(context: Context): List<PlatformPresence> = listOf(
        platformPresence(context, CourierSignals.WOLT_PACKAGE),
        platformPresence(context, CourierSignals.BOLT_PACKAGE),
    )

    private fun update(
        context: Context,
        packageName: String,
        state: PresenceSignal,
        source: String,
        strong: Boolean,
        now: Long,
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = prefix(packageName)
        val currentStrongUntil = prefs.getLong("${key}_strong_until", 0L)
        val currentRaw = prefs.getString("${key}_state", PresenceSignal.UNKNOWN.name) ?: PresenceSignal.UNKNOWN.name
        val current = runCatching { PresenceSignal.valueOf(currentRaw) }.getOrDefault(PresenceSignal.UNKNOWN)

        // A stale/hanging ongoing notification must not immediately undo an explicit screen OFFLINE.
        if (!strong && state == PresenceSignal.ONLINE && current == PresenceSignal.OFFLINE && now < currentStrongUntil) return

        prefs.edit()
            .putString("${key}_state", state.name)
            .putString("${key}_source", source)
            .putLong("${key}_updated_at", now)
            .apply {
                if (strong) putLong("${key}_strong_until", now + STRONG_SCREEN_HOLD_MS)
            }
            .apply()

        val active = prefs.getStringSet(KEY_ACTIVE_PLATFORMS, emptySet()).orEmpty().toMutableSet()
        when (state) {
            PresenceSignal.ONLINE -> {
                val wasEmpty = active.isEmpty()
                active += packageName
                prefs.edit().putStringSet(KEY_ACTIVE_PLATFORMS, active).apply()
                if (wasEmpty) {
                    CourierMetaDatabase.get(context).startAutomaticSession("${OfferState.platformLabel(packageName)} online · $source", now)
                    CaptureEventLog.append(context, "work_online", "Automatic work session started from $source", OfferState.platformLabel(packageName))
                }
            }
            PresenceSignal.OFFLINE -> {
                val removed = active.remove(packageName)
                prefs.edit().putStringSet(KEY_ACTIVE_PLATFORMS, active).apply()
                if (removed && active.isEmpty()) {
                    CourierMetaDatabase.get(context).endAutomaticSession("All active courier apps offline · $source", now)
                    CaptureEventLog.append(context, "work_offline", "Automatic work session ended from $source", OfferState.platformLabel(packageName))
                }
            }
            PresenceSignal.UNKNOWN -> {
                // Keep the active membership unchanged. Absence of a notification is not proof of offline.
            }
        }
    }

    private fun prefix(packageName: String): String = when (packageName) {
        CourierSignals.WOLT_PACKAGE -> "wolt"
        CourierSignals.BOLT_PACKAGE -> "bolt"
        else -> packageName.replace('.', '_')
    }
}
