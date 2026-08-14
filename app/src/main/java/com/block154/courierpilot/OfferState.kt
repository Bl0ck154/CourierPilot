package com.block154.courierpilot

import android.content.Context

internal data class PendingOffer(
    val packageName: String,
    val sourceName: String,
    val armedAt: Long,
    val notificationKey: String = "",
)

internal enum class ArmResult {
    ARMED,
    DUPLICATE_UPDATE,
    REPLACED_SAME_PLATFORM,
    QUEUED_OTHER_PLATFORM,
}

internal object OfferState {
    private const val PREFS = "courier_offer_capture"
    private const val KEY_PACKAGE = "pending_package"
    private const val KEY_SOURCE = "pending_source"
    private const val KEY_ARMED_AT = "pending_armed_at"
    private const val KEY_NOTIFICATION = "pending_notification_key"
    private const val KEY_QUEUED_PACKAGE = "queued_package"
    private const val KEY_QUEUED_SOURCE = "queued_source"
    private const val KEY_QUEUED_ARMED_AT = "queued_armed_at"
    private const val KEY_QUEUED_NOTIFICATION = "queued_notification_key"
    private const val KEY_CAPTURED_NOTIFICATION_PACKAGE = "captured_notification_package"
    private const val KEY_CAPTURED_NOTIFICATION = "captured_notification_key"
    private const val KEY_CAPTURED_NOTIFICATION_AT = "captured_notification_at"
    private const val KEY_LAST_CAPTURE = "last_capture"
    private const val KEY_LAST_UI_TEXT = "last_ui_text"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_AUTO_OPEN = "auto_open"
    private const val KEY_WAKE_SCREEN = "wake_screen"
    private const val MAX_PENDING_AGE_MS = 180_000L
    private const val CAPTURED_NOTIFICATION_TTL_MS = 4L * 60L * 1000L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun arm(context: Context, packageName: String, sourceName: String, notificationKey: String = ""): ArmResult {
        if (notificationKey.isNotBlank() &&
            !notificationKey.startsWith("screen:") &&
            wasCapturedNotification(context, packageName, notificationKey)
        ) {
            return ArmResult.DUPLICATE_UPDATE
        }

        val existing = pending(context)
        if (existing == null) {
            writePending(context, packageName, sourceName, notificationKey, System.currentTimeMillis())
            return ArmResult.ARMED
        }

        if (notificationKey.isNotBlank() && existing.notificationKey == notificationKey) {
            return ArmResult.DUPLICATE_UPDATE
        }

        if (existing.packageName == packageName) {
            writePending(context, packageName, sourceName, notificationKey, System.currentTimeMillis())
            return ArmResult.REPLACED_SAME_PLATFORM
        }

        prefs(context).edit()
            .putString(KEY_QUEUED_PACKAGE, packageName)
            .putString(KEY_QUEUED_SOURCE, sourceName)
            .putString(KEY_QUEUED_NOTIFICATION, notificationKey)
            .putLong(KEY_QUEUED_ARMED_AT, System.currentTimeMillis())
            .putString(KEY_LAST_ERROR, "${platformLabel(packageName)} offer queued while ${platformLabel(existing.packageName)} capture is active")
            .apply()
        return ArmResult.QUEUED_OTHER_PLATFORM
    }

    fun pending(context: Context): PendingOffer? {
        val p = prefs(context)
        val packageName = p.getString(KEY_PACKAGE, null)
        if (packageName == null) {
            promoteQueued(context)
            return readPending(context)
        }
        val armedAt = p.getLong(KEY_ARMED_AT, 0L)
        if (armedAt == 0L || System.currentTimeMillis() - armedAt > MAX_PENDING_AGE_MS) {
            CaptureEventLog.append(
                context,
                stage = "expired",
                platform = platformLabel(packageName),
                message = "Pending offer expired after 3 minutes without a detected price",
            )
            clearCurrent(context)
            markError(context, "Offer expired before a price was detected; no screenshot was saved")
            promoteQueued(context)
            return readPending(context)
        }
        return readPending(context)
    }

    /**
     * Called only after a successful capture. Besides seeding the semantic screen deduper, retain
     * the notification instance that produced the capture. Courier apps frequently repost/update
     * the same StatusBarNotification after the price is already visible; without this tombstone a
     * cleared transaction was armed again and produced a second/third screenshot + DB row.
     */
    fun clear(context: Context) {
        val current = readPending(context)
        if (current != null) {
            if (current.notificationKey.isNotBlank() && !current.notificationKey.startsWith("screen:")) {
                markCapturedNotification(context, current.packageName, current.notificationKey)
            }

            val text = prefs(context).getString(KEY_LAST_UI_TEXT, "").orEmpty()
            if (text.isNotBlank()) {
                val parsed = OfferParser.parse(text)
                if (parsed.priceCents != null) {
                    ScreenOfferDeduper.markArmed(
                        context,
                        current.packageName,
                        CourierSignals.offerFingerprint(current.packageName, parsed, text),
                    )
                }
            }
        }
        clearCurrent(context)
        promoteQueued(context)
    }

