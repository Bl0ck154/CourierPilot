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

/** Time-sensitive reminder when CourierPilot recognizes a building with a previously learned code. */
internal object AccessCodeNotifier {
    private const val CHANNEL_ID = "courierpilot_access_codes"

    fun show(context: Context, suggestion: AccessCodeSuggestion): Boolean {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false

        val manager = app.getSystemService(NotificationManager::class.java) ?: return false
        ensureChannel(manager)

        val notificationId = notificationId(suggestion.displayAddress)
        val intent = navigationIntent(app, suggestion)
        val contentIntent = PendingIntent.getActivity(
            app,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val codeText = suggestion.codes.joinToString(" / ")
        val title = "Door code · ${suggestion.displayAddress}"
        val body = "Possible saved code: $codeText"

        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(app, CHANNEL_ID) else Notification.Builder(app)
        val notification = builder
            .setSmallIcon(R.drawable.ic_stat_courierpilot)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()

        manager.notify(notificationId, notification)
        return true
    }

    internal fun navigationIntent(context: Context, suggestion: AccessCodeSuggestion): Intent {
        val app = context.applicationContext
        val savedAddress = runCatching {
            CourierMetaDatabase.get(app).findAddressForDisplayAddress(suggestion.displayAddress)
        }.getOrNull()
        return if (savedAddress != null) {
            Intent(app, AddressDetailsActivity::class.java).apply {
                putExtra(AddressDetailsActivity.EXTRA_ADDRESS_ID, savedAddress.id)
                putStringArrayListExtra(
                    AddressDetailsActivity.EXTRA_ACCESS_CODES,
                    ArrayList(suggestion.codes.map(String::trim).filter(String::isNotEmpty).distinct()),
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        } else {
            Intent(app, CourierPilotDashboardActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < 26) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Door codes",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Saved building access-code reminders while delivering"
                setShowBadge(false)
            }
        )
    }

    private fun notificationId(address: String): Int =
        0x4D00 + (address.lowercase().hashCode() and 0x0FFF)
}
