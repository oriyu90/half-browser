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
    /** File name of a downloaded favicon under `bookmark_icons/`; empty = none. */
    val icon: String = "",
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
    /** TCP port the certificate was trusted on. 0 means "any port" (legacy data). */
    val port: Int = 0,
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

enum class SearchEngine(val key: String, val template: String, val label: String) {
    GOOGLE("google", "https://www.google.com/search?q=%s", "Google"),
    DUCKDUCKGO("duckduckgo", "https://duckduckgo.com/?q=%s", "DuckDuckGo"),
    BRAVE("brave", "https://search.brave.com/search?q=%s", "Brave Search"),
    ECOSIA("ecosia", "https://www.ecosia.org/search?q=%s", "Ecosia"),
    KAGI("kagi", "https://kagi.com/search?q=%s", "Kagi Search"),
    MOJEEK("mojeek", "https://www.mojeek.com/search?q=%s", "Mojeek");

    companion object {
        /** Unknown or retired keys (e.g. the removed "bing") fall back to DuckDuckGo. */
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
    /** Accent hue in degrees (0-360). 222 reproduces the original brand blue. */
    val accentHue: Float = 222f,
    /** Accent saturation, 0-1. */
    val accentSaturation: Float = 0.86f,
    /** Accent brightness/lightness bias, 0-1 (0.5 is neutral). */
    val accentTone: Float = 0.5f,
    /** Fill saved HTML form logins on https pages. Never submits automatically. */
    val formAutofillEnabled: Boolean = true,
    /** Download a site icon when a bookmark is created without a custom glyph. */
    val fetchBookmarkIcons: Boolean = true,
    /**
     * Keep the open page running in the background so web notifications arrive
     * immediately (a foreground service is shown while this is on).
     */
    val backgroundWebNotifications: Boolean = false,
    /**
     * WireGuard profile the user last chose on the home screen. Kept as an id so
     * a deleted profile simply leaves the panel without a selection.
     */
    val vpnSelectedProfileId: String = "",
    /**
     * Connect the selected profile automatically on launch, even when no tunnel
     * is running (e.g. after the user disconnected, or after a reboot).
     *
     * [vpnAutoReconnect] restores a tunnel that *was* running; this one starts
     * one on every launch. Neither ever shows a system dialog: they only run
     * when the user already granted VPN consent. When both are on, the
     * reconnect path wins and this one does nothing extra.
     */
    val vpnAutoConnect: Boolean = false,
    /**
     * After the app comes back to the foreground (or a background check runs),
     * verify the tunnel's handshake and reconnect it when it went stale — the
     * "the VPN says connected after sleep but nothing loads" case. On by
     * default because it never shows a system dialog: it only runs when the
     * user already granted VPN consent.
     */
    val vpnAutoReconnect: Boolean = true,
    /**
     * Send the WireGuard keepalive every 60 s instead of every 25 s.
     *
     * 25 s is the value WireGuard recommends behind NAT, so this trades a
     * little robustness for battery and data (0.25 → 0.10 MiB/day). Off by
     * default: a strict firewall can drop an idle tunnel at 60 s.
     */
    val vpnPowerSaver: Boolean = false,
    /**
     * Defence against hostile in-page JavaScript (dialog rate limits, scripted
     * popups, `javascript:` navigation). On by default; the toggle exists for a
     * site that legitimately needs many dialogs.
     */
    val jsGuardEnabled: Boolean = true,
) {
    companion object {
        val DEFAULT = AppSettings()

        const val MIN_UI_SCALE = 0.8f
        const val MAX_UI_SCALE = 1.4f
        const val MIN_FONT_SCALE = 0.8f
        const val MAX_FONT_SCALE = 1.5f
        const val MIN_ACCENT_SATURATION = 0.15f
        const val MAX_ACCENT_SATURATION = 1.0f
        const val DEFAULT_ACCENT_HUE = 222f
        const val DEFAULT_ACCENT_SATURATION = 0.86f
        const val DEFAULT_ACCENT_TONE = 0.5f
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
