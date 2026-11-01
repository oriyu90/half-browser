package com.yukiorita.halfbrowser.data

import android.content.Context
import android.net.Uri
import com.yukiorita.halfbrowser.AppContainer
import com.yukiorita.halfbrowser.core.BackupCodec
import com.yukiorita.halfbrowser.core.BackupCrypto
import com.yukiorita.halfbrowser.core.BackupDecodeResult
import com.yukiorita.halfbrowser.core.BackupError
import com.yukiorita.halfbrowser.core.BackupFile
import com.yukiorita.halfbrowser.core.BackupRules
import com.yukiorita.halfbrowser.core.Codecs
import com.yukiorita.halfbrowser.core.CredentialBundle
import com.yukiorita.halfbrowser.core.EncryptedCredentialPayload
import com.yukiorita.halfbrowser.shortcuts.BookmarkShortcuts
import com.yukiorita.halfbrowser.work.WatchScheduler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ImportMode { REPLACE, MERGE }

data class ImportOutcome(
    val bookmarks: Int = 0,
    val watches: Int = 0,
    val trustedSites: Int = 0,
    val credentials: Int = 0,
    val credentialsSkipped: Boolean = false,
    val error: BackupError? = null,
)

/**
 * Export/import of user data through the Storage Access Framework.
 *
 * The app never asks for broad storage permission: the user picks the file, and
 * the app only touches that one document. Reading is capped at
 * [BackupCodec.MAX_BYTES] so a hostile or corrupt file cannot exhaust memory.
 */
class BackupService(
    private val context: Context,
    private val container: AppContainer,
) {

    fun suggestedFileName(now: Long = System.currentTimeMillis()): String {
        val stamp = runCatching {
            SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(now))
        }.getOrDefault("backup")
        return "half-browser-backup-$stamp.json"
    }

    /**
     * @param passphrase required (and non-null) only when [includeCredentials] is
     *   set and at least one credential exists; otherwise passwords are omitted.
     * @return the JSON document, or null when encryption was requested but failed.
     */
    fun buildBackup(
        includeCredentials: Boolean,
        passphrase: CharArray?,
        appVersion: String,
    ): String? {
        val exportedCredentials = if (includeCredentials) {
            container.credentials.exportAll()
        } else {
            emptyMap()
        }
        var payload: EncryptedCredentialPayload? = null
        if (exportedCredentials.isNotEmpty()) {
            val plaintext = runCatching {
                Codecs.json.encodeToString(
                    CredentialBundle.serializer(),
                    CredentialBundle(items = exportedCredentials),
                )
            }.getOrNull() ?: return null
            val encrypted = passphrase?.let { BackupCrypto.encrypt(plaintext, it) } ?: return null
            payload = EncryptedCredentialPayload(
                scheme = BackupCrypto.SCHEME,
                iterations = encrypted.iterations,
                salt = encrypted.salt,
                iv = encrypted.iv,
                ciphertext = encrypted.ciphertext,
            )
        }

        val bookmarks = container.bookmarks.snapshot()
        val validIds = bookmarks.map { it.id }.toSet()
        val file = BackupFile(
            exportedAt = System.currentTimeMillis(),
            appVersion = appVersion,
            settings = container.settings.current(),
            bookmarks = bookmarks,
            siteWatches = container.siteWatch.snapshot().filter { it.bookmarkId in validIds },
            watchSettings = container.siteWatch.settingsSnapshot(),
            trustedSites = container.trust.snapshot(),
            credentials = payload,
            credentialCount = exportedCredentials.size,
        )
        return BackupCodec.encode(file).takeIf { it.isNotBlank() }
    }

    fun write(uri: Uri, json: String): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
            stream.write(json.toByteArray(Charsets.UTF_8))
            stream.flush()
            true
        } ?: false
    }.getOrDefault(false)

    fun read(uri: Uri): BackupDecodeResult {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(8 * 1024)
                val out = java.io.ByteArrayOutputStream()
                var total = 0
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > BackupCodec.MAX_BYTES) {
                        return BackupDecodeResult.Failure(BackupError.TOO_LARGE)
                    }
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        }.getOrNull() ?: return BackupDecodeResult.Failure(BackupError.EMPTY)

        if (bytes.isEmpty()) return BackupDecodeResult.Failure(BackupError.EMPTY)
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
            ?: return BackupDecodeResult.Failure(BackupError.INVALID_JSON)
        return BackupCodec.decode(text, bytes.size)
    }

    fun apply(
        file: BackupFile,
        mode: ImportMode,
        passphrase: CharArray?,
    ): ImportOutcome {
        val currentBookmarks = container.bookmarks.snapshot()
        val currentWatches = container.siteWatch.snapshot()
        val currentTrusted = container.trust.snapshot()

        val bookmarks = when (mode) {
            ImportMode.REPLACE -> file.bookmarks
            ImportMode.MERGE -> BackupRules.mergeBookmarks(currentBookmarks, file.bookmarks)
        }
        val trusted = when (mode) {
            ImportMode.REPLACE -> file.trustedSites
            ImportMode.MERGE -> BackupRules.mergeTrusted(currentTrusted, file.trustedSites)
        }
        val watches = BackupRules.pruneWatches(
            when (mode) {
                ImportMode.REPLACE -> file.siteWatches
                ImportMode.MERGE -> BackupRules.mergeWatches(currentWatches, file.siteWatches)
            },
            bookmarks,
        )

        container.bookmarks.replaceAll(bookmarks)
        container.trust.replaceAll(trusted)
        container.siteWatch.replaceAll(
            watches,
            when (mode) {
                // Merging must not silently rewrite the user's checking interval.
                ImportMode.MERGE -> container.siteWatch.settingsSnapshot()
                ImportMode.REPLACE -> file.watchSettings
            },
        )
        container.settings.replace(file.settings)

        var credentialsImported = 0
        var credentialsSkipped = false
        if (file.credentials != null) {
            val decrypted = passphrase?.let { secret ->
                val payload = file.credentials
                BackupCrypto.decrypt(
                    BackupCrypto.Encrypted(
                        salt = payload.salt,
                        iv = payload.iv,
                        ciphertext = payload.ciphertext,
                        iterations = payload.iterations,
                    ),
                    secret,
                )
            }
            if (decrypted != null) {
                val imported = runCatching {
                    Codecs.json.decodeFromString(CredentialBundle.serializer(), decrypted).items
                }.getOrDefault(emptyMap())
                val merged = when (mode) {
                    ImportMode.REPLACE -> imported
                    ImportMode.MERGE -> BackupRules.mergeCredentials(
                        container.credentials.exportAll(),
                        imported,
                    )
                }
                credentialsImported = container.credentials.importAll(merged, overwrite = true)
            } else {
                credentialsSkipped = true
            }
        }

        val finalBookmarks = container.bookmarks.snapshot()
        BookmarkShortcuts.syncDynamic(context, finalBookmarks)
        container.notifyTrustChanged()
        val active = container.siteWatch.snapshot().any {
            it.enabled && it.bookmarkId in finalBookmarks.map { bookmark -> bookmark.id }
        }
        WatchScheduler.sync(context, container.siteWatch.settingsSnapshot(), active)

        return ImportOutcome(
            bookmarks = finalBookmarks.size,
            watches = container.siteWatch.snapshot().count { it.enabled },
            trustedSites = trusted.size,
            credentials = credentialsImported,
            credentialsSkipped = credentialsSkipped,
        )
    }
}
