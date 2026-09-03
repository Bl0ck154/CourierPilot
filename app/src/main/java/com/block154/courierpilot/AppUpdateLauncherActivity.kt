package com.block154.courierpilot

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Tiny launcher shim used only to make update discovery reliable.
 *
 * The real dashboard still starts immediately on normal launches. This activity asks for Android's
 * notification permission once (Android 13+) and kicks a due update check without requiring the user
 * to open Settings. The periodic JobScheduler remains the primary background path.
 */
class AppUpdateLauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        BackgroundAppUpdateScheduler.ensureScheduled(this)
        AppUpdateManager.checkIfDue(applicationContext) {
            AppUpdateForegroundAwareness.showPendingUpdate(applicationContext)
        }

        if (!AppUpdateForegroundAwareness.requestNotificationPermissionIfNeeded(this)) {
            AppUpdateForegroundAwareness.showPendingUpdate(applicationContext)
            openDashboard()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AppUpdateForegroundAwareness.REQUEST_NOTIFICATIONS) {
            AppUpdateForegroundAwareness.showPendingUpdate(applicationContext)
            openDashboard()
        }
    }

    private fun openDashboard() {
        startActivity(
            Intent(this, CourierPilotDashboardActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
        finish()
    }
}

/** Foreground-side update permission + notification bridge. */
internal object AppUpdateForegroundAwareness {
    const val REQUEST_NOTIFICATIONS = 1554

    private const val PREFS = "courierpilot_update_awareness"
    private const val KEY_PERMISSION_PROMPTED = "notification_permission_prompted"
    private const val UPDATE_CHANNEL_ID = "courierpilot_updates"
    private const val UPDATE_NOTIFICATION_ID = 1550

    fun requestNotificationPermissionIfNeeded(activity: ComponentActivity): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return false
        }

        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_PERMISSION_PROMPTED, false)) return false

        prefs.edit().putBoolean(KEY_PERMISSION_PROMPTED, true).apply()
        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        return true
    }

    fun showPendingUpdate(context: Context) {
        val app = context.applicationContext
        val status = AppUpdateManager.snapshot(app)
        val ready = status.phase == AppUpdatePhase.READY
        if (!ready && status.phase != AppUpdatePhase.AVAILABLE) return

        val version = status.version ?: return
        if (AppUpdateSettings.dismissedVersion(app) == version) return
        if (!notificationsAllowed(app)) return

        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        if (manager.activeNotifications.any { it.id == UPDATE_NOTIFICATION_ID }) return

        manager.createNotificationChannel(
            NotificationChannel(
                UPDATE_CHANNEL_ID,
                "CourierPilot updates",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "New CourierPilot versions downloaded from GitHub Releases."
                setShowBadge(true)
            }
        )

        val openUpdates = PendingIntent.getActivity(
            app,
            1551,
            Intent(app, AppUpdateActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val primaryAction = PendingIntent.getActivity(
            app,
            1552,
            Intent(app, AppUpdateActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (ready) putExtra(AppUpdateActivity.EXTRA_INSTALL_NOW, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val later = PendingIntent.getBroadcast(
            app,
            1553,
            Intent(app, AppUpdateNotificationReceiver::class.java).apply {
                action = AppUpdateNotificationReceiver.ACTION_DISMISS
                putExtra(AppUpdateNotificationReceiver.EXTRA_VERSION, version)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = if (ready) {
            "CourierPilot $version ready to install"
        } else {
            "CourierPilot $version available"
        }
        val message = if (ready) {
            "Update downloaded and verified. Tap Install to update CourierPilot."
        } else {
            "A newer CourierPilot version is available. Tap to view the update."
        }

        manager.notify(
            UPDATE_NOTIFICATION_ID,
            Notification.Builder(app, UPDATE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_courierpilot)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setContentIntent(openUpdates)
                .setDeleteIntent(later)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setNumber(1)
                .setBadgeIconType(Notification.BADGE_ICON_SMALL)
                .addAction(0, if (ready) "Install" else "Open", primaryAction)
                .addAction(0, "Later", later)
                .build()
        )
    }

    private fun notificationsAllowed(context: Context): Boolean {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
    }
}
