package com.yukiorita.halfbrowser.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.yukiorita.halfbrowser.core.Codecs
import com.yukiorita.halfbrowser.core.CredentialMeta
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class StoredCredential(
    val host: String,
    val realm: String,
    val username: String,
    val password: String,
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
 * The blob is encrypted with an AES-256-GCM key held by the Android Keystore.
 * If the Keystore is unavailable (some enterprise/older devices), the store falls
 * back to process-memory only: credentials still work for the session but are
 * never written in clear text. It is excluded from cloud backup and device
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

    fun find(host: String, realm: String): StoredCredential? {
        val key = keyOf(host, realm)
        memory[key]?.let { return it }
        val loaded = readAll()[key] ?: return null
        memory[key] = loaded
        return loaded
    }

    fun save(host: String, realm: String, username: String, password: String) {
        val normalizedHost = host.lowercase().trim()
        val entry = StoredCredential(
            host = normalizedHost,
            realm = realm.trim(),
            username = username,
            password = password,
            savedAt = System.currentTimeMillis(),
        )
        val all = readAll().toMutableMap()
        all[keyOf(normalizedHost, realm)] = entry
        memory[keyOf(normalizedHost, realm)] = entry
        writeAll(all)
    }

    fun all(): List<CredentialMeta> =
        readAll().values
            .sortedBy { it.host }
            .map {
                CredentialMeta(
                    id = keyOf(it.host, it.realm),
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

    private fun writeAll(entries: Map<String, StoredCredential>) {
        val target = prefs ?: return
        runCatching {
            val raw = Codecs.json.encodeToString(
                CredentialBundle.serializer(),
                CredentialBundle(items = entries),
            )
            target.edit().putString(KEY_CREDENTIALS, raw).apply()
        }.onFailure {
            Log.w(TAG, "Could not persist credentials")
        }
    }

    private fun keyOf(host: String, realm: String): String =
        "${host.lowercase().trim()}|${realm.trim()}"

    private companion object {
        const val TAG = "HalfBrowserCredentials"
        const val FILE_NAME = "halfbrowser_secure"
        const val KEY_CREDENTIALS = "credentials_json"
    }
}
