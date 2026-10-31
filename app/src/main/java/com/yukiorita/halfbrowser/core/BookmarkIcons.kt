package com.yukiorita.halfbrowser.core

/**
 * Pure helpers for bookmark tile identity, deterministic colours and launcher
 * shortcut ids. No Android dependencies so the behaviour is unit-testable.
 */
object BookmarkIcons {

    /** Material-ish palette that reads well on both light and dark surfaces. */
    val PALETTE: List<Long> = listOf(
        0xFF2563EB, // blue
        0xFF0EA5E9, // sky
        0xFF14B8A6, // teal
        0xFF22C55E, // green
        0xFFEAB308, // amber
        0xFFF97316, // orange
        0xFFEF4444, // red
        0xFFEC4899, // pink
        0xFF8B5CF6, // violet
        0xFF64748B, // slate
    )

    fun paletteIndexFor(url: String, seed: Int): Int {
        if (seed in PALETTE.indices) return seed
        val normalized = url.trim().lowercase()
        val hash = normalized.fold(7) { acc, ch -> (acc * 31 + ch.code) and 0x7fffffff }
        return hash % PALETTE.size
    }

    fun colorFor(url: String, seed: Int = -1): Long = PALETTE[paletteIndexFor(url, seed)]

    /**
     * Returns a 1-2 character glyph. Any user-provided glyph wins; otherwise the
     * first letter of the title, falling back to the first letter of the host.
     */
    fun glyphFor(title: String, url: String, userGlyph: String = ""): String {
        val override = userGlyph.trim()
        if (override.isNotEmpty()) return override.take(2)
        val source = title.trim().ifBlank { UrlNormalizer.displayHost(url).orEmpty() }
        val first = source.firstOrNull { it.isLetterOrDigit() }
        return first?.uppercase() ?: "•"
    }

    /** Shortcut ids must be stable and unique per bookmark, and are never reused. */
    fun shortcutId(bookmarkId: String): String = "bookmark_$bookmarkId"

    fun bookmarkIdFromShortcut(shortcutId: String): String? =
        shortcutId.removePrefix("bookmark_").takeIf { it.isNotEmpty() && it != shortcutId }

    /** Launcher labels are truncated by the system; keep them short and predictable. */
    fun shortcutLabel(title: String, url: String): String {
        val clean = title.trim().ifBlank { UrlNormalizer.displayHost(url).orEmpty() }
        return clean.take(40).ifBlank { "Bookmark" }
    }

    fun normalizedTitleOrFallback(title: String, url: String): String {
        val clean = title.trim()
        return clean.ifBlank { UrlNormalizer.displayHost(url).orEmpty().ifBlank { url } }
    }
}
