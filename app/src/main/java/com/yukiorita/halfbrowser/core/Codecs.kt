package com.yukiorita.halfbrowser.core

import kotlinx.serialization.json.Json

/**
 * JSON codecs for persisted state. Decoding never throws: corrupted or older data
 * falls back to defaults so a bad write cannot brick the app on launch.
 */
object Codecs {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    fun encodeSettings(settings: AppSettings): String =
        runCatching { json.encodeToString(AppSettings.serializer(), settings) }
            .getOrDefault("")

    fun decodeSettings(raw: String?): AppSettings {
        if (raw.isNullOrBlank()) return AppSettings.DEFAULT
        val decoded = runCatching { json.decodeFromString(AppSettings.serializer(), raw) }.getOrNull()
        return sanitize(decoded ?: AppSettings.DEFAULT)
    }

    fun sanitize(settings: AppSettings): AppSettings = settings.copy(
        uiScale = settings.uiScale.coerceIn(AppSettings.MIN_UI_SCALE, AppSettings.MAX_UI_SCALE),
        fontScale = settings.fontScale.coerceIn(AppSettings.MIN_FONT_SCALE, AppSettings.MAX_FONT_SCALE),
        gridColumns = if (settings.gridColumns in AppSettings.ALLOWED_GRID_COLUMNS) settings.gridColumns else 0,
        language = if (settings.language in LanguageMode.entries) settings.language else LanguageMode.SYSTEM,
        searchEngine = settings.searchEngine,
        themeMode = if (settings.themeMode in ThemeMode.entries) settings.themeMode else ThemeMode.SYSTEM,
        accentHue = ((settings.accentHue % 360f) + 360f) % 360f,
        accentSaturation = settings.accentSaturation
            .coerceIn(AppSettings.MIN_ACCENT_SATURATION, AppSettings.MAX_ACCENT_SATURATION),
        accentTone = settings.accentTone.coerceIn(0f, 1f),
        vpnSelectedProfileId = settings.vpnSelectedProfileId.take(64),
    )

    fun encodeBookmarks(items: List<Bookmark>): String =
        runCatching { json.encodeToString(BookmarkList.serializer(), BookmarkList(items = items)) }
            .getOrDefault("")

    fun decodeBookmarks(raw: String?): List<Bookmark> {
        if (raw.isNullOrBlank()) return emptyList()
        val list = runCatching { json.decodeFromString(BookmarkList.serializer(), raw) }.getOrNull()
            ?: return emptyList()
        // Defensive filtering: never surface an entry that cannot be navigated to.
        return list.items
            .filter { it.id.isNotBlank() && UrlNormalizer.validateWebUrl(it.url) != null }
            .distinctBy { it.id }
    }

    fun encodeSiteWatches(items: List<SiteWatch>): String =
        runCatching { json.encodeToString(SiteWatchList.serializer(), SiteWatchList(items = items)) }
            .getOrDefault("")

    fun decodeSiteWatches(raw: String?): List<SiteWatch> {
        if (raw.isNullOrBlank()) return emptyList()
        val list = runCatching { json.decodeFromString(SiteWatchList.serializer(), raw) }.getOrNull()
            ?: return emptyList()
        return list.items
            .filter { it.bookmarkId.isNotBlank() }
            .distinctBy { it.bookmarkId }
    }

    fun encodeWatchSettings(settings: SiteWatchSettings): String =
        runCatching { json.encodeToString(SiteWatchSettings.serializer(), settings) }
            .getOrDefault("")

    fun decodeWatchSettings(raw: String?): SiteWatchSettings {
        val decoded = raw?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.decodeFromString(SiteWatchSettings.serializer(), it) }.getOrNull()
        } ?: SiteWatchSettings()
        return decoded.copy(
            intervalMinutes = WatchRules.normalizeInterval(decoded.intervalMinutes),
            lastRunResult = decoded.lastRunResult.take(300),
        )
    }

    fun encodeTrustedSites(items: List<TrustedSite>): String =
        runCatching { json.encodeToString(TrustedSiteList.serializer(), TrustedSiteList(items = items)) }
            .getOrDefault("")

    fun decodeTrustedSites(raw: String?): List<TrustedSite> {
        if (raw.isNullOrBlank()) return emptyList()
        val list = runCatching { json.decodeFromString(TrustedSiteList.serializer(), raw) }.getOrNull()
            ?: return emptyList()
        return list.items
            .filter { it.host.isNotBlank() && it.fingerprintSha256.isNotBlank() }
            .distinctBy { it.host.lowercase() + ":" + it.port }
    }

    fun encodeSitePermissions(items: List<SitePermissionGrant>): String =
        runCatching {
            json.encodeToString(
                SitePermissionList.serializer(),
                SitePermissionList(items = items),
            )
        }.getOrDefault("")

    fun decodeSitePermissions(raw: String?): List<SitePermissionGrant> {
        if (raw.isNullOrBlank()) return emptyList()
        val list = runCatching { json.decodeFromString(SitePermissionList.serializer(), raw) }
            .getOrNull() ?: return emptyList()
        return list.items
            .filter {
                SitePermissions.isWebOrigin(it.origin) &&
                    SitePermissionKind.fromKey(it.kind) != null
            }
            .distinctBy { it.origin + "|" + it.kind }
    }
}
