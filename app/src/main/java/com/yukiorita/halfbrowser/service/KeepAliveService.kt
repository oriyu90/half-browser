package com.yukiorita.halfbrowser.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.yukiorita.halfbrowser.MainActivity
import com.yukiorita.halfbrowser.R

/**
 * Keeps the process — and therefore the page's JavaScript — alive while the app
 * is in the background, so web notifications arrive immediately instead of when
 * the user next opens the browser.
 *
 * Android does not let a WebView app receive a site's push messages the way
 * Chrome does (that needs Google Play Services), so the only honest way to make
 * the existing page keep producing notifications is to keep it running. This
 * service is started only when the user turns the "deliver immediately" setting
 * on, shows a permanent notification while active, and is stopped as soon as the
 * browser screen is closed.
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfSafely()
            return START_NOT_STICKY
        }
        startInForeground()
        // NOT sticky: this service exists only so the page on screen can keep
        // producing notifications. If the process is killed there is no page to
        // keep alive, and resurrecting the service would leave a permanent
        // notification for a browser that is not open. BrowserScreen restarts it
        // when a page is shown again.
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        runCatching {
            ensureChannel(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_halfbrowser)
        .setContentTitle(getString(R.string.keepalive_title))
        .setContentText(getString(R.string.keepalive_text))
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setOngoing(true)
        .setSilent(true)
        .setShowWhen(false)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun stopSelfSafely() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        stopSelf()
    }

    override fun onDestroy() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "page_keep_alive"
        private const val NOTIFICATION_ID = 0x4b42
        private const val ACTION_START = "com.yukiorita.halfbrowser.KEEP_ALIVE_START"
        private const val ACTION_STOP = "com.yukiorita.halfbrowser.KEEP_ALIVE_STOP"

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(
                    Intent(context, KeepAliveService::class.java).setAction(ACTION_START),
                )
            }.onFailure {
                // Older devices / restricted profiles can refuse the start; the
                // browser keeps working, it just loses background delivery.
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, KeepAliveService::class.java).setAction(ACTION_STOP),
                )
            }.onFailure {
                runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
            }
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.keepalive_channel_name),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = context.getString(R.string.keepalive_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
