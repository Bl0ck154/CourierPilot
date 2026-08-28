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
 * Presence is intentionally evidence-based. A foreground-service/persistent notification only
 * proves that Android keeps part of the courier app alive; it does not prove that the courier is
 * accepting orders. Real offers and explicit online/offline wording are strong signals.
 */
internal object CourierPresence {
    private const val PREFS = "courierpilot_presence"
    private const val KEY_ACTIVE_PLATFORMS = "active_platforms"
    private const val STRONG_SCREEN_HOLD_MS = 2L * 60L * 1000L
    internal const val ONLINE_SIGNAL_TTL_MS = 30L * 60L * 1000L
    private const val LEGACY_PERSISTENT_SOURCE = "persistent notification"

    fun markOfferOnline(
        context: Context,
        packageName: String,
        source: String = "offer signal",
        now: Long = System.currentTimeMillis(),
    ) {
        update(context, packageName, PresenceSignal.ONLINE, source, strong = true, now = now)
    }

    fun markNotificationUnknown(context: Context, packageName: String, now: Long = System.currentTimeMillis()) {
        update(context, packageName, PresenceSignal.UNKNOWN, "notification not explicit", strong = false, now = now)
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
        reconcileStaleOnline(context)
        repairLegacyPersistentOnline(context, packageName)
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

    /**
     * A real offer proves ONLINE at that moment, but it must not keep a work session alive forever.
     * If no fresh explicit online/offer evidence arrives for 30 minutes, close the automatic work
     * session at the expiry boundary rather than at the much later time when the UI happens to open.
     */
    fun reconcileStaleOnline(context: Context, now: Long = System.currentTimeMillis()) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val active = prefs.getStringSet(KEY_ACTIVE_PLATFORMS, emptySet()).orEmpty().toMutableSet()
        if (active.isEmpty()) return

        val remaining = active.toMutableSet()
        val expiryTimes = mutableListOf<Long>()
        val editor = prefs.edit()
        var changed = false

        active.forEach { packageName ->
            val key = prefix(packageName)
            val raw = prefs.getString("${key}_state", PresenceSignal.UNKNOWN.name)
            val state = runCatching { PresenceSignal.valueOf(raw ?: PresenceSignal.UNKNOWN.name) }
                .getOrDefault(PresenceSignal.UNKNOWN)
            val updatedAt = prefs.getLong("${key}_updated_at", 0L)

            val stale = state != PresenceSignal.ONLINE ||
                updatedAt <= 0L ||
                now - updatedAt >= ONLINE_SIGNAL_TTL_MS
            if (!stale) return@forEach

            val expiry = when {
                state != PresenceSignal.ONLINE -> now
                updatedAt <= 0L -> now
                else -> (updatedAt + ONLINE_SIGNAL_TTL_MS).coerceAtMost(now)
            }
            remaining.remove(packageName)
            expiryTimes += expiry
            changed = true
            editor
                .putString("${key}_state", PresenceSignal.UNKNOWN.name)
                .putString("${key}_source", "online signal expired")
                .putLong("${key}_updated_at", expiry)
                .putLong("${key}_strong_until", 0L)
        }

        if (!changed) return
        editor.putStringSet(KEY_ACTIVE_PLATFORMS, remaining).apply()

        if (active.isNotEmpty() && remaining.isEmpty()) {
            val endedAt = expiryTimes.maxOrNull()?.coerceAtMost(now) ?: now
            CourierMetaDatabase.get(context).endAutomaticSession("Online signal expired", endedAt)
            CaptureEventLog.append(
                context,
                "work_stale",
                "Automatic work session closed after 30 min without fresh online evidence",
                dedupeWindowMs = 30_000L,
            )
        }
    }

    /**
     * Releases false ONLINE values written by pre-0.14 builds from an unqualified sticky
     * notification. This runs lazily once when presence is next read after upgrade and also removes
     * that platform from automatic work-time accounting.
     */
    private fun repairLegacyPersistentOnline(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = prefix(packageName)
        val state = prefs.getString("${key}_state", PresenceSignal.UNKNOWN.name)
        val source = prefs.getString("${key}_source", "")
        if (state != PresenceSignal.ONLINE.name || source != LEGACY_PERSISTENT_SOURCE) return
        update(
            context = context,
            packageName = packageName,
            state = PresenceSignal.UNKNOWN,
            source = "legacy persistent notification discarded",
            strong = false,
            now = System.currentTimeMillis(),
        )
    }

    private fun update(
        context: Context,
        packageName: String,
        state: PresenceSignal,
        source: String,
        strong: Boolean,
        now: Long,
    ) {
        // Close a previously stale session before applying the next signal. Otherwise two offers
        // hours apart could silently bridge the entire idle gap if the dashboard was never opened.
        reconcileStaleOnline(context, now)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = prefix(packageName)
        val currentStrongUntil = prefs.getLong("${key}_strong_until", 0L)
        val currentRaw = prefs.getString("${key}_state", PresenceSignal.UNKNOWN.name) ?: PresenceSignal.UNKNOWN.name
        val current = runCatching { PresenceSignal.valueOf(currentRaw) }.getOrDefault(PresenceSignal.UNKNOWN)

        // A weak notification signal must never immediately undo an explicit screen OFFLINE state.
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
