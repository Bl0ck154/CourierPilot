package com.block154.courierpilot

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * Android/OEM firmware can leave Notification access enabled in Settings while the actual listener
 * binding has gone stale. Requesting a rebind is the public Android recovery mechanism. We do it on
 * process start and whenever the user returns to CourierPilot, with a cooldown so normal navigation
 * never causes a rebind storm.
 */
internal object CaptureServiceRecovery {
    private const val PREFS = "courierpilot_capture_recovery"
    private const val KEY_LAST_REBIND_REQUEST_AT = "last_notification_rebind_request_at"
    private const val REBIND_COOLDOWN_MS = 60_000L

    fun install(application: Application) {
        requestNotificationListenerRebind(application, reason = "process_start")
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                requestNotificationListenerRebind(activity, reason = "app_resumed")
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    fun requestNotificationListenerRebind(
        context: Context,
        reason: String,
        force: Boolean = false,
    ): Boolean {
        val appContext = context.applicationContext
        if (!notificationAccessEnabled(appContext)) return false

        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_REBIND_REQUEST_AT, 0L)
        if (!force && now - last < REBIND_COOLDOWN_MS) return false

        prefs.edit().putLong(KEY_LAST_REBIND_REQUEST_AT, now).apply()
        val component = ComponentName(appContext, CourierPilotNotificationListener::class.java)
        return runCatching {
            NotificationListenerService.requestRebind(component)
            CaptureEventLog.append(
                appContext,
                stage = "listener_rebind",
                message = "Requested notification listener rebind ($reason)",
                dedupeWindowMs = 30_000L,
            )
            true
        }.getOrElse { error ->
            CaptureEventLog.append(
                appContext,
                stage = "listener_rebind_failed",
                message = error.javaClass.simpleName,
                dedupeWindowMs = 30_000L,
            )
            false
        }
    }

    fun lastRebindRequestedAt(context: Context): Long =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_REBIND_REQUEST_AT, 0L)

    fun notificationAccessEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        val target = ComponentName(context, CourierPilotNotificationListener::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == target }
    }
}