    /**
     * A removed offer notification ends the lifetime of its instance key. Releasing the tombstone
     * here prevents a later genuine offer from being suppressed when a courier app reuses the same
     * notification id/tag. The TTL remains a safety net if the OEM never delivers removal.
     */
    fun releaseCapturedNotification(context: Context, packageName: String, notificationKey: String) {
        if (notificationKey.isBlank()) return
        val p = prefs(context)
        if (p.getString(KEY_CAPTURED_NOTIFICATION_PACKAGE, "") != packageName) return
        if (p.getString(KEY_CAPTURED_NOTIFICATION, "") != notificationKey) return
        p.edit()
            .remove(KEY_CAPTURED_NOTIFICATION_PACKAGE)
            .remove(KEY_CAPTURED_NOTIFICATION)
            .remove(KEY_CAPTURED_NOTIFICATION_AT)
            .apply()
    }

    private fun wasCapturedNotification(context: Context, packageName: String, notificationKey: String): Boolean {
        val p = prefs(context)
        val previousPackage = p.getString(KEY_CAPTURED_NOTIFICATION_PACKAGE, "").orEmpty()
        val previousKey = p.getString(KEY_CAPTURED_NOTIFICATION, "").orEmpty()
        val capturedAt = p.getLong(KEY_CAPTURED_NOTIFICATION_AT, 0L)
        if (capturedAt == 0L || System.currentTimeMillis() - capturedAt > CAPTURED_NOTIFICATION_TTL_MS) {
            if (capturedAt != 0L) {
                p.edit()
                    .remove(KEY_CAPTURED_NOTIFICATION_PACKAGE)
                    .remove(KEY_CAPTURED_NOTIFICATION)
                    .remove(KEY_CAPTURED_NOTIFICATION_AT)
                    .apply()
            }
            return false
        }
        return previousPackage == packageName && previousKey == notificationKey
    }

    private fun markCapturedNotification(context: Context, packageName: String, notificationKey: String) {
        prefs(context).edit()
            .putString(KEY_CAPTURED_NOTIFICATION_PACKAGE, packageName)
            .putString(KEY_CAPTURED_NOTIFICATION, notificationKey)
            .putLong(KEY_CAPTURED_NOTIFICATION_AT, System.currentTimeMillis())
            .apply()
    }

    private fun readPending(context: Context): PendingOffer? {
        val p = prefs(context)
        val packageName = p.getString(KEY_PACKAGE, null) ?: return null
        val armedAt = p.getLong(KEY_ARMED_AT, 0L)
        if (armedAt == 0L) return null
        return PendingOffer(
            packageName = packageName,
            sourceName = p.getString(KEY_SOURCE, packageName) ?: packageName,
            armedAt = armedAt,
            notificationKey = p.getString(KEY_NOTIFICATION, "") ?: "",
        )
    }

    private fun writePending(context: Context, packageName: String, sourceName: String, notificationKey: String, armedAt: Long) {
        prefs(context).edit()
            .putString(KEY_PACKAGE, packageName)
            .putString(KEY_SOURCE, sourceName)
            .putLong(KEY_ARMED_AT, armedAt)
            .putString(KEY_NOTIFICATION, notificationKey)
            .putString(KEY_LAST_ERROR, "")
            .apply()
    }

    private fun clearCurrent(context: Context) {
        prefs(context).edit()
            .remove(KEY_PACKAGE)
            .remove(KEY_SOURCE)
            .remove(KEY_ARMED_AT)
            .remove(KEY_NOTIFICATION)
            .apply()
    }

    private fun promoteQueued(context: Context) {
        val p = prefs(context)
        val pkg = p.getString(KEY_QUEUED_PACKAGE, null) ?: return
        val armed = p.getLong(KEY_QUEUED_ARMED_AT, 0L)
        val source = p.getString(KEY_QUEUED_SOURCE, pkg) ?: pkg
        val key = p.getString(KEY_QUEUED_NOTIFICATION, "") ?: ""
        p.edit()
            .remove(KEY_QUEUED_PACKAGE)
            .remove(KEY_QUEUED_SOURCE)
            .remove(KEY_QUEUED_ARMED_AT)
            .remove(KEY_QUEUED_NOTIFICATION)
            .apply()
        if (armed != 0L && System.currentTimeMillis() - armed <= MAX_PENDING_AGE_MS) {
            if (key.isNotBlank() && !key.startsWith("screen:") && wasCapturedNotification(context, pkg, key)) {
                CaptureEventLog.append(
                    context,
                    stage = "queued_duplicate",
                    platform = platformLabel(pkg),
                    message = "Queued notification was already captured; discarded",
                )
                return
            }
            writePending(context, pkg, source, key, armed)
            CaptureEventLog.append(
                context,
                stage = "promoted",
                platform = platformLabel(pkg),
                message = "Queued offer promoted to active capture",
            )
        }
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

    fun wakeScreen(context: Context): Boolean = prefs(context).getBoolean(KEY_WAKE_SCREEN, false)

    fun setWakeScreen(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WAKE_SCREEN, enabled).apply()
    }

    fun platformLabel(packageName: String): String = when {
        packageName.contains("wolt", ignoreCase = true) -> "Wolt"
        packageName.contains("bolt", ignoreCase = true) -> "Bolt"
        else -> "Courier"
    }
}
