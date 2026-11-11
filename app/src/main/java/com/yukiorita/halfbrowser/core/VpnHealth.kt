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

    /**
     * The OS says our tunnel is still up, but this process cannot read its
     * handshake time (the WireGuard library keeps its tunnel registry in
     * process memory, so after a process restart there is nothing to query).
     *
     * This is deliberately distinct from [HEALTHY]: the tunnel exists, but
     * nothing has proven that traffic flows. The UI says "connected, not yet
     * verified" rather than claiming a working connection.
     *
     * Appended last so that existing name-based persistence
     * ([VpnHealthRecord.reachability]) keeps decoding old values.
     */
    TUNNEL_ONLY,
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

    /**
     * How often the controller re-reads the handshake time while the app is on
     * screen. The healthy window is [HEALTHY_WINDOW_MS], so this is already
     * finer than the judgement needs; it exists so the badge reacts quickly.
     */
    const val POLL_FOREGROUND_MS = 10_000L

    /**
     * First interval used while the app is not visible (screen off or another
     * app in front). A healthy tunnel backs off from here towards
     * [POLL_BACKGROUND_MAX_MS]; a broken one keeps polling at this rate.
     */
    const val POLL_BACKGROUND_MIN_MS = 60_000L

    /** Upper bound of the background back-off. */
    const val POLL_BACKGROUND_MAX_MS = 300_000L

    /** Multiplier applied to the background interval per consecutive healthy check. */
    const val POLL_BACKOFF_FACTOR = 1.5

    /**
     * Grace period used right after the network changed. A handshake cannot
     * complete before the new path exists, so the normal 45 s grace would
     * report a false failure and trigger a pointless reconnect.
     */
    const val NETWORK_CHANGE_GRACE_MS = 10_000L

    /** Minimum gap between two automatic reconnects (prevents reconnect storms). */
    const val AUTO_RECONNECT_MIN_INTERVAL_MS = 30_000L

    /**
     * Classifies a session from its snapshot.
     *
     * @param now current epoch millis
     * @return the reachability, never [VpnReachability.UNKNOWN] for an UP tunnel
     */
    /**
     * @param graceStartOverride when the current session effectively restarted
     *   for grace purposes (e.g. the moment the network changed). It only moves
     *   the *grace* window forward; whether a handshake belongs to this session
     *   is still judged against [VpnHealthSnapshot.connectedAt], because a
     *   handshake completed after the connect is valid proof even if it
     *   happened before the network changed.
     */
    fun classify(
        snapshot: VpnHealthSnapshot,
        now: Long,
        graceStartOverride: Long = 0L,
    ): VpnReachability {
        val connectedAt = snapshot.connectedAt
        // Grace is measured from the later of the connect and the override, so a
        // network switch gets a fresh window instead of inheriting an expired one.
        val graceStart = maxOf(connectedAt, graceStartOverride)
        val graceAge = if (graceStart > 0L) (now - graceStart).coerceAtLeast(0L) else Long.MAX_VALUE
        if (snapshot.hasHandshake()) {
            val handshakeAge = (now - snapshot.lastHandshakeAt).coerceAtLeast(0L)
            // A handshake that predates this connect (a leftover from the
            // previous session) proves nothing about the current one until a
            // new one arrives; inside the grace period that is "verifying".
            if (snapshot.lastHandshakeAt < connectedAt) {
                return if (graceAge < HANDSHAKE_GRACE_MS) VpnReachability.VERIFYING else VpnReachability.STALE
            }
            // A fresh handshake since connect is authoritative even right after
            // UP: the server is demonstrably reachable.
            if (handshakeAge <= HEALTHY_WINDOW_MS) return VpnReachability.HEALTHY
            return VpnReachability.STALE
        }
        // No handshake at all: stay "verifying" inside the grace period (a slow
        // mobile network can take a few seconds), then report unreachable.
        return if (graceAge < HANDSHAKE_GRACE_MS) VpnReachability.VERIFYING else VpnReachability.UNREACHABLE
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
     * Decides what to publish after a verification pass.
     *
     * A tunnel adopted from the OS ([VpnReachability.TUNNEL_ONLY]) has no
     * readable handshake in this process, so every pass observes
     * [VpnReachability.UNKNOWN]. Publishing that would erase the only true fact
     * we have ("the tunnel exists") and, worse, UNKNOWN is not a reconnect
     * trigger, so the app would stop verifying entirely. Keep TUNNEL_ONLY until
     * something more specific is observed.
     *
     * @param observed what this pass measured
     * @param previous what was published before
     */
    fun publish(observed: VpnReachability, previous: VpnReachability): VpnReachability =
        if (observed == VpnReachability.UNKNOWN && previous == VpnReachability.TUNNEL_ONLY) {
            VpnReachability.TUNNEL_ONLY
        } else {
            observed
        }

    /**
     * How long to wait before the next verification pass.
     *
     * The cost driver is [com.wireguard.android.backend.Backend.getStatistics]:
     * every call performs a native `wgGetConfig`, stringifies the whole config
     * and splits it, so it must not run every few seconds while nobody is
     * looking. The judgement only needs [HEALTHY_WINDOW_MS] resolution, which is
     * why the background schedule backs off instead of polling flat out.
     *
     * @param foreground the app is visible to the user
     * @param reachability the last observed reachability
     * @param consecutiveHealthy how many checks in a row were [VpnReachability.HEALTHY]
     *   (0 = not proven healthy yet, which keeps the fast background rate;
     *   1 = one healthy check, which already applies the first back-off step)
     * @return milliseconds to wait before the next check
     */
    fun nextPollDelayMs(
        foreground: Boolean,
        reachability: VpnReachability,
        consecutiveHealthy: Int,
    ): Long {
        if (foreground) return POLL_FOREGROUND_MS
        // A tunnel that is not proven healthy is re-checked at the fast
        // background rate: this is the case where recovery matters.
        if (reachability != VpnReachability.HEALTHY || consecutiveHealthy <= 0) {
            return POLL_BACKGROUND_MIN_MS
        }
        var delay = POLL_BACKGROUND_MIN_MS.toDouble()
        repeat(consecutiveHealthy.coerceAtMost(MAX_BACKOFF_STEPS)) {
            delay *= POLL_BACKOFF_FACTOR
            if (delay >= POLL_BACKGROUND_MAX_MS) return POLL_BACKGROUND_MAX_MS
        }
        return delay.toLong().coerceIn(POLL_BACKGROUND_MIN_MS, POLL_BACKGROUND_MAX_MS)
    }

    /**
     * Caps the exponent so the computation stays bounded for a long session.
     * Shared with the controller, which caps its healthy counter the same way.
     */
    const val MAX_BACKOFF_STEPS = 16

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
