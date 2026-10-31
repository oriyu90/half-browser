package com.yukiorita.halfbrowser.core

import java.security.MessageDigest

/**
 * Turns an HTML document into a stable fingerprint for change detection.
 *
 * The fingerprint deliberately ignores markup, scripts, styles and whitespace so
 * that a rotating ad banner or a re-ordered attribute does not trigger a false
 * "page changed" notification. It is not a DOM parser; it is a bounded, cheap
 * approximation suitable for a 512 KiB background fetch.
 */
object SiteContent {

    private val SCRIPT = Regex("<script\\b[^>]*>.*?</script\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val STYLE = Regex("<style\\b[^>]*>.*?</style\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val NOSCRIPT = Regex("<noscript\\b[^>]*>.*?</noscript\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val TAG = Regex("<[^>]+>", RegexOption.DOT_MATCHES_ALL)
    private val WHITESPACE = Regex("[\\s\\u00a0]+")
    private val TITLE = Regex("<title\\b[^>]*>(.*?)</title\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    fun normalize(html: String, maxChars: Int = 20_000): String {
        var text = html
        text = SCRIPT.replace(text, " ")
        text = STYLE.replace(text, " ")
        text = NOSCRIPT.replace(text, " ")
        text = COMMENT.replace(text, " ")
        text = TAG.replace(text, " ")
        text = decodeEntities(text)
        text = WHITESPACE.replace(text, " ").trim()
        return if (text.length > maxChars) text.substring(0, maxChars) else text
    }

    fun titleOf(html: String): String =
        TITLE.find(html)?.groupValues?.getOrNull(1)
            ?.let { decodeEntities(it) }
            ?.replace(WHITESPACE, " ")
            ?.trim()
            ?.take(200)
            .orEmpty()

    fun fingerprint(html: String): String {
        val normalized = normalize(html)
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    fun snippet(html: String, maxChars: Int = 160): String {
        val normalized = normalize(html, maxChars = 1_000)
        return if (normalized.length > maxChars) {
            normalized.substring(0, maxChars).trimEnd() + "…"
        } else {
            normalized
        }
    }

    /**
     * Extracts the realm from `WWW-Authenticate: Basic realm="X", charset="UTF-8"`.
     * Returns an empty string when the header does not carry a quoted realm.
     */
    fun basicRealm(header: String?): String {
        if (header.isNullOrBlank()) return ""
        val quoted = Regex("realm\\s*=\\s*\"([^\"]*)\"", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.getOrNull(1)
        if (!quoted.isNullOrBlank()) return quoted.take(200)
        return Regex("realm\\s*=\\s*([^,\\s]+)", RegexOption.IGNORE_CASE)
            .find(header)?.groupValues?.getOrNull(1)
            ?.take(200)
            .orEmpty()
    }

    fun decodeEntities(value: String): String = value
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
}
