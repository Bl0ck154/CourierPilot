package com.block154.courierpilot

import android.content.Context
import android.os.SystemClock

internal data class PlatformPresence(
    val platform: String,
    val state: PresenceSignal,
    val source: String,
    val updatedAt: Long,
)

/**
 * Tracks Wolt/Bolt online state without a manual Start shift button.
 *
 * Ongoing notifications are useful positive evidence. Notification disappearance is deliberately
 * UNKNOWN rather than OFFLINE: it removes that platform from time accounting until a new positive
 * signal arrives, but never claims that the courier actually went offline.
 */
internal object CourierPresence {
    private const val PREFS = "courierpilot_presence"
    private const val KEY_ACTIVE_PLATFORMS = "active_platforms"
    private const val STRONG_SCREEN_HOLD_MS = 2L * 60L * 1000L

    fun markNotificationOnline(context: Context, packageName: String, now: Long = System.currentTimeMillis()) {
        update(context, packageName, PresenceSignal.ONLINE, "persistent notification", strong = false, now = now)
    }

    fun markOfferOnline(
        context: Context,
        packageName: String,
        source: String = "offer signal",
        now: Long = System.currentTimeMillis(),
    ) {
        update(context, packageName, PresenceSignal.ONLINE, source, strong = true, now = now)
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

    /**
     * SharedPreferences and SQLite survive a device reboot. An open work session must not silently
     * count the period while the phone was powered off. BOOT_COMPLETED arrives shortly after boot,
     * so wall-clock now minus elapsedRealtime approximates the actual boot instant.
     */
    fun resetAfterBoot(context: Context) {
        val now = System.currentTimeMillis()
        val bootWallTime = (now - SystemClock.elapsedRealtime()).coerceIn(0L, now)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet(KEY_ACTIVE_PLATFORMS, emptySet())
            .putString("wolt_state", PresenceSignal.UNKNOWN.name)
            .putString("wolt_source", "device reboot")
            .putLong("wolt_updated_at", now)
            .putLong("wolt_strong_until", 0L)
            .putString("bolt_state", PresenceSignal.UNKNOWN.name)
            .putString("bolt_source", "device reboot")
            .putLong("bolt_updated_at", now)
            .putLong("bolt_strong_until", 0L)
            .apply()
        CourierMetaDatabase.get(context).endAutomaticSession("Device reboot", bootWallTime)
        CaptureEventLog.append(context, "work_reset", "Automatic presence reset after device reboot", dedupeWindowMs = 30_000L)
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
                removeFromTimeAccounting(
                    context = context,
                    prefs = prefs,
                    active = active,
                    packageName = packageName,
                    endReason = "All active courier apps offline · $source",
                    eventStage = "work_offline",
                    eventMessage = "Automatic work session ended from $source",
                    now = now,
                )
            }
            PresenceSignal.UNKNOWN -> {
                // Unknown is not Offline, but continuing to count it as Online would invent work time.
                removeFromTimeAccounting(
                    context = context,
                    prefs = prefs,
                    active = active,
                    packageName = packageName,
                    endReason = "No confirmed online signal · $source",
                    eventStage = "work_uncertain",
                    eventMessage = "Automatic work timer paused until a new online signal",
                    now = now,
                )
            }
        }
    }

    private fun removeFromTimeAccounting(
        context: Context,
        prefs: android.content.SharedPreferences,
        active: MutableSet<String>,
        packageName: String,
        endReason: String,
        eventStage: String,
        eventMessage: String,
        now: Long,
    ) {
        val removed = active.remove(packageName)
        prefs.edit().putStringSet(KEY_ACTIVE_PLATFORMS, active).apply()
        if (removed && active.isEmpty()) {
            CourierMetaDatabase.get(context).endAutomaticSession(endReason, now)
            CaptureEventLog.append(context, eventStage, eventMessage, OfferState.platformLabel(packageName))
        }
    }

    private fun prefix(packageName: String): String = when (packageName) {
        CourierSignals.WOLT_PACKAGE -> "wolt"
        CourierSignals.BOLT_PACKAGE -> "bolt"
        else -> packageName.replace('.', '_')
    }
}
