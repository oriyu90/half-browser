package com.yukiorita.halfbrowser.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yukiorita.halfbrowser.HalfBrowserApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Periodic VPN health check.
 *
 * While the app is in the background the foreground poller is stopped, so a
 * tunnel whose NAT mapping expired would go unnoticed until the user opens the
 * app again. This worker reads the handshake time of the live tunnel, records
 * it, and — only when the user allowed it and consent is already granted —
 * reconnects a tunnel that went stale.
 *
 * The worker may be the first thing to run after the process was recreated;
 * [com.yukiorita.halfbrowser.data.VpnController.verifyForBackground] handles
 * that by reading the persisted session instead of the in-memory state.
 *
 * It does nothing when the user turned both automatic options off, so it costs
 * a single cheap check per period otherwise.
 */
class VpnHealthWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val application = applicationContext as? HalfBrowserApplication ?: return Result.success()
        val container = application.container
        val settings = container.settings.current()
        if (!settings.vpnAutoReconnect && !settings.vpnAutoConnect) {
            // Nothing to keep alive: stop the schedule from the next sync.
            return Result.success()
        }
        return runCatching {
            withContext(Dispatchers.IO) {
                container.vpn.verifyForBackground(settings.vpnAutoReconnect)
            }
            Result.success()
        }.getOrElse { error ->
            // A transient failure (no network, service busy) must not be retried
            // aggressively; the next period is enough.
            if (runAttemptCount >= MAX_ATTEMPTS) Result.success() else Result.retry()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
