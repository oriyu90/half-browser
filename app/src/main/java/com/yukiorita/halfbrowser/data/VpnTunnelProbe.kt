package com.yukiorita.halfbrowser.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process

/**
 * Answers one question cheaply: **is this app's VPN tunnel still up in the OS?**
 *
 * Why this exists
 * ---------------
 * The WireGuard library keeps its tunnel registry in *process* memory
 * (`GoBackend.currentTunnel` is an instance field), so after the process is
 * recreated `getRunningTunnelNames()` is empty even when the OS still holds a
 * live VpnService. Without an independent probe the app would tear down a
 * perfectly healthy tunnel and rebuild it on every restart.
 *
 * `ConnectivityManager` is the authority here: a running VpnService publishes a
 * network with `TRANSPORT_VPN` whose `getOwnerUid()` is the owning app's UID.
 * That was verified on a device with `dumpsys connectivity` before this class
 * was written (`Transports: WIFI|VPN` + `OwnerUid: 10193` for the app's UID).
 *
 * Cost: one `getAllNetworks()` pass, no native calls, no wakeups. It runs on
 * state transitions (resume, background check, connect) and once per
 * verification pass while a tunnel is adopted, which is at most once every ten
 * seconds on screen and once a minute or less in the background.
 */
object VpnTunnelProbe {

    /** Result of the probe. */
    enum class State {
        /** Our own VPN network exists in the OS. */
        ACTIVE,

        /** No VPN network owned by this app. */
        INACTIVE,

        /**
         * The platform cannot answer: below API 30 (`getOwnerUid()` is not
         * usable there), or the connectivity service is unavailable. Callers
         * must fall back to the conservative behaviour.
         */
        UNKNOWN,
    }

    /**
     * @return whether this app currently owns a VPN network
     */
    fun probe(context: Context): State {
        // getOwnerUid() arrived in API 30 (R). On older platforms there is no
        // reliable way to attribute a VPN network to a package, so report
        // UNKNOWN instead of guessing: a wrong INACTIVE would tear down a live
        // tunnel, and a wrong ACTIVE would claim a connection that does not
        // exist. Lint enforces this guard (NewApi).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return State.UNKNOWN
        val manager = runCatching {
            context.applicationContext.getSystemService(ConnectivityManager::class.java)
        }.getOrNull() ?: return State.UNKNOWN
        return runCatching {
            val ownUid = Process.myUid()
            val networks = manager.allNetworks
            if (networks.isEmpty()) return State.INACTIVE
            for (network in networks) {
                val caps = manager.getNetworkCapabilities(network) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                // getOwnerUid() is the app that created the VPN network. It is
                // the only reliable attribution available to a third-party app.
                if (caps.ownerUid == ownUid) return State.ACTIVE
            }
            State.INACTIVE
        }.getOrElse { State.UNKNOWN }
    }
}
