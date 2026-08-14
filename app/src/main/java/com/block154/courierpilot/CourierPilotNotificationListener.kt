package com.block154.courierpilot

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Locale

class CourierPilotNotificationListener : NotificationListenerService() {

    private val knownCourierPackages = setOf(
        "com.wolt.courierapp",
        "com.bolt.deliverycourier",
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return

        val sourceName = resolveAppName(sbn.packageName)
        if (!isCourierSource(sbn.packageName, sourceName)) return
        if (!looksLikeOfferNotification(sbn.notification)) return

        val before = OfferState.pending(this)
        OfferState.arm(this, sbn.packageName, sourceName, sbn.key)
        val after = OfferState.pending(this)
        val isNewCurrentOffer = after?.notificationKey == sbn.key && before?.notificationKey != sbn.key

        if (OfferState.autoOpen(this) && isNewCurrentOffer) {
            openOriginalNotification(sbn.notification.contentIntent, sourceName)
        }
    }

    private fun openOriginalNotification(contentIntent: PendingIntent?, sourceName: String) {
        if (contentIntent == null) {
            OfferState.markError(this, "$sourceName notification has no content intent")
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
        } catch (_: PendingIntent.CanceledException) {
            OfferState.markError(this, "Could not open $sourceName from its notification")
        } catch (t: Throwable) {
            OfferState.markError(this, "Could not open $sourceName: ${t.javaClass.simpleName}")
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
