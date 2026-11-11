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
 * Owns the periodic VPN checks.
 *
 * Two jobs with deliberately different constraints
 * -------------------------------------------------
 *  - **health** — verifies a tunnel that is *currently up*. Its purpose is to
 *    catch a tunnel that broke while the app was hidden. Opening the app gives
 *    the same check for free, so this job may wait for a healthy battery
 *    (`setRequiresBatteryNotLow(true)`). It is registered **only while a tunnel
 *    is actually connected**, so a user who is not using the VPN never pays for
 *    it (v1.8.0 woke up 96 times a day even when disconnected).
 *  - **watchdog** — brings a tunnel back after the process was killed. It
 *    protects a connection the user asked for, so it must not be suppressed by
 *    low battery. Registered only when Android's Always-on VPN is in use, where
 *    the system itself has committed to keeping the tunnel up.
 *
 * WorkManager persists both across process death and reboot. The OS clamps
 * periodic work to 15 minutes; the event-driven checks (screen on, network
 * change, app resume) cover everything that needs to be faster.
 */
object VpnHealthScheduler {

    /** Verifies an already-connected tunnel. */
    const val HEALTH_WORK_NAME = "vpn-health-periodic"

    /** Restores a tunnel after the process was killed (Always-on VPN only). */
    const val WATCHDOG_WORK_NAME = "vpn-watchdog-periodic"

    /**
     * @param healthCheck register the verification job (a tunnel is up)
     * @param watchdog register the restart-recovery job (Always-on VPN is on)
     */
    fun sync(context: Context, healthCheck: Boolean, watchdog: Boolean) {
        val manager = WorkManager.getInstance(context)
        syncJob(manager, HEALTH_WORK_NAME, healthCheck, requiresBatteryNotLow = true)
        syncJob(manager, WATCHDOG_WORK_NAME, watchdog, requiresBatteryNotLow = false)
    }

    private fun syncJob(
        manager: WorkManager,
        name: String,
        enabled: Boolean,
        requiresBatteryNotLow: Boolean,
    ) {
        if (!enabled) {
            manager.cancelUniqueWork(name)
            return
        }
        val request = PeriodicWorkRequestBuilder<VpnHealthWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(requiresBatteryNotLow)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("vpn-health")
            .build()
        manager.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context) {
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(HEALTH_WORK_NAME)
        manager.cancelUniqueWork(WATCHDOG_WORK_NAME)
    }

    private const val PERIOD_MINUTES = 15L
}
