package com.block154.courierpilot

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/** Android-owned periodic scheduling for automatic update checks/downloads. */
internal object BackgroundAppUpdateScheduler {
    private const val JOB_ID = 1550

    fun ensureScheduled(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val wantedInterval = AppUpdateSettings.checkFrequency(context).intervalMs
        val existing = scheduler.getPendingJob(JOB_ID)
        if (existing != null && existing.intervalMillis == wantedInterval) return
        scheduler.cancel(JOB_ID)
        schedule(context, scheduler, wantedInterval)
    }

    fun reschedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        scheduler.cancel(JOB_ID)
        schedule(context, scheduler, AppUpdateSettings.checkFrequency(context).intervalMs)
    }

    private fun schedule(context: Context, scheduler: JobScheduler, intervalMs: Long) {
        scheduler.schedule(
            JobInfo.Builder(
                JOB_ID,
                ComponentName(context, AppUpdateJobService::class.java),
            )
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(intervalMs)
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
