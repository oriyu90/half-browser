package com.yukiorita.halfbrowser.core

import kotlinx.serialization.Serializable

/**
 * Portable backup of every user-created item in Half-Browser.
 *
 * The file is plain JSON so it stays inspectable and forward-compatible: unknown
 * fields are ignored on import and every field has a default. Passwords are the
 * one exception, and they are only ever present as a passphrase-encrypted blob.
 */
@Serializable
data class BackupFile(
    val format: String = BackupCodec.FORMAT,
    val version: Int = BackupCodec.CURRENT_VERSION,
    val exportedAt: Long = 0L,
    val appVersion: String = "",
    val settings: AppSettings = AppSettings.DEFAULT,
    val bookmarks: List<Bookmark> = emptyList(),
    val siteWatches: List<SiteWatch> = emptyList(),
    val watchSettings: SiteWatchSettings = SiteWatchSettings(),
    val trustedSites: List<TrustedSite> = emptyList(),
    val credentials: EncryptedCredentialPayload? = null,
    /** Number of credentials inside [credentials]; shown without decrypting. */
    val credentialCount: Int = 0,
)

@Serializable
data class EncryptedCredentialPayload(
    val scheme: String = BackupCrypto.SCHEME,
    val iterations: Int = BackupCrypto.DEFAULT_ITERATIONS,
    val salt: String,
    val iv: String,
    val ciphertext: String,
)

enum class BackupError {
    EMPTY,
    TOO_LARGE,
    INVALID_JSON,
    UNSUPPORTED_FORMAT,
    UNSUPPORTED_VERSION,
}

sealed interface BackupDecodeResult {
    data class Success(val file: BackupFile) : BackupDecodeResult
    data class Failure(val error: BackupError) : BackupDecodeResult
}

object BackupCodec {

    const val FORMAT = "halfbrowser.backup"
    const val CURRENT_VERSION = 1
    const val MAX_BYTES = 4 * 1024 * 1024

    fun encode(file: BackupFile): String =
        runCatching { Codecs.json.encodeToString(BackupFile.serializer(), file) }.getOrDefault("")

    fun decode(raw: String?, byteSize: Int = -1): BackupDecodeResult {
        if (raw.isNullOrBlank()) return BackupDecodeResult.Failure(BackupError.EMPTY)
        val size = if (byteSize >= 0) byteSize else raw.toByteArray(Charsets.UTF_8).size
        if (size > MAX_BYTES) return BackupDecodeResult.Failure(BackupError.TOO_LARGE)
        val decoded = runCatching {
            Codecs.json.decodeFromString(BackupFile.serializer(), raw)
        }.getOrNull() ?: return BackupDecodeResult.Failure(BackupError.INVALID_JSON)
        if (decoded.format != FORMAT) {
            return BackupDecodeResult.Failure(BackupError.UNSUPPORTED_FORMAT)
        }
        if (decoded.version !in 1..CURRENT_VERSION) {
            return BackupDecodeResult.Failure(BackupError.UNSUPPORTED_VERSION)
        }
        return BackupDecodeResult.Success(BackupRules.sanitize(decoded))
    }
}

/**
 * Validation and merge rules for imported data. A backup can be hand-edited or
 * come from a newer build, so nothing is trusted: URLs must validate, counts are
 * capped, and duplicate identities are collapsed.
 */
object BackupRules {

    const val MAX_BOOKMARKS = 500
    const val MAX_TRUSTED_SITES = 100
    const val MAX_WATCHES = 500

