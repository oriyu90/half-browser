package com.yukiorita.halfbrowser.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yukiorita.halfbrowser.HalfBrowserApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Periodic VPN check, shared by both scheduled jobs ([VpnHealthScheduler]).
 *
 * While the app is hidden the in-app poller backs off to minutes, so a tunnel
 * whose NAT mapping expired would go unnoticed until the user opens the app.
 * This worker reads the handshake time of the live tunnel, records it, and —
 * only when the user allowed it and consent is already granted — reconnects a
 * tunnel that went stale.
 *
 * The worker may be the first thing to run after the process was recreated;
 * [com.yukiorita.halfbrowser.data.VpnController.verifyForBackground] handles
 * that by asking the OS whether the tunnel still exists
 * ([com.yukiorita.halfbrowser.data.VpnTunnelProbe]) instead of trusting the
 * in-memory state.
 *
 * The scheduler only registers this work when it can do something useful, so
 * the guard below is a safety net rather than the main filter.
 */
class VpnHealthWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val application = applicationContext as? HalfBrowserApplication ?: return Result.success()
        val container = application.container
        val settings = container.settings.current()
        // Either automatic option means the user wants the tunnel kept alive.
        // v1.8.0 checked both; narrowing this to `vpnAutoReconnect` alone would
        // strand users who chose "always connect on launch" with reconnect off,
        // because HomeScreen deliberately does not connect for them when
        // reconnect is on, and onAppForeground respects a disabled reconnect.
        if (!settings.vpnAutoReconnect && !settings.vpnAutoConnect) return Result.success()
        return runCatching {
            withContext(Dispatchers.IO) {
                // `autoReconnect` only records that this was automatic; the
                // decision to restore a missing tunnel is made by the user's
                // settings inside verifyForBackground.
                container.vpn.verifyForBackground(autoReconnect = settings.vpnAutoReconnect)
            }
            Result.success()
        }.getOrElse { error ->
            // Defensive: cancellation is normally delivered outside this block,
            // but if it ever surfaces here it must stay cancelled rather than
            // become a retry.
            if (error is kotlinx.coroutines.CancellationException) throw error
            // A transient failure (no network, service busy) must not be retried
            // aggressively; the next period is enough.
            if (runAttemptCount >= MAX_ATTEMPTS) Result.success() else Result.retry()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
