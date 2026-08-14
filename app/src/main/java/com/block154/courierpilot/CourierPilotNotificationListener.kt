package com.block154.courierpilot

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.os.Build
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Locale

class CourierPilotNotificationListener : NotificationListenerService() {

    private val knownCourierPackages = setOf(
        "com.wolt.courierapp",
        "com.bolt.deliverycourier",
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        CaptureEventLog.append(this, "listener", "Notification listener connected", dedupeWindowMs = 30_000L)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return

        val sourceName = resolveAppName(sbn.packageName)
        if (!isCourierSource(sbn.packageName, sourceName)) return
        val platform = OfferState.platformLabel(sbn.packageName)

        if (!looksLikeOfferNotification(sbn.notification)) {
            CaptureEventLog.append(
                this,
                stage = "notification_ignored",
                platform = platform,
                message = "Courier notification did not match offer keywords",
                dedupeWindowMs = 10_000L,
            )
            return
        }

        CaptureEventLog.append(this, "notification", "Offer-like notification received", platform)
        val armResult = OfferState.arm(this, sbn.packageName, sourceName, sbn.key)
        CaptureEventLog.append(
            this,
            stage = "armed",
            platform = platform,
            message = when (armResult) {
                ArmResult.ARMED -> "New capture armed"
                ArmResult.DUPLICATE_UPDATE -> "Duplicate notification update ignored"
                ArmResult.REPLACED_SAME_PLATFORM -> "New notification replaced pending offer from same platform"
                ArmResult.QUEUED_OTHER_PLATFORM -> "Offer queued behind active capture from other platform"
            },
        )

        val shouldAct = armResult != ArmResult.DUPLICATE_UPDATE && armResult != ArmResult.QUEUED_OTHER_PLATFORM
        if (shouldAct && OfferState.wakeScreen(this)) {
            wakeScreenBriefly(platform)
        }
        if (shouldAct && OfferState.autoOpen(this)) {
            openOriginalNotification(sbn.notification.contentIntent, sourceName, platform)
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeScreenBriefly(platform: String) {
        try {
            val power = getSystemService(POWER_SERVICE) as PowerManager
            if (power.isInteractive) return
            val lock = power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "$packageName:offerWake",
            )
            lock.acquire(3_000L)
            CaptureEventLog.append(this, "wake", "Requested a brief screen wake", platform)
        } catch (t: Throwable) {
            CaptureEventLog.append(this, "wake_failed", t.javaClass.simpleName, platform)
        }
    }

    private fun openOriginalNotification(contentIntent: PendingIntent?, sourceName: String, platform: String) {
        if (contentIntent == null) {
            OfferState.markError(this, "$sourceName notification has no content intent")
            CaptureEventLog.append(this, "open_failed", "Notification has no content intent", platform)
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val options = ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
                    )
                }
                contentIntent.send(this, 0, null, null, null, null, options.toBundle())
            } else {
                contentIntent.send()
            }
            CaptureEventLog.append(this, "open_requested", "Sent original courier notification action", platform)
        } catch (_: PendingIntent.CanceledException) {
            OfferState.markError(this, "Could not open $sourceName from its notification")
            CaptureEventLog.append(this, "open_failed", "Courier notification action was cancelled", platform)
        } catch (t: Throwable) {
            OfferState.markError(this, "Could not open $sourceName: ${t.javaClass.simpleName}")
            CaptureEventLog.append(this, "open_failed", t.javaClass.simpleName, platform)
        }
    }

    private fun resolveAppName(pkg: String): String {
        return try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Throwable) {
            pkg
        }
    }

    private fun isCourierSource(pkg: String, appName: String): Boolean {
        if (pkg in knownCourierPackages) return true
        val identity = "$pkg $appName".lowercase(Locale.ROOT)
        return identity.contains("wolt courier") ||
            identity.contains("wolt partner") ||
            identity.contains("bolt food courier") ||
            identity.contains("bolt courier")
    }

    private fun looksLikeOfferNotification(notification: Notification): Boolean {
        val e = notification.extras
        val text = buildString {
            append(e.getCharSequence(Notification.EXTRA_TITLE).orEmpty())
            append(' ')
            append(e.getCharSequence(Notification.EXTRA_TEXT).orEmpty())
            append(' ')
            append(e.getCharSequence(Notification.EXTRA_BIG_TEXT).orEmpty())
            append(' ')
            append(e.getCharSequence(Notification.EXTRA_SUB_TEXT).orEmpty())
            append(' ')
            append(notification.tickerText.orEmpty())
        }.lowercase(Locale.ROOT)

        val offerWords = listOf(
            "task", "order", "delivery", "offer",
            "užduot", "uzduot", "užsak", "uzsak", "pristat",
            "задан", "заказ", "достав", "замов", "завдан",
        )
        return offerWords.any(text::contains)
    }
}
