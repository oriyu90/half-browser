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

    val isPersistent: Boolean get() = prefs != null

    fun snapshot(): List<VpnProfile> = _profiles.value

    fun find(id: String): VpnProfile? = _profiles.value.firstOrNull { it.id == id }

    fun add(profile: VpnProfile): VpnProfile? {
        synchronized(lock) {
            val current = _profiles.value
            if (current.size >= MAX_PROFILES) return null
            if (current.any { it.id == profile.id }) return null
            val updated = current + profile
            persist(updated)
            _profiles.value = updated
            return profile
        }
    }

    fun update(profile: VpnProfile): Boolean {
        synchronized(lock) {
            val current = _profiles.value
            if (current.none { it.id == profile.id }) return false
            val updated = current.map { if (it.id == profile.id) profile else it }
            persist(updated)
            _profiles.value = updated
            return true
        }
    }

    fun delete(id: String): Boolean {
        synchronized(lock) {
            val current = _profiles.value
            val updated = current.filterNot { it.id == id }
            if (updated.size == current.size) return false
            persist(updated)
            _profiles.value = updated
            return true
        }
    }

    /** Bulk replacement used by backup restore. */
    fun replaceAll(profiles: List<VpnProfile>) {
        synchronized(lock) {
            val bounded = profiles.distinctBy { it.id }.take(MAX_PROFILES)
            persist(bounded)
            _profiles.value = bounded
        }
    }

    /** Merge by id, keeping the newer `updatedAt`; used by backup merge. */
    fun mergeAll(profiles: List<VpnProfile>) {
        synchronized(lock) {
            val byId = _profiles.value.associateBy { it.id }.toMutableMap()
            for (incoming in profiles) {
                val existing = byId[incoming.id]
                if (existing == null || incoming.updatedAt >= existing.updatedAt) {
                    byId[incoming.id] = incoming
                }
            }
            val merged = byId.values.sortedBy { it.createdAt }.take(MAX_PROFILES)
            persist(merged)
            _profiles.value = merged
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

    private fun persist(profiles: List<VpnProfile>) {
        val encoded = runCatching {
            Codecs.json.encodeToString(VpnProfileList.serializer(), VpnProfileList(items = profiles))
        }.getOrNull() ?: return
        val store = prefs
        if (store == null) {
            memory[KEY_PROFILES] = encoded
            return
        }
        runCatching {
            // commit(), not apply(): a profile the user just saved must survive an
            // immediate process kill (the user may connect and then swipe away).
            store.edit().putString(KEY_PROFILES, encoded).commit()
        }.onFailure { Log.w(TAG, "VPN profile write failed") }
    }

    companion object {
        private const val TAG = "VpnStore"
        private const val FILE_NAME = "halfbrowser_vpn"
        private const val KEY_PROFILES = "vpn_profiles"
        private const val CURRENT_VERSION = 1
        const val MAX_PROFILES = 32
    }
}
