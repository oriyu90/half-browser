package com.yukiorita.halfbrowser

import android.app.Application
import com.wireguard.android.backend.GoBackend
import com.yukiorita.halfbrowser.core.SiteWatch
import com.yukiorita.halfbrowser.notifications.SiteNotifier
import com.yukiorita.halfbrowser.notifications.WebNotificationBridge
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
    }
}
