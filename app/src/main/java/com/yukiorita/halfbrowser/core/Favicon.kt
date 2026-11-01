package com.yukiorita.halfbrowser.core

import java.net.URI

/**
 * Pure rules for bookmark icons ("favicons").
 *
 * The app never keeps a remote icon URL: bytes are downloaded once, downscaled
 * and stored as a small PNG in app-private storage. These helpers only decide
 * what to request and how to name the file, so they can be unit-tested.
 */
object FaviconRules {

    /** Hard cap for a downloaded icon; larger responses are discarded. */
    const val MAX_BYTES = 256 * 1024

    /** Icons are normalised to this square size before being stored. */
    const val TARGET_SIZE = 192

    const val MAX_FILE_NAME = 80

    /** Directory under `filesDir` where icons live. */
    const val DIRECTORY = "bookmark_icons"

    fun iconFileName(bookmarkId: String): String {
        val safe = bookmarkId.map { ch ->
            if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '_'
        }.joinToString("").take(MAX_FILE_NAME)
        return if (safe.isBlank() || safe.trim('_').isBlank()) "icon.png" else "$safe.png"
    }

    /** Common locations tried before parsing the page markup. */
    fun candidateUrls(pageUrl: String): List<String> {
        val uri = runCatching { URI(pageUrl) }.getOrNull() ?: return emptyList()
        val scheme = uri.scheme?.lowercase() ?: return emptyList()
        if (scheme != "http" && scheme != "https") return emptyList()
        val authority = buildString {
            append(uri.host ?: return emptyList())
            if (uri.port > 0 && uri.port != defaultPort(scheme)) append(':').append(uri.port)
        }
        // Touch icons are usually 180px and look far better than a 16px .ico.
        return listOf(
            "$scheme://$authority/apple-touch-icon.png",
            "$scheme://$authority/apple-touch-icon-precomposed.png",
            "$scheme://$authority/favicon.ico",
        )
    }

    /**
     * Finds the first `<link rel="...icon...">` href and resolves it against the
     * page URL. Attribute order, quoting and relative paths are all handled.
     */
    fun parseIconHref(html: String, pageUrl: String): String? {
        if (html.isBlank()) return null
        val linkTag = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
        for (match in linkTag.findAll(html.take(200_000))) {
            val tag = match.value
            val rel = attribute(tag, "rel")?.lowercase() ?: continue
            if (!rel.contains("icon")) continue
            val href = attribute(tag, "href")?.trim().orEmpty()
            if (href.isBlank()) continue
            if (href.startsWith("data:", ignoreCase = true)) continue
            val resolved = runCatching { URI(pageUrl).resolve(href).toString() }.getOrNull()
                ?: continue
            if (!resolved.startsWith("http://", true) && !resolved.startsWith("https://", true)) {
                continue
            }
            return resolved
        }
        return null
    }

    private fun attribute(tag: String, name: String): String? {
        val pattern = Regex(
            "\\b" + Regex.escape(name) + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",
            RegexOption.IGNORE_CASE,
        )
        val match = pattern.find(tag) ?: return null
        return match.groupValues[2].ifBlank {
            match.groupValues[3].ifBlank { match.groupValues[4] }
        }
    }

    /** Cheap magic-number check so an HTML error page is never stored as an icon. */
    fun looksLikeImage(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        fun at(index: Int) = bytes[index].toInt() and 0xFF
        return when {
            at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> true // PNG
            at(0) == 0xFF && at(1) == 0xD8 -> true // JPEG
            at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> true // GIF
            at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
                at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50 -> true // WebP
            at(0) == 0x00 && at(1) == 0x00 && at(2) == 0x01 && at(3) == 0x00 -> true // ICO
            else -> false
        }
    }

    /**
     * `.ico` files often embed a PNG payload (Vista+). Android's BitmapFactory
     * cannot decode ICO containers, so the PNG slice is extracted when present.
     */
    fun extractEmbeddedPng(ico: ByteArray): ByteArray? {
        if (ico.size < 22) return null
        fun u8(i: Int) = ico[i].toInt() and 0xFF
        fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun u32(i: Int) = u16(i) or (u16(i + 2) shl 16)
        if (u8(0) != 0 || u8(1) != 0 || u8(2) != 1 || u8(3) != 0) return null
        val count = u16(4)
        if (count <= 0) return null
        for (index in 0 until count) {
            val entry = 6 + index * 16
            if (entry + 16 > ico.size) return null
            val size = u32(entry + 8)
            val offset = u32(entry + 12)
            if (size <= 0 || offset <= 0 || offset + size > ico.size) continue
            if (size >= 8 && ico[offset] == 0x89.toByte() && ico[offset + 1] == 0x50.toByte() &&
                ico[offset + 2] == 0x4E.toByte() && ico[offset + 3] == 0x47.toByte()
            ) {
                return ico.copyOfRange(offset, offset + size)
            }
        }
        return null
    }

    private fun defaultPort(scheme: String): Int = if (scheme == "https") 443 else 80
}
