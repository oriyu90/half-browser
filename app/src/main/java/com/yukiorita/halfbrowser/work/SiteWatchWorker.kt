package com.yukiorita.halfbrowser.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yukiorita.halfbrowser.HalfBrowserApplication
import com.yukiorita.halfbrowser.core.SiteWatch
import com.yukiorita.halfbrowser.core.WatchRules
import com.yukiorita.halfbrowser.data.FetchResult
import com.yukiorita.halfbrowser.notifications.SiteNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sequential, bounded page check. No WebView, no images, no cookies: one short
 * HTTP request per site, 512 KiB cap, 10 s timeout, at most 20 sites per run.
 */
class SiteWatchWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val application = applicationContext as? HalfBrowserApplication ?: return Result.success()
        val container = application.container
        val settings = container.siteWatch.settingsSnapshot()
        if (!settings.enabled) return Result.success()

        val bookmarks = container.bookmarks.snapshot()
        val targets = container.siteWatch.snapshot()
            .asSequence()
            .filter { it.enabled }
            .mapNotNull { watch ->
                bookmarks.firstOrNull { it.id == watch.bookmarkId }?.let { watch to it }
            }
            .sortedBy { it.first.lastCheckedAt }
            .take(WatchRules.MAX_SITES_PER_RUN)
            .toList()

        if (targets.isEmpty()) return Result.success()

        var ok = 0
        var failed = 0
        var notified = 0
        for ((watch, bookmark) in targets) {
            if (isStopped) break
            val now = System.currentTimeMillis()
            val result = runCatching {
                withContext(Dispatchers.IO) { container.siteFetcher.fetch(bookmark) }
            }.getOrElse { FetchResult.Failure(it.javaClass.simpleName) }

            when (result) {
                is FetchResult.Failure -> {
                    container.siteWatch.recordError(watch.bookmarkId, now, result.reason)
                    failed++
                }
                is FetchResult.Success -> {
                    val isBaseline = WatchRules.isBaselineMissing(watch)
                    val changed = !isBaseline && watch.fingerprint != result.fingerprint
                    container.siteWatch.recordSuccess(
                        bookmarkId = watch.bookmarkId,
                        checkedAt = now,
                        fingerprint = result.fingerprint,
                        title = result.title,
                        changed = changed,
                    )
                    ok++
                    if (changed &&
                        WatchRules.canNotify(now, watch.lastNotifiedAt) &&
                        SiteNotifier.canPost(application)
                    ) {
                        SiteNotifier.notify(application, bookmark, result.title, result.snippet)
                        container.siteWatch.markNotified(watch.bookmarkId, now)
                        notified++
                    }
                }
            }
        }

        container.siteWatch.updateSettings {
            it.copy(
                lastRunAt = System.currentTimeMillis(),
                lastRunResult = "ok=$ok failed=$failed notified=$notified",
            )
        }
        return Result.success()
    }
}
