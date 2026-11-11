package com.yukiorita.halfbrowser.data

import android.content.Context
import android.util.Log
import com.yukiorita.halfbrowser.core.Codecs
import com.yukiorita.halfbrowser.core.VpnHealthRecord
import com.yukiorita.halfbrowser.core.VpnReachability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Non-secret record of the last tunnel session's health.
 *
 * It answers "when did the tunnel last handshake, and what did we conclude?"
 * across process death (the WorkManager check and the next app launch read it).
 * It deliberately holds **no** keys, addresses, DNS servers or endpoints, so it
 * lives in a plain SharedPreferences file — not the Keystore-backed
 * [VpnStore] — and is excluded from backup and device transfer.
 *
 * Writes use `commit()`: the record is written right before the app may be
 * killed, and losing it would make the next launch start from "unknown".
 * Failures are logged and ignored; health history is advisory, never required
 * for the tunnel to work.
 */
class VpnHealthStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    private val _record = MutableStateFlow(read())
    val record: StateFlow<VpnHealthRecord> = _record.asStateFlow()

    fun snapshot(): VpnHealthRecord = _record.value

    fun update(transform: (VpnHealthRecord) -> VpnHealthRecord) {
        synchronized(lock) {
            val updated = transform(_record.value)
            if (updated == _record.value) return
            runCatching {
                prefs.edit().putString(KEY_RECORD, Codecs.json.encodeToString(VpnHealthRecord.serializer(), updated))
                    .commit()
            }.onFailure { Log.w(TAG, "VPN health write failed") }
            _record.value = updated
        }
    }

    /**
     * Starts a new session for [profileId].
     *
     * The previous session's handshake time is cleared: it belongs to a tunnel
     * that no longer exists, and keeping it would let the panel show a fresh
     * "last handshake" for a tunnel that has never handshaked.
     */
    fun startSession(profileId: String, connectedAt: Long) {
        update {
            it.copy(
                profileId = profileId,
                connectedAt = connectedAt,
                lastHandshakeAt = 0L,
                checkedAt = connectedAt,
                reachability = VpnReachability.VERIFYING.name,
            )
        }
    }

    fun recordCheck(lastHandshakeAt: Long, reachability: VpnReachability, checkedAt: Long) {
        update {
            it.copy(
                lastHandshakeAt = lastHandshakeAt,
                checkedAt = checkedAt,
                reachability = reachability.name,
            )
        }
    }

    fun recordAutoReconnect(at: Long) {
        update { it.copy(lastAutoReconnectAt = at) }
    }

    /**
     * Clears the session after a user-initiated disconnect. The profile id is
     * kept so the panel can still name the profile the user was using, and the
     * handshake time is kept as history (the detail sheet labels it as the last
     * session's, because [connectedAt] is 0).
     */
    fun clearSession(reachability: VpnReachability = VpnReachability.UNKNOWN) {
        update {
            it.copy(
                connectedAt = 0L,
                checkedAt = System.currentTimeMillis(),
                reachability = reachability.name,
            )
        }
    }

    private fun read(): VpnHealthRecord {
        val raw = runCatching { prefs.getString(KEY_RECORD, null) }.getOrNull() ?: return VpnHealthRecord.EMPTY
        val decoded = runCatching {
            Codecs.json.decodeFromString(VpnHealthRecord.serializer(), raw)
        }.onFailure { Log.w(TAG, "VPN health record unreadable") }.getOrNull()
            ?: return VpnHealthRecord.EMPTY
        if (decoded.version !in 1..CURRENT_VERSION) return VpnHealthRecord.EMPTY
        // Reject a corrupted reachability name instead of crashing the UI.
        val reachability = VpnReachability.entries.firstOrNull { it.name == decoded.reachability }
            ?: VpnReachability.UNKNOWN
        return decoded.copy(reachability = reachability.name, profileId = decoded.profileId.take(64))
    }

    private companion object {
        const val TAG = "VpnHealthStore"
        const val FILE_NAME = "halfbrowser_vpn_health"
        const val KEY_RECORD = "health_record"
        const val CURRENT_VERSION = 1
    }
}
