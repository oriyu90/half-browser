package com.yukiorita.halfbrowser.ui.components

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Helpers for the Android "default browser" role.
 *
 * A normal app cannot force itself to receive external links: the user must pick
 * it in the system UI. This object only reports availability/status and builds
 * the request intent, so callers never crash on devices without the role.
 */
object DefaultBrowser {

    fun isSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            val manager = context.getSystemService(RoleManager::class.java) ?: return false
            manager.isRoleAvailable(RoleManager.ROLE_BROWSER)
        }.getOrDefault(false)
    }

    fun isDefault(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            val manager = context.getSystemService(RoleManager::class.java) ?: return false
            manager.isRoleHeld(RoleManager.ROLE_BROWSER)
        }.getOrDefault(false)
    }

    fun requestIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val manager = context.getSystemService(RoleManager::class.java) ?: return null
            if (!manager.isRoleAvailable(RoleManager.ROLE_BROWSER)) return null
            manager.createRequestRoleIntent(RoleManager.ROLE_BROWSER)
        }.getOrNull()
    }

    /** Fallback when the browser role is unavailable (older devices / some OEMs). */
    fun settingsIntent(): Intent? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            null
        }
    }.getOrNull()
}
