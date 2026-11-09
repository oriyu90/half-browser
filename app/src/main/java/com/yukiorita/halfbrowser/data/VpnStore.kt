package com.yukiorita.halfbrowser.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.yukiorita.halfbrowser.core.Codecs
import com.yukiorita.halfbrowser.core.VpnProfile
import com.yukiorita.halfbrowser.core.VpnProfileList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Encrypted storage for WireGuard profiles.
 *
 * A profile contains the interface **private key** and possibly a pre-shared
 * key, so it is treated exactly like a password: AES-256-GCM through the Android
 * Keystore, excluded from cloud backup and device transfer, never logged.
 *
 * If the Keystore is unavailable the store degrades to process memory only —
 * profiles still work for the session but are not written in clear text. That
 * mirrors [CredentialStore] and keeps the "never persist a secret in the clear"
 * rule absolute.
 *
 * Write failures: [persist] uses `commit()`, so a profile the user just saved
 * survives an immediate process kill. When a write fails the in-memory
 * [profiles] list is *not* advanced (no memory/persistence divergence) and
 * [lastWriteFailed] is set to true. The UI should observe [lastWriteFailed]
 * (or check the return value of add/update/delete, which is false on failure)
 * and tell the user the profile was not saved.
 */
class VpnStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences? = runCatching {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.onFailure {
        Log.w(TAG, "Encrypted storage unavailable; VPN profiles will not be persisted")
    }.getOrNull()

    private val memory = mutableMapOf<String, String>()
    private val lock = Any()

    private val _profiles = MutableStateFlow(load())
    val profiles: StateFlow<List<VpnProfile>> = _profiles.asStateFlow()

    /**
     * True when the most recent profile write failed (or the Keystore-backed
     * store is unavailable for a write). Cleared on the next successful write.
     */
    private val _lastWriteFailed = MutableStateFlow(false)
    val lastWriteFailed: StateFlow<Boolean> = _lastWriteFailed.asStateFlow()

    val isPersistent: Boolean get() = prefs != null

    fun snapshot(): List<VpnProfile> = _profiles.value

    fun find(id: String): VpnProfile? = _profiles.value.firstOrNull { it.id == id }

    fun add(profile: VpnProfile): VpnProfile? {
        synchronized(lock) {
            val current = _profiles.value
            if (current.size >= MAX_PROFILES) return null
            if (current.any { it.id == profile.id }) return null
            val updated = current + profile
            if (!persist(updated)) return null
            _profiles.value = updated
            return profile
        }
    }

    fun update(profile: VpnProfile): Boolean {
        synchronized(lock) {
            val current = _profiles.value
            if (current.none { it.id == profile.id }) return false
            val updated = current.map { if (it.id == profile.id) profile else it }
            if (!persist(updated)) return false
            _profiles.value = updated
            return true
        }
    }

    fun delete(id: String): Boolean {
        synchronized(lock) {
            val current = _profiles.value
            val updated = current.filterNot { it.id == id }
            if (updated.size == current.size) return false
            if (!persist(updated)) return false
            _profiles.value = updated
            return true
        }
    }

    private fun load(): List<VpnProfile> {
        val raw = readRaw() ?: return emptyList()
        val decoded = runCatching {
            Codecs.json.decodeFromString(VpnProfileList.serializer(), raw)
        }.getOrNull() ?: return emptyList()
        if (decoded.version !in 1..CURRENT_VERSION) return emptyList()
        return decoded.items
            .distinctBy { it.id }
            .filter { it.id.isNotBlank() && it.configText.isNotBlank() }
            .take(MAX_PROFILES)
    }

    private fun readRaw(): String? {
        val store = prefs ?: return synchronized(lock) { memory[KEY_PROFILES] }
        return runCatching { store.getString(KEY_PROFILES, null) }
            .onFailure { Log.w(TAG, "VPN profile read failed") }
            .getOrNull()
    }

    /**
     * Writes [profiles] to disk synchronously. Returns true on success (or when
     * running memory-only, where there is no backing store to fail against).
     * On failure [lastWriteFailed] is set and false is returned so callers must
     * NOT advance the in-memory state.
     */
    private fun persist(profiles: List<VpnProfile>): Boolean {
        val encoded = runCatching {
            Codecs.json.encodeToString(VpnProfileList.serializer(), VpnProfileList(items = profiles))
        }.getOrNull()
        if (encoded == null) {
            _lastWriteFailed.value = true
            return false
        }
        val store = prefs
        if (store == null) {
            memory[KEY_PROFILES] = encoded
            _lastWriteFailed.value = false
            return true
        }
        val ok = runCatching {
            // commit(), not apply(): a profile the user just saved must survive an
            // immediate process kill (the user may connect and then swipe away).
            store.edit().putString(KEY_PROFILES, encoded).commit()
        }.onFailure { Log.w(TAG, "VPN profile write failed") }.getOrDefault(false)
        if (!ok) {
            _lastWriteFailed.value = true
            Log.w(TAG, "VPN profile write failed (commit returned false)")
        } else {
            _lastWriteFailed.value = false
        }
        return ok
    }

    companion object {
        private const val TAG = "VpnStore"
        private const val FILE_NAME = "halfbrowser_vpn"
        private const val KEY_PROFILES = "vpn_profiles"
        private const val CURRENT_VERSION = 1
        const val MAX_PROFILES = 32
    }
}
