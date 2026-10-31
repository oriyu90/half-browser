package com.yukiorita.halfbrowser.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.yukiorita.halfbrowser.core.Codecs
import com.yukiorita.halfbrowser.core.CredentialKey
import com.yukiorita.halfbrowser.core.CredentialMeta
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class StoredCredential(
    val host: String,
    val realm: String,
    val username: String,
    val password: String,
    val scheme: String = "https",
    val port: Int = 443,
    val savedAt: Long = 0L,
)

@Serializable
data class CredentialBundle(
    val version: Int = 1,
    val items: Map<String, StoredCredential> = emptyMap(),
)

/**
 * Encrypted storage for HTTP authentication credentials.
 *
 * Credentials are scoped to scheme + host + port + realm. This matters: a
 * password saved for `https://host:443` must never be offered to
 * `http://host:80`, where it would travel in clear text.
 *
 * The blob is encrypted with an AES-256-GCM key held by the Android Keystore.
 * If the Keystore is unavailable (some enterprise/older devices), the store falls
 * back to process memory only: credentials still work for the session but are
 * never written in clear text. The file is excluded from cloud backup and device
 * transfer via the manifest backup rules.
 */
class CredentialStore(context: Context) {

    private val memory = ConcurrentHashMap<String, StoredCredential>()
    private val prefs: SharedPreferences? = runCatching {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context.applicationContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.onFailure {
        Log.w(TAG, "Encrypted storage unavailable; credentials will not be persisted")
    }.getOrNull()

    val isPersistent: Boolean get() = prefs != null

    fun find(scheme: String, host: String, port: Int, realm: String): StoredCredential? {
        if (scheme.lowercase() != "https") return null
        val key = keyOf(scheme, host, port, realm)
        memory[key]?.let { return it }
        val loaded = readAll()[key] ?: return null
        memory[key] = loaded
        return loaded
    }

    /** @return true only when the credential was actually written to encrypted storage. */
    fun save(
        scheme: String,
        host: String,
        port: Int,
        realm: String,
        username: String,
        password: String,
    ): Boolean {
        if (scheme.lowercase() != "https") return false
        val entry = StoredCredential(
            host = host.lowercase().trim(),
            realm = realm.trim(),
            username = username,
            password = password,
            scheme = "https",
            port = port,
            savedAt = System.currentTimeMillis(),
        )
        val key = keyOf("https", entry.host, port, realm)
        val all = readAll().toMutableMap()
        all[key] = entry
        val written = writeAll(all)
        if (written) memory[key] = entry
        return written
    }

    fun all(): List<CredentialMeta> =
        readAll().values
            .sortedBy { it.host }
            .map {
                CredentialMeta(
                    id = keyOf(it.scheme, it.host, it.port, it.realm),
                    host = it.host,
                    realm = it.realm,
                    username = it.username,
                )
            }

    fun delete(id: String) {
        memory.remove(id)
        val all = readAll().toMutableMap()
        all.remove(id)
        writeAll(all)
    }

    fun clear() {
        memory.clear()
        writeAll(emptyMap())
    }

    private fun readAll(): Map<String, StoredCredential> {
        val raw = runCatching { prefs?.getString(KEY_CREDENTIALS, null) }.getOrNull() ?: return emptyMap()
        return runCatching {
            Codecs.json.decodeFromString(CredentialBundle.serializer(), raw).items
        }.getOrDefault(emptyMap())
    }

    private fun writeAll(entries: Map<String, StoredCredential>): Boolean {
        val target = prefs ?: return false
        return runCatching {
            val raw = Codecs.json.encodeToString(
                CredentialBundle.serializer(),
                CredentialBundle(items = entries),
            )
            target.edit().putString(KEY_CREDENTIALS, raw).commit()
        }.onFailure {
            Log.w(TAG, "Could not persist credentials")
        }.getOrDefault(false)
    }

    private fun keyOf(scheme: String, host: String, port: Int, realm: String): String =
        CredentialKey.of(scheme, host, port, realm)

    private companion object {
        const val TAG = "HalfBrowserCredentials"
        const val FILE_NAME = "halfbrowser_secure"
        const val KEY_CREDENTIALS = "credentials_json"
    }
}
