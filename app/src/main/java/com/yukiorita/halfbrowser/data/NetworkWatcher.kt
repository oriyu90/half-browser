package com.yukiorita.halfbrowser.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Watches for network changes that invalidate a WireGuard session.
 *
 * Why this exists
 * ---------------
 * Switching Wi-Fi ⇄ mobile, toggling airplane mode or the server changing
 * address all change the UDP path: the endpoint may resolve differently and the
 * source address definitely does, so WireGuard has to handshake again. Without
 * a listener the app only noticed this on the next poll (or when the user
 * reopened it), which is the reported "it says connected but nothing loads".
 *
 * Design notes
 * ------------
 *  - Registered on the **application** context from
 *    [com.yukiorita.halfbrowser.HalfBrowserApplication], not from an Activity:
 *    the network can change while no screen exists, and that is exactly when a
 *    tunnel goes stale unnoticed.
 *  - **Debounced.** A single Wi-Fi→mobile switch fires several callbacks
 *    (`onLost`, `onAvailable`, `onCapabilitiesChanged`, sometimes twice). Acting
 *    on each one would tear the tunnel down repeatedly. One short delay
 *    collapses them into a single reaction.
 *  - `ACCESS_NETWORK_STATE` is already declared; this adds **no permission**.
 *  - The callback only *signals*. Deciding whether to reconnect stays in
 *    [VpnController], which owns the reachability rules.
 */
class NetworkWatcher(
    context: Context,
    private val onNetworkChanged: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false

    private val pending = Runnable { runCatching { onNetworkChanged() } }

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = schedule()

        override fun onLost(network: Network) = schedule()


        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities,
        ) {
            // Only a change of internet reachability matters; a link-local or
            // local-only update is noise. INTERNET alone is also set for a
            // captive portal, so VALIDATED is preferred when present — either
            // one is enough to justify a single debounced re-check.
            // Our own tunnel appearing/disappearing is not a network change: it
            // is the result of one. Reacting to it would re-verify right after
            // every connect and reconnect.
            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
            val hasInternet = networkCapabilities
                .hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val validated = networkCapabilities
                .hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (hasInternet || validated) schedule()
        }
    }

    /** Collapses a burst of callbacks into one reaction. */
    private fun schedule() {
        handler.removeCallbacks(pending)
        handler.postDelayed(pending, DEBOUNCE_MS)
    }

    /**
     * Registers the callback for the lifetime of the process. There is
     * deliberately no matching call in normal operation: the watcher lives as
     * long as the Application, and unregistering would only lose the events the
     * app needs. [stop] exists for tests and for a future explicit teardown.
     *
     * @return true when the platform accepted the registration
     */
    fun start(): Boolean {
        if (registered) return true
        val manager = runCatching {
            appContext.getSystemService(ConnectivityManager::class.java)
        }.getOrNull() ?: return false
        registered = runCatching {
            manager.registerDefaultNetworkCallback(callback)
            true
        }.onFailure { Log.w(TAG, "network callback unavailable: ${it.javaClass.simpleName}") }
            .getOrDefault(false)
        return registered
    }

    fun stop() {
        if (!registered) return
        registered = false
        handler.removeCallbacks(pending)
        runCatching {
            appContext.getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(callback)
        }
    }

    private companion object {
        const val TAG = "NetworkWatcher"

        /**
         * Long enough to collapse the burst of callbacks a single switch
         * produces, short enough that the user never notices the delay.
         */
        const val DEBOUNCE_MS = 1_500L
    }
}
