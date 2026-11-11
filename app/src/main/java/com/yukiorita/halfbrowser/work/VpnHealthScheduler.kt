package com.yukiorita.halfbrowser.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Owns the periodic VPN health check.
 *
 * WorkManager persists the job across process death and reboot, so the check
 * keeps running while the app is closed. The OS clamps periodic work to a
 * minimum of 15 minutes; the immediate check on resume
 * (`VpnController.onAppForeground`) covers the "just woke up" case.
 */
object VpnHealthScheduler {

    private const val PERIODIC_NAME = "vpn-health-periodic"

    /**
     * @param enabled false cancels the schedule (no profile selected, or the
     *   user turned both automatic options off)
     */
    fun sync(context: Context, enabled: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (!enabled) {
            manager.cancelUniqueWork(PERIODIC_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<VpnHealthWorker>(
            PERIOD_MINUTES,
            TimeUnit.MINUTES,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        manager.enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
    }

    private const val PERIOD_MINUTES = 15L
}
