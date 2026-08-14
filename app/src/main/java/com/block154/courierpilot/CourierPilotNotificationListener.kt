package com.block154.courierpilot

import android.app.ActivityOptions
import android.app.PendingIntent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class CourierPilotNotificationListener : NotificationListenerService() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onListenerConnected() {
        super.onListenerConnected()
        CaptureEventLog.append(this, "listener", "Notification listener connected", dedupeWindowMs = 30_000L)
        reconcileAllCourierPresence()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!CourierSignals.isCourierPackage(sbn.packageName)) return
        val platform = OfferState.platformLabel(sbn.packageName)
        val notification = sbn.notification

        if (CourierSignals.isOfferNotification(notification)) {
            // Receiving a real offer is stronger proof of online state than a possibly stale ongoing notification.
            CourierPresence.markOfferOnline(this, sbn.packageName, "offer notification")
            CaptureEventLog.append(this, "notification", "Strict offer notification matched", platform)
            val sourceName = resolveAppName(sbn.packageName)
            val armResult = OfferState.arm(this, sbn.packageName, sourceName, sbn.key)
            CaptureEventLog.append(
                this,
                stage = "armed",
                platform = platform,
                message = when (armResult) {
                    ArmResult.ARMED -> "New capture armed"
                    ArmResult.DUPLICATE_UPDATE -> "Duplicate/already captured notification update ignored"
                    ArmResult.REPLACED_SAME_PLATFORM -> "New notification replaced pending offer from same platform"
                    ArmResult.QUEUED_OTHER_PLATFORM -> "Offer queued behind active capture from other platform"
                },
            )

            val shouldAct = armResult != ArmResult.DUPLICATE_UPDATE && armResult != ArmResult.QUEUED_OTHER_PLATFORM
            if (shouldAct && OfferState.wakeScreen(this)) wakeScreenBriefly(platform)
            if (shouldAct && OfferState.autoOpen(this)) {
                openOriginalNotification(notification.contentIntent, sourceName, platform)
            }
            return
        }

        val notificationText = CourierSignals.notificationText(notification)
        when (val signal = CourierSignals.detectPresence(notificationText)) {
            PresenceSignal.ONLINE, PresenceSignal.OFFLINE ->
                CourierPresence.markExplicitNotification(this, sbn.packageName, signal)
            PresenceSignal.UNKNOWN -> if (CourierSignals.isOngoingPresenceNotification(notification)) {
                CourierPresence.markNotificationOnline(this, sbn.packageName)
            }
        }

        CaptureEventLog.append(
            this,
            stage = "notification_ignored",
            platform = platform,
            message = "Courier notification was not an offer; no auto-open/capture arm",
            dedupeWindowMs = 20_000L,
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!CourierSignals.isCourierPackage(sbn.packageName)) return
        if (CourierSignals.isOfferNotification(sbn.notification)) {
            // A captured notification key is only a tombstone for this notification lifetime.
            // Once Android removes it, allow the courier app to reuse the same id/tag for a new offer.
            OfferState.releaseCapturedNotification(this, sbn.packageName, sbn.key)
            return
        }
        if (!CourierSignals.isOngoingPresenceNotification(sbn.notification)) return

        // Swiping/removing a persistent notification is not proof that the courier went offline.
        // Give the app time to repost it, then degrade to UNKNOWN if it stays absent.
        val packageName = sbn.packageName
        handler.postDelayed({ reconcilePackagePresence(packageName) }, PRESENCE_REMOVAL_GRACE_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun reconcileAllCourierPresence() {
        CourierSignals.courierPackages.forEach(::reconcilePackagePresence)
    }

    private fun reconcilePackagePresence(packageName: String) {
        val active = runCatching { activeNotifications?.filter { it.packageName == packageName }.orEmpty() }
            .getOrDefault(emptyList())
        val nonOffer = active.filterNot { CourierSignals.isOfferNotification(it.notification) }

        val explicit = nonOffer.asSequence()
            .map { CourierSignals.detectPresence(CourierSignals.notificationText(it.notification)) }
            .firstOrNull { it != PresenceSignal.UNKNOWN }
        if (explicit != null) {
            CourierPresence.markExplicitNotification(this, packageName, explicit)
            return
        }

        if (nonOffer.any { CourierSignals.isOngoingPresenceNotification(it.notification) }) {
            CourierPresence.markNotificationOnline(this, packageName)
        } else {
            CourierPresence.markNotificationUnknown(this, packageName)
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
                    setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }
                contentIntent.send(this, 0, null, null, null, null, options.toBundle())
            } else {
                contentIntent.send()
            }
            CaptureEventLog.append(this, "open_requested", "Sent original offer notification action", platform)
        } catch (_: PendingIntent.CanceledException) {
            OfferState.markError(this, "Could not open $sourceName from its offer notification")
            CaptureEventLog.append(this, "open_failed", "Offer notification action was cancelled", platform)
        } catch (t: Throwable) {
            OfferState.markError(this, "Could not open $sourceName: ${t.javaClass.simpleName}")
            CaptureEventLog.append(this, "open_failed", t.javaClass.simpleName, platform)
        }
    }

    private fun resolveAppName(pkg: String): String = try {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (_: Throwable) {
        pkg
    }

    companion object {
        private const val PRESENCE_REMOVAL_GRACE_MS = 20_000L
    }
}
