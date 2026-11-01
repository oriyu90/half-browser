package com.yukiorita.halfbrowser.core

import kotlinx.serialization.Serializable
import java.net.URI

/**
 * The permissions a web site can ask this browser for.
 *
 * Values are persisted by their string key so a future rename of the enum
 * constant does not silently drop the user's decision. [NOTIFICATIONS] is not
 * offered by Android WebView itself (its `PermissionRequest` has no notification
 * resource); it is served by the in-app notification bridge.
 */
enum class SitePermissionKind(val key: String) {
    NOTIFICATIONS("notifications"),
    CAMERA("camera"),
    MICROPHONE("microphone"),
    LOCATION("location"),
    ;

    companion object {
        fun fromKey(key: String?): SitePermissionKind? =
            entries.firstOrNull { it.key == key }

        /**
         * Maps a WebView `PermissionRequest` resource string to our kind.
         * Unknown resources (protected media, MIDI, …) return null and keep the
         * old per-request behaviour.
         */
        fun fromWebResource(resource: String?): SitePermissionKind? = when (resource) {
            "android.webkit.resource.VIDEO_CAPTURE" -> CAMERA
            "android.webkit.resource.AUDIO_CAPTURE" -> MICROPHONE
            else -> null
        }
    }
}

/**
 * One stored decision, keyed by origin (scheme://host[:port]).
 * A missing row means "ask every time" — that is the default for every site.
 */
@Serializable
data class SitePermissionGrant(
    val origin: String,
    /** [SitePermissionKind.key]; unknown keys are ignored on load. */
    val kind: String,
    val allowed: Boolean,
    val updatedAt: Long = 0L,
)

@Serializable
data class SitePermissionList(
    val version: Int = 1,
    val items: List<SitePermissionGrant> = emptyList(),
)

/**
 * Origin handling for the site-permission store.
 *
 * Android WebView reports origins as `scheme://host[:port]`. Decisions are
 * scoped to that exact origin (never to a bare host) so `http://` and
 * `https://`, or two ports, cannot inherit each other's grants.
 *
 * Parsing is done with [java.net.URI] rather than `android.net.Uri` so the rules
 * stay unit-testable on the JVM.
 */
object SitePermissions {

    fun originOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        // Reject control characters and embedded whitespace early: URI would
        // throw for those, and a browser origin never contains them.
        if (trimmed.any { it.isWhitespace() || it.code < 0x20 }) return null
        val parts = split(trimmed) ?: return null
        val scheme = parts.scheme
        if (scheme != "http" && scheme != "https") return null
        val host = parts.host.lowercase()
        if (!isPlausibleHost(host)) return null
        val defaultPort = if (scheme == "https") 443 else 80
        return if (parts.port <= 0 || parts.port == defaultPort) {
            "$scheme://$host"
        } else {
            "$scheme://$host:${parts.port}"
        }
    }

    /** Display label for a stored origin (`https://example.com` -> `example.com`). */
    fun hostOf(origin: String): String =
        origin.substringAfter("://", origin)
            .substringBefore('/')
            .substringBefore(':')
            .ifBlank { origin }

    fun isWebOrigin(origin: String): Boolean =
        origin.startsWith("https://") || origin.startsWith("http://")

    private class Parts(val scheme: String, val host: String, val port: Int)

    private fun split(url: String): Parts? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = url.substring(0, schemeEnd).lowercase()
        val rest = url.substring(schemeEnd + 3)
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isBlank()) return null
        // User info is legal in a URL but never part of an origin.
        val hostPort = authority.substringAfterLast('@')
        if (hostPort.isBlank()) return null
        if (hostPort.startsWith("[")) {
            // IPv6 literal: [::1]:8080
            val close = hostPort.indexOf(']')
            if (close <= 0) return null
            val host = hostPort.substring(1, close)
            val port = hostPort.substring(close + 1).removePrefix(":").toIntOrNull() ?: -1
            return Parts(scheme, host, port)
        }
        val colon = hostPort.lastIndexOf(':')
        if (colon > 0) {
            val port = hostPort.substring(colon + 1).toIntOrNull()
            if (port == null) return null
            return Parts(scheme, hostPort.substring(0, colon), port)
        }
        return Parts(scheme, hostPort, -1)
    }

    private fun isPlausibleHost(host: String): Boolean {
        if (host.isBlank()) return false
        if (host == "localhost") return true
        if (host.startsWith("[")) return true
        // A dot, or a single-label host (intranet). Whitespace and slashes are
        // already excluded by the callers.
        return host.none { it == '/' || it == '\\' || it == '@' || it == ':' }
    }

    /** Test/utility helper: canonical form of any URL, or null when not web. */
    fun canonicalOrNull(url: String?): String? = runCatching {
        val uri = URI(url ?: return null)
        uri.normalize().toString()
    }.getOrNull()
}
