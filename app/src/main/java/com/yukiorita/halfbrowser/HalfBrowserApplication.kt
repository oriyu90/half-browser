package com.yukiorita.halfbrowser

import android.app.Application
import com.yukiorita.halfbrowser.core.SiteWatch
import com.yukiorita.halfbrowser.notifications.SiteNotifier
import com.yukiorita.halfbrowser.work.WatchScheduler

class HalfBrowserApplication : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        SiteNotifier.ensureChannel(this)
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
