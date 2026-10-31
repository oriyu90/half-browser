package com.yukiorita.halfbrowser.core

import kotlinx.serialization.Serializable

/**
 * A user bookmark. Persisted as JSON; fields are kept primitive and additive so
 * older data keeps loading after an app update.
 */
@Serializable
data class Bookmark(
    val id: String,
    val title: String,
    val url: String,
    /** 1-2 character glyph shown on the tile. Empty means "derive from the title". */
    val glyph: String = "",
    /** Deterministic palette index override; -1 means "derive from the URL". */
    val colorSeed: Int = -1,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@Serializable
data class BookmarkList(
    val version: Int = 1,
    val items: List<Bookmark> = emptyList(),
)

/**
 * A pinned self-signed certificate for one host. Trust is scoped to the exact
 * certificate fingerprint (TOFU); a changed certificate triggers a new warning.
 */
@Serializable
data class TrustedSite(
    val host: String,
    /** Lower-case hex SHA-256 of the DER certificate, no separators. */
    val fingerprintSha256: String,
    val subject: String = "",
    val issuer: String = "",
    val addedAt: Long = 0L,
    val lastSeenAt: Long = 0L,
)

@Serializable
data class TrustedSiteList(
    val version: Int = 1,
    val items: List<TrustedSite> = emptyList(),
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class LanguageMode(val tag: String) {
    SYSTEM(""),
    JAPANESE("ja"),
    ENGLISH("en"),
}

enum class SearchEngine(val key: String, val template: String) {
    GOOGLE("google", "https://www.google.com/search?q=%s"),
    DUCKDUCKGO("duckduckgo", "https://duckduckgo.com/?q=%s"),
    BING("bing", "https://www.bing.com/search?q=%s");

    companion object {
        fun fromKey(key: String?): SearchEngine =
            entries.firstOrNull { it.key == key } ?: DUCKDUCKGO
    }
}

/**
 * All user-visible preferences. Persisted as one JSON blob so a single atomic
 * write keeps settings consistent (no partially-applied multi-key update).
 */
@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val uiScale: Float = 1.0f,
    val fontScale: Float = 1.0f,
    /** 0 = automatic (window-width based), otherwise a fixed column count. */
    val gridColumns: Int = 0,
    val language: LanguageMode = LanguageMode.SYSTEM,
    val searchEngine: SearchEngine = SearchEngine.DUCKDUCKGO,
    /** Show the address field on the home screen. */
    val showAddressBar: Boolean = true,
) {
    companion object {
        val DEFAULT = AppSettings()

        const val MIN_UI_SCALE = 0.8f
        const val MAX_UI_SCALE = 1.4f
        const val MIN_FONT_SCALE = 0.8f
        const val MAX_FONT_SCALE = 1.5f
        val ALLOWED_GRID_COLUMNS = setOf(0, 3, 4, 5, 6, 8)
    }
}

/** A request produced by an Intent, a shortcut, or the home screen. */
sealed interface NavigationRequest {
    data object Home : NavigationRequest

    data class Open(
        val url: String,
        /** Non-null when the URL was opened from a bookmark. */
        val bookmarkId: String? = null,
        val fromExternalApp: Boolean = false,
    ) : NavigationRequest
}

data class CredentialMeta(
    val id: String,
    val host: String,
    val realm: String,
    val username: String,
)
