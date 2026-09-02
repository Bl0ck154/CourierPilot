package com.block154.courierpilot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AppUpdateNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_DISMISS) return
        AppUpdateManager.dismissNotification(context, intent.getStringExtra(EXTRA_VERSION))
    }

    companion object {
        const val ACTION_DISMISS = "com.block154.courierpilot.action.DISMISS_APP_UPDATE"
        const val EXTRA_VERSION = "update_version"
    }
}
