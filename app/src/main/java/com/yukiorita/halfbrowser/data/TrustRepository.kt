package com.yukiorita.halfbrowser.data

import android.content.Context
import com.yukiorita.halfbrowser.core.Codecs
import com.yukiorita.halfbrowser.core.TrustedSite
import com.yukiorita.halfbrowser.core.TrustPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TrustRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val _sites = MutableStateFlow(Codecs.decodeTrustedSites(prefs.getString(KEY_ITEMS, null)))
    val sites: StateFlow<List<TrustedSite>> = _sites.asStateFlow()

    fun snapshot(): List<TrustedSite> = _sites.value

    fun find(host: String, port: Int = 0): TrustedSite? =
        snapshot().firstOrNull {
            it.host.equals(host, ignoreCase = true) && (it.port == 0 || port == 0 || it.port == port)
        }

    /**
     * Pins a certificate for a host. A new certificate replaces the old pin only
     * after the user explicitly chose "always trust", which is why this method is
     * only called from that confirmation path.
     */
    fun pin(
        host: String,
        port: Int,
        fingerprintSha256: String,
        subject: String = "",
        issuer: String = "",
    ): TrustedSite {
        val normalizedHost = host.lowercase().trim()
        val now = System.currentTimeMillis()
        val site = TrustedSite(
            host = normalizedHost,
            port = port,
            fingerprintSha256 = TrustPolicy.normalizeFingerprint(fingerprintSha256),
            subject = subject.take(300),
            issuer = issuer.take(300),
            addedAt = now,
            lastSeenAt = now,
        )
        mutable { current ->
            current.filterNot { it.host == normalizedHost && it.port == port } + site
        }
        return site
    }

    fun markSeen(host: String, port: Int = 0) {
        val normalizedHost = host.lowercase().trim()
        mutable { current ->
            current.map {
                if (it.host == normalizedHost && (port == 0 || it.port == 0 || it.port == port)) {
                    it.copy(lastSeenAt = System.currentTimeMillis())
                } else {
                    it
                }
            }
        }
    }

    fun revoke(host: String, port: Int? = null) {
        val normalizedHost = host.lowercase().trim()
        mutable { current ->
            current.filterNot {
                it.host == normalizedHost && (port == null || it.port == port)
            }
        }
    }

    private fun mutable(transform: (List<TrustedSite>) -> List<TrustedSite>) {
        synchronized(lock) {
            val updated = transform(_sites.value)
            if (updated == _sites.value) return
            prefs.edit().putString(KEY_ITEMS, Codecs.encodeTrustedSites(updated)).apply()
            _sites.value = updated
        }
    }

    companion object {
        private const val PREFS_NAME = "halfbrowser_trust"
        private const val KEY_ITEMS = "trusted_sites_json"
    }
}