    fun sanitize(file: BackupFile): BackupFile {
        val bookmarks = buildList {
            val ids = mutableSetOf<String>()
            val urls = mutableSetOf<String>()
            for (bookmark in file.bookmarks) {
                if (bookmark.id.isBlank() || !ids.add(bookmark.id)) continue
                val url = UrlNormalizer.validateWebUrl(bookmark.url) ?: continue
                val canonical = UrlNormalizer.canonicalForCompare(url) ?: continue
                if (!urls.add(canonical)) continue
                add(
                    bookmark.copy(
                        url = url,
                        title = bookmark.title.trim().take(200),
                        glyph = bookmark.glyph.trim().take(2),
                        colorSeed = bookmark.colorSeed.coerceIn(-1, 1_000_000),
                    ),
                )
                if (size >= MAX_BOOKMARKS) break
            }
        }

        val bookmarkedIds = bookmarks.map { it.id }.toSet()
        val watches = file.siteWatches.asSequence()
            .filter { it.bookmarkId in bookmarkedIds }
            .map {
                it.copy(
                    fingerprint = it.fingerprint.take(128),
                    lastTitle = it.lastTitle.take(200),
                    lastError = it.lastError.take(200),
                )
            }
            .distinctBy { it.bookmarkId }
            .take(MAX_WATCHES)
            .toList()

        val trusted = file.trustedSites.asSequence()
            .filter { it.host.isNotBlank() && it.fingerprintSha256.isNotBlank() }
            .map {
                it.copy(
                    host = it.host.trim().lowercase().take(255),
                    port = it.port.coerceIn(0, 65535),
                    fingerprintSha256 = TrustPolicy.normalizeFingerprint(it.fingerprintSha256),
                    subject = it.subject.take(300),
                    issuer = it.issuer.take(300),
                )
            }
            .distinctBy { it.host + ":" + it.port }
            .take(MAX_TRUSTED_SITES)
            .toList()

        return file.copy(
            settings = Codecs.sanitize(file.settings),
            bookmarks = bookmarks,
            siteWatches = watches,
            watchSettings = Codecs.decodeWatchSettings(
                Codecs.encodeWatchSettings(file.watchSettings),
            ),
            trustedSites = trusted,
        )
    }

    /** Existing bookmarks win; imported ones are added only when their id and URL are new. */
    fun mergeBookmarks(current: List<Bookmark>, imported: List<Bookmark>): List<Bookmark> {
        val result = current.toMutableList()
        val ids = current.map { it.id }.toMutableSet()
        val urls = current.mapNotNull { UrlNormalizer.canonicalForCompare(it.url) }.toMutableSet()
        imported.forEach { item ->
            val canonical = UrlNormalizer.canonicalForCompare(item.url) ?: return@forEach
            if (item.id in ids || canonical in urls) return@forEach
            result += item
            ids += item.id
            urls += canonical
        }
        return result.take(MAX_BOOKMARKS)
    }

    fun mergeWatches(current: List<SiteWatch>, imported: List<SiteWatch>): List<SiteWatch> {
        val result = current.toMutableList()
        val known = current.map { it.bookmarkId }.toMutableSet()
        imported.forEach { item ->
            if (item.bookmarkId in known) return@forEach
            result += item
            known += item.bookmarkId
        }
        return result.take(MAX_WATCHES)
    }

    fun mergeTrusted(current: List<TrustedSite>, imported: List<TrustedSite>): List<TrustedSite> {
        val result = current.toMutableList()
        val known = current.map { it.host + ":" + it.port }.toMutableSet()
        imported.forEach { item ->
            val key = item.host + ":" + item.port
            if (key in known) return@forEach
            result += item
            known += key
        }
        return result.take(MAX_TRUSTED_SITES)
    }

    /** Imported credentials overwrite the same key (host + port + realm). */
    fun mergeCredentials(
        current: Map<String, StoredCredential>,
        imported: Map<String, StoredCredential>,
    ): Map<String, StoredCredential> {
        if (imported.isEmpty()) return current
        val result = current.toMutableMap()
        imported.forEach { (key, value) -> result[key] = value }
        return result
    }

    /** Drops watches whose bookmark no longer exists. */
    fun pruneWatches(watches: List<SiteWatch>, bookmarks: List<Bookmark>): List<SiteWatch> {
        val ids = bookmarks.map { it.id }.toSet()
        return watches.filter { it.bookmarkId in ids }
    }
}
