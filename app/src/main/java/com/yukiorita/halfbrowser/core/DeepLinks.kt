package com.yukiorita.halfbrowser.core

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Parses the app's internal `halfbrowser://` deep links.
 *
 * Supported forms:
 *  - halfbrowser://home
 *  - halfbrowser://open?url=<encoded>&bookmarkId=<id>
 *  - halfbrowser://bookmark/<id>
 *
 * Parsing is defensive: unknown or malformed links return null instead of throwing,
 * because intents are an untrusted input surface.
 */
object DeepLinks {

    const val SCHEME = "halfbrowser"

    sealed interface Link {
        data object Home : Link
        data class Open(val url: String, val bookmarkId: String?) : Link
        data class BookmarkById(val id: String) : Link
    }

    fun parse(raw: String?): Link? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val uri = URI(raw.trim())
            if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
            val host = uri.host?.lowercase() ?: return null
            when (host) {
                "home" -> Link.Home
                "open" -> {
                    val params = parseQuery(uri.rawQuery)
                    val url = params["url"]?.let { decode(it) } ?: return null
                    if (UrlNormalizer.validateWebUrl(url) == null) return null
                    val bookmarkId = params["bookmarkId"]?.let { decode(it) }?.takeIf { it.isNotBlank() }
                    Link.Open(url, bookmarkId)
                }
                "bookmark" -> {
                    val id = uri.rawPath.orEmpty().removePrefix("/").trim()
                    if (id.isEmpty()) return null
                    Link.BookmarkById(decode(id))
                }
                else -> null
            }
        }.getOrNull()
    }

    fun openUri(url: String, bookmarkId: String? = null): String {
        val base = "$SCHEME://open?url=${encode(url)}"
        return if (bookmarkId.isNullOrBlank()) base else "$base&bookmarkId=${encode(bookmarkId)}"
    }

    fun bookmarkUri(id: String): String = "$SCHEME://bookmark/${encode(id)}"

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&').mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null else part.substring(0, idx) to part.substring(idx + 1)
        }.toMap()
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun encode(value: String): String =
        runCatching { URLEncoder.encode(value, "UTF-8") }.getOrDefault(value)
}
