package com.block154.courierpilot

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Explicit feedback keeps stale door codes from repeatedly resurfacing and teaches real entrances. */
class AccessCodeFeedbackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val buildingKey = intent.getStringExtra(EXTRA_BUILDING_KEY)?.takeIf(String::isNotBlank) ?: return
        val codes = intent.getStringArrayListExtra(EXTRA_CODES)
            .orEmpty()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        if (codes.isEmpty()) return

        when (intent.action) {
            ACTION_WORKS -> {
                codes.forEach { AccessHintFeedbackStore.markConfirmed(app, buildingKey, it) }
                RouteResearchLocation.bestLastKnown(app)?.let { fix ->
                    LearnedEntranceStore.record(app, buildingKey, fix)
                }
                CaptureEventLog.append(
                    app,
                    stage = "access_code_feedback_confirmed",
                    message = "Door-code reminder confirmed; entranceSamples=${LearnedEntranceStore.sampleCount(app, buildingKey)}",
                    dedupeWindowMs = 5_000L,
                )
            }

            ACTION_WRONG_OLD -> {
                val database = CourierMetaDatabase.get(app)
                codes.forEach { code ->
                    AccessHintFeedbackStore.markRejected(app, buildingKey, code)
                    AccessCodeDeletion.delete(database, buildingKey, code)
                }
                AccessCodeSuggestions.clear(app)
                CaptureEventLog.append(
                    app,
                    stage = "access_code_feedback_rejected",
                    message = "Door-code reminder marked wrong/old and derived code removed",
                    dedupeWindowMs = 5_000L,
                )
            }

            else -> return
        }

        intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
            .takeIf { it >= 0 }
            ?.let { id -> app.getSystemService(NotificationManager::class.java)?.cancel(id) }
    }

    companion object {
        const val ACTION_WORKS = "com.block154.courierpilot.ACCESS_CODE_WORKS"
        const val ACTION_WRONG_OLD = "com.block154.courierpilot.ACCESS_CODE_WRONG_OLD"
        const val EXTRA_BUILDING_KEY = "building_key"
        const val EXTRA_CODES = "codes"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
