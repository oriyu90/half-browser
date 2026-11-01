package com.yukiorita.halfbrowser.data

import android.content.Context
import com.yukiorita.halfbrowser.core.Codecs
import com.yukiorita.halfbrowser.core.SiteWatch
import com.yukiorita.halfbrowser.core.SiteWatchSettings
import com.yukiorita.halfbrowser.core.WatchRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Background watch configuration and last-check state.
 *
 * Kept separate from [AppSettings] because it grows with the bookmark list and is
 * written by a Worker while the UI is not running.
 */
class SiteWatchRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    private val _watches = MutableStateFlow(
        Codecs.decodeSiteWatches(prefs.getString(KEY_WATCHES, null)),
    )
    val watches: StateFlow<List<SiteWatch>> = _watches.asStateFlow()

    private val _settings = MutableStateFlow(
        Codecs.decodeWatchSettings(prefs.getString(KEY_SETTINGS, null)),
    )
    val settings: StateFlow<SiteWatchSettings> = _settings.asStateFlow()

    fun snapshot(): List<SiteWatch> = _watches.value
    fun settingsSnapshot(): SiteWatchSettings = _settings.value

    fun find(bookmarkId: String): SiteWatch? =
        snapshot().firstOrNull { it.bookmarkId == bookmarkId }

    fun isEnabled(bookmarkId: String): Boolean = find(bookmarkId)?.enabled == true

    fun setEnabled(bookmarkId: String, enabled: Boolean) {
        if (bookmarkId.isBlank()) return
        mutableWatches { current ->
            val existing = current.firstOrNull { it.bookmarkId == bookmarkId }
            if (existing == null) {
                current + SiteWatch(bookmarkId = bookmarkId, enabled = enabled)
            } else {
                current.map { if (it.bookmarkId == bookmarkId) it.copy(enabled = enabled) else it }
            }
        }
    }

    fun recordSuccess(
        bookmarkId: String,
        checkedAt: Long,
        fingerprint: String,
        title: String,
        changed: Boolean,
    ) {
        mutableWatches { current ->
            current.map {
                if (it.bookmarkId != bookmarkId) {
                    it
                } else {
                    it.copy(
                        lastCheckedAt = checkedAt,
                        fingerprint = fingerprint,
                        lastTitle = title.take(200),
                        lastChangedAt = if (changed) checkedAt else it.lastChangedAt,
                        lastError = "",
                    )
                }
            }
        }
    }

    fun recordError(bookmarkId: String, checkedAt: Long, reason: String) {
        mutableWatches { current ->
            current.map {
                if (it.bookmarkId != bookmarkId) {
                    it
                } else {
                    it.copy(
                        lastCheckedAt = checkedAt,
                        lastError = reason.take(200),
                    )
                }
            }
        }
    }

    fun markNotified(bookmarkId: String, at: Long) {
        mutableWatches { current ->
            current.map {
                if (it.bookmarkId == bookmarkId) it.copy(lastNotifiedAt = at) else it
            }
        }
    }

    /** Removes watch records whose bookmark no longer exists. */
    fun prune(validBookmarkIds: Set<String>) {
        mutableWatches { current -> current.filter { it.bookmarkId in validBookmarkIds } }
    }

    /** Bulk replacement used by backup restore. */
    fun replaceAll(watches: List<SiteWatch>, settings: SiteWatchSettings) {
        synchronized(lock) {
            val cleaned = watches.filter { it.bookmarkId.isNotBlank() }
                .distinctBy { it.bookmarkId }
                .take(500)
            val updatedSettings = settings.copy(
                intervalMinutes = WatchRules.normalizeInterval(settings.intervalMinutes),
                lastRunResult = settings.lastRunResult.take(300),
            )
            prefs.edit()
                .putString(KEY_WATCHES, Codecs.encodeSiteWatches(cleaned))
                .putString(KEY_SETTINGS, Codecs.encodeWatchSettings(updatedSettings))
                .apply()
            _watches.value = cleaned
            _settings.value = updatedSettings
        }
    }

    fun updateSettings(transform: (SiteWatchSettings) -> SiteWatchSettings) {
        synchronized(lock) {
            val updated = transform(_settings.value)
            prefs.edit().putString(KEY_SETTINGS, Codecs.encodeWatchSettings(updated)).apply()
            _settings.value = updated
        }
    }

    private fun mutableWatches(transform: (List<SiteWatch>) -> List<SiteWatch>) {
        synchronized(lock) {
            val updated = transform(_watches.value)
            if (updated == _watches.value) return
            prefs.edit().putString(KEY_WATCHES, Codecs.encodeSiteWatches(updated)).apply()
            _watches.value = updated
        }
    }

    private companion object {
        const val PREFS_NAME = "halfbrowser_site_watch"
        const val KEY_WATCHES = "watches_json"
        const val KEY_SETTINGS = "settings_json"
    }
}
