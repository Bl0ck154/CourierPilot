package com.block154.courierpilot

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lifecycle-level safety net for date rollover.
 *
 * The dashboard already schedules a midnight recomposition, but Android may pause/resume activities
 * around courier-app switching or defer callbacks. This guard tracks the actual local calendar day
 * and recreates the visible CourierPilot dashboard/home once when that day changes.
 */
internal object DayRolloverUiRefresh : Application.ActivityLifecycleCallbacks {
    private const val CHECK_INTERVAL_MS = 30_000L

    private val handler = Handler(Looper.getMainLooper())
    private var installed = false
    private var resumedActivity: WeakReference<Activity>? = null
    private var lastDayKey: String = dayKey()

    private val checker = object : Runnable {
        override fun run() {
            val activity = resumedActivity?.get() ?: return
            refreshIfDayChanged(activity)
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    fun install(application: Application) {
        if (installed) return
        installed = true
        lastDayKey = dayKey()
        application.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        resumedActivity = WeakReference(activity)
        refreshIfDayChanged(activity)
        handler.removeCallbacks(checker)
        handler.postDelayed(checker, CHECK_INTERVAL_MS)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumedActivity?.get() === activity) {
            resumedActivity = null
            handler.removeCallbacks(checker)
        }
    }

    private fun refreshIfDayChanged(activity: Activity) {
        val current = dayKey()
        if (current == lastDayKey) return
        lastDayKey = current
        if (activity is CourierPilotDashboardActivity || activity is CourierPilotHomeActivity) {
            activity.recreate()
        }
    }

    private fun dayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
