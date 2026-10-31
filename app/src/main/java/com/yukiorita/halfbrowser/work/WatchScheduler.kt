package com.yukiorita.halfbrowser.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.yukiorita.halfbrowser.core.SiteWatchSettings
import java.util.concurrent.TimeUnit

/**
 * Owns the single periodic WorkManager job. WorkManager persists the job across
 * process death and device reboot, so the browser does not need a foreground
 * service or a background WebView to keep watching sites.
 */
object WatchScheduler {

    private const val PERIODIC_NAME = "site-watch-periodic"
    private const val IMMEDIATE_NAME = "site-watch-now"

    fun sync(context: Context, settings: SiteWatchSettings, hasEnabledSites: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (!settings.enabled || !hasEnabledSites) {
            manager.cancelUniqueWork(PERIODIC_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<SiteWatchWorker>(
            settings.intervalMinutes.toLong(),
            TimeUnit.MINUTES,
        )
            .setConstraints(constraints(settings))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        manager.enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** One-shot check used for the first baseline and the "check now" action. */
    fun requestImmediate(context: Context, settings: SiteWatchSettings) {
        val request = OneTimeWorkRequestBuilder<SiteWatchWorker>()
            .setConstraints(constraints(settings))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_NAME)
    }

    private fun constraints(settings: SiteWatchSettings): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(
                if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
            )
            .setRequiresBatteryNotLow(true)
            .build()
}
