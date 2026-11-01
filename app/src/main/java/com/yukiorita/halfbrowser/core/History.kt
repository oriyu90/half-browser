package com.yukiorita.halfbrowser.core

import kotlinx.serialization.Serializable

/** One visited page. Kept small: the history is a convenience, not an archive. */
@Serializable
data class HistoryEntry(
    val url: String,
    val title: String = "",
    val visitedAt: Long = 0L,
    val visitCount: Int = 1,
)

@Serializable
data class HistoryList(
    val version: Int = 1,
    val items: List<HistoryEntry> = emptyList(),
)

/**
 * Pure history rules: adding a page never grows the list without bound and a
 * reload of the same page updates the existing row instead of duplicating it.
 */
object HistoryRules {

    const val MAX_ENTRIES = 500

    /** Re-visits of the same URL within this window are treated as one visit. */
    const val MERGE_WINDOW_MS = 30 * 60 * 1000L

    fun record(
        current: List<HistoryEntry>,
        url: String,
        title: String,
        now: Long,
    ): List<HistoryEntry> {
        val normalized = UrlNormalizer.validateWebUrl(url) ?: return current
        val cleanTitle = title.trim().take(300)
        val existingIndex = current.indexOfFirst {
            UrlNormalizer.canonicalForCompare(it.url) == UrlNormalizer.canonicalForCompare(normalized)
        }
        val head = if (existingIndex >= 0) {
            val existing = current[existingIndex]
            val recent = now - existing.visitedAt <= MERGE_WINDOW_MS
            val updated = existing.copy(
                url = normalized,
                title = cleanTitle.ifBlank { existing.title },
                visitedAt = now,
                visitCount = if (recent) existing.visitCount else existing.visitCount + 1,
            )
            listOf(updated) + current.filterIndexed { index, _ -> index != existingIndex }
        } else {
            listOf(HistoryEntry(url = normalized, title = cleanTitle, visitedAt = now)) + current
        }
        return head.take(MAX_ENTRIES)
    }

    fun sanitize(current: List<HistoryEntry>): List<HistoryEntry> =
        current.asSequence()
            .filter { UrlNormalizer.validateWebUrl(it.url) != null }
            .distinctBy { UrlNormalizer.canonicalForCompare(it.url) }
            .take(MAX_ENTRIES)
            .toList()
}
