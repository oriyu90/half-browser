package com.yukiorita.halfbrowser

import android.app.Application
import com.wireguard.android.backend.GoBackend
import com.yukiorita.halfbrowser.core.SiteWatch
import com.yukiorita.halfbrowser.notifications.SiteNotifier
import com.yukiorita.halfbrowser.notifications.WebNotificationBridge
import com.yukiorita.halfbrowser.work.VpnHealthScheduler
import com.yukiorita.halfbrowser.work.WatchScheduler

class HalfBrowserApplication : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

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
        runCatching {
            val c = container
            val bookmarkIds = c.bookmarks.snapshot().map { it.id }.toSet()
            c.siteWatch.prune(bookmarkIds)
            val hasEnabled = c.siteWatch.snapshot().any { watch: SiteWatch ->
                watch.enabled && watch.bookmarkId in bookmarkIds
            }
            WatchScheduler.sync(this, c.siteWatch.settingsSnapshot(), hasEnabled)
        }
        runCatching {
            // Keep the periodic VPN check in step with the profile list and the
            // user's automatic-reconnect choices. Scheduled only when there is a
            // profile to keep alive; cancelled otherwise.
            val c = container
            val settings = c.settings.current()
            val hasProfile = c.vpnStore.profiles.value.isNotEmpty()
            VpnHealthScheduler.sync(
                this,
                enabled = hasProfile && (settings.vpnAutoReconnect || settings.vpnAutoConnect),
            )
        }.onFailure { android.util.Log.w("HalfBrowserApplication", "VPN health schedule failed") }
    }
}
