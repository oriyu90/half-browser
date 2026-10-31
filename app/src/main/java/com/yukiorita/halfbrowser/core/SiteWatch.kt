package com.yukiorita.halfbrowser.core

import kotlinx.serialization.Serializable

/** Per-bookmark background watch state. */
@Serializable
data class SiteWatch(
    val bookmarkId: String,
    val enabled: Boolean = false,
    val lastCheckedAt: Long = 0L,
    val lastChangedAt: Long = 0L,
    val lastNotifiedAt: Long = 0L,
    /** SHA-256 of the normalized page text captured on the last successful check. */
    val fingerprint: String = "",
    val lastTitle: String = "",
    val lastError: String = "",
)

@Serializable
data class SiteWatchList(
    val version: Int = 1,
    val items: List<SiteWatch> = emptyList(),
)

@Serializable
data class SiteWatchSettings(
    val enabled: Boolean = true,
    val intervalMinutes: Int = 30,
    val wifiOnly: Boolean = false,
    val lastRunAt: Long = 0L,
    val lastRunResult: String = "",
)

/**
 * Pure rules shared by the scheduler, worker and settings UI.
 */
object WatchRules {
    val INTERVALS = listOf(15, 30, 60, 180)
    const val DEFAULT_INTERVAL = 30
    const val COOLDOWN_MS = 30 * 60 * 1000L
    const val MAX_SITES_PER_RUN = 20
    const val MAX_HTML_BYTES = 512 * 1024
    const val TIMEOUT_MS = 10_000

    fun normalizeInterval(minutes: Int): Int =
        if (minutes in INTERVALS) minutes else DEFAULT_INTERVAL

    fun canNotify(now: Long, lastNotifiedAt: Long): Boolean =
        lastNotifiedAt <= 0L || now - lastNotifiedAt >= COOLDOWN_MS

    fun isBaselineMissing(watch: SiteWatch): Boolean = watch.fingerprint.isBlank()
}
