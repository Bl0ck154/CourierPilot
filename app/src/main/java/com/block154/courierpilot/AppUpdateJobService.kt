package com.block154.courierpilot

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/** Android-owned periodic scheduling for automatic update checks/downloads. */
internal object BackgroundAppUpdateScheduler {
    const val CHECK_INTERVAL_MS = 30L * 60L * 1000L
    private const val JOB_ID = 1550

    fun ensureScheduled(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val existing = scheduler.getPendingJob(JOB_ID)
        if (existing != null && existing.intervalMillis == CHECK_INTERVAL_MS) return
        scheduler.cancel(JOB_ID)
        schedule(context, scheduler)
    }

    private fun schedule(context: Context, scheduler: JobScheduler) {
        scheduler.schedule(
            JobInfo.Builder(
                JOB_ID,
                ComponentName(context, AppUpdateJobService::class.java),
            )
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(CHECK_INTERVAL_MS)
                .setPersisted(true)
                .build()
        )
    }
}

class AppUpdateJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        AppUpdateManager.checkIfDue(applicationContext) {
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        return true
    }
}
