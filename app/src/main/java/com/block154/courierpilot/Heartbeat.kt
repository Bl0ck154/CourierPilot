package com.block154.courierpilot

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings

internal object HeartbeatSettings {
    private const val PREFS = "courierpilot_heartbeat"
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        if (enabled) HeartbeatScheduler.schedule(context) else HeartbeatScheduler.cancel(context)
    }
}

internal object HeartbeatScheduler {
    const val INTERVAL_HOURS = 4
    private const val INTERVAL_MS = INTERVAL_HOURS * 60L * 60L * 1000L
    private const val ACTION_HEARTBEAT = "com.block154.courierpilot.action.HEARTBEAT"
    private const val REQUEST_CODE = 1544

    /**
     * Application.onCreate() can run many times over the lifetime of an installed app. Re-sending
     * setInexactRepeating() with the same PendingIntent replaces the previous alarm and moves its
     * next trigger to now + 4h. Only create the alarm here when it does not already exist.
     */
    fun ensureScheduled(context: Context) {
        if (HeartbeatSettings.enabled(context) && !isScheduled(context)) schedule(context)
    }

    fun schedule(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + INTERVAL_MS,
            INTERVAL_MS,
            heartbeatIntent(context),
        )
    }

    fun cancel(context: Context) {
        val existing = existingHeartbeatIntent(context) ?: return
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(existing)
        existing.cancel()
    }

    fun isScheduled(context: Context): Boolean = existingHeartbeatIntent(context) != null

    fun isHeartbeatAction(action: String?): Boolean = action == ACTION_HEARTBEAT

    private fun existingHeartbeatIntent(context: Context): PendingIntent? = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, HeartbeatReceiver::class.java).setAction(ACTION_HEARTBEAT),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun heartbeatIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, HeartbeatReceiver::class.java).setAction(ACTION_HEARTBEAT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

class HeartbeatReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when {
            intent?.action == Intent.ACTION_BOOT_COMPLETED || intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED -> {
                HeartbeatScheduler.ensureScheduled(context)
            }
            HeartbeatScheduler.isHeartbeatAction(intent?.action) && HeartbeatSettings.enabled(context) -> {
                HeartbeatNotifier.show(context)
            }
        }
    }
}

internal object HeartbeatNotifier {
    private const val CHANNEL_ID = "courierpilot_heartbeat"
    private const val NOTIFICATION_ID = 1544

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun show(context: Context) {
        if (!canPost(context)) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel(manager)

        val healthy = hasNotificationListenerAccess(context) && hasAccessibilityAccess(context)
        val title = if (healthy) "CourierPilot is active" else "CourierPilot needs attention"
        val message = if (healthy) {
            "Background offer capture is enabled for Wolt and Bolt."
        } else {
            "CourierPilot is running, but one of the capture permissions is disabled."
        }
        val openApp = PendingIntent.getActivity(
            context,
            1545,
            Intent(context, CourierPilotHomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_courierpilot)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setOngoing(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createChannel(manager: NotificationManager) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "CourierPilot alive reminders",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Occasional non-persistent reminders that CourierPilot background capture is alive."
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun hasNotificationListenerAccess(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.split(':').any {
            ComponentName.unflattenFromString(it)?.packageName == context.packageName
        }
    }

    private fun hasAccessibilityAccess(context: Context): Boolean {
        if (Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) return false
        val target = ComponentName(context, OfferAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == target }
    }
}
