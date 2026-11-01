package com.yukiorita.halfbrowser.data

import android.content.Context
import com.yukiorita.halfbrowser.core.Codecs
import com.yukiorita.halfbrowser.core.SitePermissionGrant
import com.yukiorita.halfbrowser.core.SitePermissionKind
import com.yukiorita.halfbrowser.core.SitePermissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Remembers per-origin permission decisions ("allow once and remember").
 *
 * Absence of a row deliberately means "ask again": the browser never upgrades a
 * site to a permanent grant on its own, and every grant is revocable from the
 * settings screen.
 */
class SitePermissionRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val _grants = MutableStateFlow(
        Codecs.decodeSitePermissions(prefs.getString(KEY_ITEMS, null)),
    )
    val grants: StateFlow<List<SitePermissionGrant>> = _grants.asStateFlow()

    fun snapshot(): List<SitePermissionGrant> = _grants.value

    /** True/false when the user already decided for this origin, null when unset. */
    fun decision(origin: String?, kind: SitePermissionKind): Boolean? {
        val normalized = SitePermissions.originOf(origin) ?: return null
        return snapshot()
            .firstOrNull { it.origin == normalized && it.kind == kind.key }
            ?.allowed
    }

    fun record(origin: String, kind: SitePermissionKind, allowed: Boolean) {
        val normalized = SitePermissions.originOf(origin) ?: return
        val now = System.currentTimeMillis()
        mutable { current ->
            current.filterNot { it.origin == normalized && it.kind == kind.key } +
                SitePermissionGrant(
                    origin = normalized,
                    kind = kind.key,
                    allowed = allowed,
                    updatedAt = now,
                )
        }
    }

    fun forget(origin: String) {
        val normalized = SitePermissions.originOf(origin) ?: return
        mutable { current -> current.filterNot { it.origin == normalized } }
    }

    fun forget(origin: String, kind: SitePermissionKind) {
        val normalized = SitePermissions.originOf(origin) ?: return
        mutable { current ->
            current.filterNot { it.origin == normalized && it.kind == kind.key }
        }
    }

    fun clear() = mutable { emptyList() }

    /** Origins that currently have at least one decision, newest first. */
    fun sites(): List<SitePermissionOrigin> =
        snapshot()
            .groupBy { it.origin }
            .map { (origin, rows) ->
                SitePermissionOrigin(
                    origin = origin,
                    decisions = SitePermissionKind.entries.mapNotNull { kind ->
                        rows.firstOrNull { it.kind == kind.key }?.let { kind to it.allowed }
                    }.toMap(),
                    updatedAt = rows.maxOf { it.updatedAt },
                )
            }
            .sortedByDescending { it.updatedAt }

    private fun mutable(transform: (List<SitePermissionGrant>) -> List<SitePermissionGrant>) {
        synchronized(lock) {
            val updated = transform(_grants.value)
            prefs.edit()
                .putString(KEY_ITEMS, Codecs.encodeSitePermissions(updated))
                .apply()
            _grants.value = updated
        }
    }

    companion object {
        private const val PREFS_NAME = "halfbrowser_site_permissions"
        private const val KEY_ITEMS = "grants_json"
    }
}

/** One origin with its decided permissions, used by the settings list. */
data class SitePermissionOrigin(
    val origin: String,
    val decisions: Map<SitePermissionKind, Boolean>,
    val updatedAt: Long,
)
