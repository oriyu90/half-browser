package com.yukiorita.halfbrowser

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.wireguard.android.backend.GoBackend
import com.yukiorita.halfbrowser.core.SiteWatch
import com.yukiorita.halfbrowser.core.VpnAlwaysOn
import com.yukiorita.halfbrowser.data.NetworkWatcher
import com.yukiorita.halfbrowser.notifications.SiteNotifier
import com.yukiorita.halfbrowser.notifications.WebNotificationBridge
import com.yukiorita.halfbrowser.work.VpnHealthScheduler
import com.yukiorita.halfbrowser.work.WatchScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class HalfBrowserApplication : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    /** Outlives any screen; used only for cheap bookkeeping. */
    // limitedParallelism(1): the collector below never returns, so it must not
    // hold one of the shared Dispatchers.IO threads for the life of the process.
    private val applicationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1),
    )

    /** Reacts to Wi-Fi ⇄ mobile switches so a stale tunnel is noticed at once. */
    private var networkWatcher: NetworkWatcher? = null

    /**
     * Screen on/off. `Activity.onStop()` is not called when the screen turns
     * off, so without this the VPN verification loop would keep running at the
     * foreground rate in the user's pocket.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> runCatching { container.vpn.onScreenOff() }
                Intent.ACTION_SCREEN_ON -> runCatching { container.vpn.onScreenOn() }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Always-on VPN hook. The system can start the VPN service without the
        // app UI (device boot, lockdown enforcement); without this callback the
        // service would start and bring up nothing. Registration is static and
        // cheap: no native library is loaded until a connect actually happens.
        GoBackend.setAlwaysOnCallback {
            runCatching { container.vpn.onAlwaysOnTriggered() }
        }
        SiteNotifier.ensureChannel(this)
        WebNotificationBridge.ensureChannel(this)
        registerScreenReceiver()
        registerNetworkWatcher()
        runCatching {
            val c = container
            val bookmarkIds = c.bookmarks.snapshot().map { it.id }.toSet()
            c.siteWatch.prune(bookmarkIds)
            val hasEnabled = c.siteWatch.snapshot().any { watch: SiteWatch ->
                watch.enabled && watch.bookmarkId in bookmarkIds
            }
            WatchScheduler.sync(this, c.siteWatch.settingsSnapshot(), hasEnabled)
        }
        runCatching { syncVpnSchedule() }.onFailure { Log.w(TAG, "VPN health schedule failed") }
        // Keep the schedule in step with the tunnel: the verification job only
        // exists while a tunnel is up, so a disconnected app schedules nothing.
        // Dispatchers.IO, never Default: WorkManager initialises itself on a
        // single-threaded view of Dispatchers.Default, so calling
        // WorkManager.getInstance() from a Default coroutine can deadlock
        // against that initialisation (and the failure is silent, because a
        // launch's exception never reaches the surrounding runCatching).
        applicationScope.launch {
            container.vpn.state
                .map { it.isActive }
                // WorkManager's enqueue is cheap but not free, and the state
                // flow can emit the same value repeatedly.
                .distinctUntilChanged()
                .collect { runCatching { syncVpnSchedule() } }
        }
    }

    /**
     * Registers the periodic VPN jobs that are actually useful right now.
     *
     *  - verification: only while a tunnel is connected (otherwise it would wake
     *    the device for nothing);
     *  - watchdog: only when the system's Always-on VPN is enabled, because that
     *    is when a killed process must be recovered without the user's help.
     */
    private fun syncVpnSchedule() {
        val c = container
        val hasProfile = c.vpnStore.profiles.value.isNotEmpty()
        val tunnelUp = c.vpn.state.value.isActive
        VpnHealthScheduler.sync(
            context = this,
            healthCheck = hasProfile && tunnelUp,
            watchdog = hasProfile && VpnAlwaysOn.isSystemAlwaysOn(this),
        )
    }

    private fun registerScreenReceiver() {
        runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
            // These are protected system broadcasts: nothing else can send them,
            // and NOT_EXPORTED keeps other apps from triggering them on API 33+.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(screenReceiver, filter)
            }
        }.onFailure { Log.w(TAG, "screen receiver unavailable") }
    }

    private fun registerNetworkWatcher() {
        runCatching {
            val watcher = NetworkWatcher(this) {
                runCatching { container.vpn.onNetworkChanged() }
            }
            // Keep the reference only when the platform accepted it, so the
            // field means "we are actually listening".
            if (watcher.start()) networkWatcher = watcher
        }.onFailure { Log.w(TAG, "network watcher unavailable") }
    }

    private companion object {
        const val TAG = "HalfBrowserApplication"
    }
}
