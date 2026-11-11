package com.yukiorita.halfbrowser.core

/**
 * Which headline the VPN status badge shows.
 *
 * The badge has to answer one question at a glance: "is my traffic going
 * through the VPN right now?". Tunnel state alone cannot answer it (a tunnel
 * can be up with no handshake), so the headline is derived from both the
 * tunnel state and the reachability.
 */
enum class VpnStatusHeadline {
    OFF,
    CONNECTING,
    DISCONNECTING,
    CONNECTED,
    VERIFYING,
    DEGRADED,
    RECONNECTING,
    FAILED,
}

object VpnStatusRules {

    /**
     * @param tunnelUp true for `VpnState.Connected`
     * @param reachability the last observed reachability
     * @param autoReconnecting true when the tunnel is up but the last automatic
     *   reconnect failed, i.e. the app is about to try again
     * @param connecting an explicit connect is in flight
     * @param disconnecting an explicit disconnect is in flight
     * @param failed the last connect attempt failed
     */
    fun headline(
        tunnelUp: Boolean,
        reachability: VpnReachability,
        autoReconnecting: Boolean = false,
        connecting: Boolean = false,
        disconnecting: Boolean = false,
        failed: Boolean = false,
    ): VpnStatusHeadline = when {
        failed -> VpnStatusHeadline.FAILED
        connecting -> VpnStatusHeadline.CONNECTING
        disconnecting -> VpnStatusHeadline.DISCONNECTING
        !tunnelUp -> VpnStatusHeadline.OFF
        autoReconnecting -> VpnStatusHeadline.RECONNECTING
        reachability == VpnReachability.HEALTHY -> VpnStatusHeadline.CONNECTED
        reachability == VpnReachability.STALE || reachability == VpnReachability.UNREACHABLE ->
            VpnStatusHeadline.DEGRADED
        // The OS says the tunnel is up but this process cannot read its
        // handshake: say "checking", never "connected".
        reachability == VpnReachability.TUNNEL_ONLY -> VpnStatusHeadline.VERIFYING
        else -> VpnStatusHeadline.VERIFYING
    }

    /**
     * True when the tunnel is up, traffic is not flowing, and the user asked
     * for automatic recovery: the badge then says "reconnecting" instead of
     * "not responding", because the app is actively working on it.
     *
     * @param autoReconnect the user setting
     * @param consentGranted `VpnService.prepare()` returned null
     */
    fun isAutoRecovering(
        reachability: VpnReachability,
        autoReconnect: Boolean,
        consentGranted: Boolean,
    ): Boolean = autoReconnect && consentGranted && VpnHealthRules.needsReconnect(reachability)

    /** True for headlines where the tunnel is up but traffic may not flow. */
    fun isWarning(headline: VpnStatusHeadline): Boolean =
        headline == VpnStatusHeadline.DEGRADED || headline == VpnStatusHeadline.FAILED

    /**
     * Human-readable age of a timestamp ("just now", "3 min ago", "2 h ago").
     * Returns null when the timestamp is unknown (0) or in the future by more
     * than a minute (a clock change), so the UI can omit the line.
     */
    fun ageText(ageMs: Long): VpnAge? {
        if (ageMs < 0L) return null
        val seconds = ageMs / 1000L
        return when {
            seconds < 60L -> VpnAge.JUST_NOW
            seconds < 3_600L -> VpnAge.MINUTES((seconds / 60L).toInt())
            seconds < 86_400L -> VpnAge.HOURS((seconds / 3_600L).toInt())
            else -> VpnAge.DAYS((seconds / 86_400L).toInt())
        }
    }
}

/**
 * A localized age; the UI maps each case to a string resource so the wording
 * stays in the translation files.
 */
sealed interface VpnAge {
    data object JUST_NOW : VpnAge
    data class MINUTES(val count: Int) : VpnAge
    data class HOURS(val count: Int) : VpnAge
    data class DAYS(val count: Int) : VpnAge
}
