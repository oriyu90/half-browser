package com.yukiorita.halfbrowser.core

import kotlinx.serialization.Serializable

/**
 * Reachability of the WireGuard session, as opposed to the state of the tunnel
 * device.
 *
 * Why this exists
 * ---------------
 * `VpnState.Connected` only says that the TUN device exists and routes are in
 * place. The WireGuard handshake is asynchronous: if the server is down, the
 * UDP port is blocked, or the NAT mapping expired while the device slept, the
 * tunnel stays "up" while nothing actually flows. The app therefore derives a
 * second, independent signal from the peers' latest handshake time and shows
 * both.
 *
 * All the rules live here as pure functions so they are unit-tested without the
 * native library or an Android device.
 */
enum class VpnReachability {
    /** No tunnel, or the tunnel was never verified. */
    UNKNOWN,

    /** A verification is in progress (just after connect, or on resume). */
    VERIFYING,

    /** A handshake completed recently: traffic can flow. */
    HEALTHY,

    /** A handshake succeeded before, but it is older than [VpnHealthRules.HEALTHY_WINDOW_MS]. */
    STALE,

    /** No handshake has ever completed and the grace period is over. */
    UNREACHABLE,
}

/**
 * A recorded observation of one tunnel session.
 *
 * @param connectedAt epoch millis when the tunnel reported UP (0 = unknown)
 * @param lastHandshakeAt epoch millis of the newest peer handshake, 0 = never
 * @param checkedAt epoch millis when [lastHandshakeAt] was read
 */
data class VpnHealthSnapshot(
    val profileId: String,
    val connectedAt: Long = 0L,
    val lastHandshakeAt: Long = 0L,
    val checkedAt: Long = 0L,
) {
    fun hasHandshake(): Boolean = lastHandshakeAt > 0L

    /** True when [lastHandshakeAt] is at or after the tunnel came up. */
    fun handshakeSinceConnect(): Boolean = hasHandshake() && lastHandshakeAt >= connectedAt
}

object VpnHealthRules {
    /**
     * A handshake is considered fresh for this long. WireGuard re-keys every
     * ~2 minutes, so a healthy idle tunnel still produces handshakes well
     * inside this window.
     */
    const val HEALTHY_WINDOW_MS = 180_000L

    /** Grace period after UP before "no handshake yet" becomes a failure. */
    const val HANDSHAKE_GRACE_MS = 45_000L

    /** How often the foreground controller re-reads the handshake time. */
    const val POLL_INTERVAL_MS = 5_000L

    /** Minimum gap between two automatic reconnects (prevents reconnect storms). */
    const val AUTO_RECONNECT_MIN_INTERVAL_MS = 30_000L

    /**
     * Classifies a session from its snapshot.
     *
     * @param now current epoch millis
     * @return the reachability, never [VpnReachability.UNKNOWN] for an UP tunnel
     */
    fun classify(snapshot: VpnHealthSnapshot, now: Long): VpnReachability {
        val connectedAt = snapshot.connectedAt
        val age = if (connectedAt > 0L) (now - connectedAt).coerceAtLeast(0L) else Long.MAX_VALUE
        if (snapshot.hasHandshake()) {
            val handshakeAge = (now - snapshot.lastHandshakeAt).coerceAtLeast(0L)
            // A handshake that predates this connect (a leftover from the
            // previous session) proves nothing about the current one until a
            // new one arrives; inside the grace period that is "verifying".
            if (snapshot.lastHandshakeAt < connectedAt) {
                return if (age < HANDSHAKE_GRACE_MS) VpnReachability.VERIFYING else VpnReachability.STALE
            }
            // A fresh handshake since connect is authoritative even right after
            // UP: the server is demonstrably reachable.
            if (handshakeAge <= HEALTHY_WINDOW_MS) return VpnReachability.HEALTHY
            return VpnReachability.STALE
        }
        // No handshake at all: stay "verifying" inside the grace period (a slow
        // mobile network can take a few seconds), then report unreachable.
        return if (age < HANDSHAKE_GRACE_MS) VpnReachability.VERIFYING else VpnReachability.UNREACHABLE
    }

    /** True when the tunnel is up but traffic is (probably) not flowing. */
    fun needsReconnect(reachability: VpnReachability): Boolean =
        reachability == VpnReachability.STALE || reachability == VpnReachability.UNREACHABLE

    /**
     * Decides whether an automatic reconnect should run now.
     *
     * @param reachability current reachability of the live tunnel
     * @param autoReconnect user setting
     * @param consentGranted `VpnService.prepare()` returned null
     * @param lastAutoReconnectAt epoch millis of the previous automatic reconnect
     * @param now current epoch millis
     */
    fun shouldAutoReconnect(
        reachability: VpnReachability,
        autoReconnect: Boolean,
        consentGranted: Boolean,
        lastAutoReconnectAt: Long,
        now: Long,
    ): Boolean {
        if (!autoReconnect || !consentGranted) return false
        if (!needsReconnect(reachability)) return false
        if (lastAutoReconnectAt > 0L && now - lastAutoReconnectAt < AUTO_RECONNECT_MIN_INTERVAL_MS) {
            return false
        }
        return true
    }

    /**
     * Chooses the newest handshake time across peers. The WireGuard library
     * reports one entry per peer; a multi-peer profile is reachable when any
     * peer has handshaked, so the maximum is the right aggregate.
     */
    fun newestHandshake(times: Iterable<Long>): Long =
        times.asSequence().filter { it > 0L }.maxOrNull() ?: 0L
}

/**
 * Persisted, non-secret health history used to answer "was the VPN working
 * when the app was last open?".
 *
 * Deliberately contains no keys, addresses, DNS servers or endpoints: it is a
 * plain SharedPreferences record so it can survive a process death without
 * touching the Keystore, and it is excluded from backup.
 */
@Serializable
data class VpnHealthRecord(
    val version: Int = 1,
    val profileId: String = "",
    val connectedAt: Long = 0L,
    val lastHandshakeAt: Long = 0L,
    val checkedAt: Long = 0L,
    val reachability: String = VpnReachability.UNKNOWN.name,
    val lastAutoReconnectAt: Long = 0L,
) {
    fun toSnapshot(): VpnHealthSnapshot = VpnHealthSnapshot(
        profileId = profileId,
        connectedAt = connectedAt,
        lastHandshakeAt = lastHandshakeAt,
        checkedAt = checkedAt,
    )

    companion object {
        val EMPTY = VpnHealthRecord()
    }
}
