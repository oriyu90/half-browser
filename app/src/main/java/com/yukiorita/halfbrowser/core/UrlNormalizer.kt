package com.yukiorita.halfbrowser.core

import java.net.URI
import java.net.URLEncoder

/**
 * Pure address-bar parsing. Kept free of Android APIs so it can be exhaustively
 * unit-tested on the JVM, including malformed input (must return null, never throw).
 */
object UrlNormalizer {

    private val HOST_LIKE = Regex(
        "^(" +
            "localhost" +
            "|\\d{1,3}(\\.\\d{1,3}){3}" +
            "|[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+" +
            ")(:\\d{1,5})?([/?#].*)?$",
        RegexOption.IGNORE_CASE,
    )

    private val ALLOWED_SCHEMES = setOf("http", "https")

    /**
     * Turns user input into a URL to load.
     *
     * - Blank -> null
     * - Explicit http(s) URL -> normalised URL
     * - Host-like text ("example.com", "192.168.0.165:8844", "localhost:8080/x")
     *   -> https:// prefixed
     * - Anything else -> search query using [searchTemplate]
     *
     * Non-web schemes (javascript:, file:, intent:, data:) are always rejected
     * from the address bar: a pasted `javascript:` must not execute in the page.
     */
    fun toNavigable(input: String, searchTemplate: String = SearchEngine.DUCKDUCKGO.template): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        if (trimmed.contains(' ')) return searchUrl(trimmed, searchTemplate)

        // "host:port/path" also matches the generic scheme pattern ("localhost:8443"
        // looks like scheme "localhost"), so host-like input must be probed first.
        if (HOST_LIKE.matches(trimmed)) {
            return validateWebUrl("https://$trimmed")
        }

        // Reject script-y or local schemes, then accept only http(s).
        val schemeMatch = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):").find(trimmed)
        if (schemeMatch != null) {
            val scheme = schemeMatch.groupValues[1].lowercase()
            if (scheme !in ALLOWED_SCHEMES) return null
            return validateWebUrl(trimmed)
        }

        // A bare word such as "example" is a search, but "example.com" was caught above.
        return searchUrl(trimmed, searchTemplate)
    }

    /**
     * Normalises a URL typed into the bookmark editor. Unlike [toNavigable], a
     * non-URL string is rejected instead of being turned into a search query.
     */
    fun normalizeForBookmark(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return validateWebUrl(trimmed)
        }
        if (HOST_LIKE.matches(trimmed)) return validateWebUrl("https://$trimmed")
        return null
    }

    fun validateWebUrl(url: String): String? {
        val candidate = url.trim()
        if (candidate.isEmpty()) return null
        return runCatching {
            val uri = URI(candidate)
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme !in ALLOWED_SCHEMES) return null
            val host = uri.host ?: return null
            if (host.isBlank()) return null
            // URI allows 'user@host'; keep it out of the address bar to avoid confusion.
            uri.toASCIIString()
        }.getOrNull()
    }

    /** Stable identity for "is this the same page as that bookmark" checks. */
    fun canonicalForCompare(url: String): String? {
        val validated = validateWebUrl(url) ?: return null
        return runCatching {
            val uri = URI(validated)
            val scheme = uri.scheme.lowercase()
            val host = (uri.host ?: return null).lowercase()
            val port = uri.port
            val defaultPort = (scheme == "https" && port == 443) || (scheme == "http" && port == 80)
            val portPart = if (port == -1 || defaultPort) "" else ":$port"
            var path = uri.rawPath.orEmpty()
            if (path.isEmpty()) path = "/"
            // Trailing slash is not significant for the page identity.
            if (path.length > 1 && path.endsWith("/")) path = path.dropLast(1)
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            "$scheme://$host$portPart$path$query"
        }.getOrNull()
    }

    fun displayHost(url: String): String? {
        val validated = validateWebUrl(url) ?: return null
        return runCatching { URI(validated).host }.getOrNull()
    }

    fun searchUrl(query: String, searchTemplate: String = SearchEngine.DUCKDUCKGO.template): String {
        val encoded = runCatching { URLEncoder.encode(query, "UTF-8") }.getOrDefault(query)
        return searchTemplate.replace("%s", encoded)
    }
}
