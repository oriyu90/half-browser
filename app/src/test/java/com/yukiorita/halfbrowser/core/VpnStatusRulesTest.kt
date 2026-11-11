package com.yukiorita.halfbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnStatusRulesTest {

    @Test
    fun `headline is off when the tunnel is down`() {
        assertEquals(
            VpnStatusHeadline.OFF,
            VpnStatusRules.headline(tunnelUp = false, reachability = VpnReachability.UNKNOWN),
        )
    }

    @Test
    fun `headline only turns connected on a healthy handshake`() {
        assertEquals(
            VpnStatusHeadline.CONNECTED,
            VpnStatusRules.headline(tunnelUp = true, reachability = VpnReachability.HEALTHY),
        )
        assertEquals(
            VpnStatusHeadline.VERIFYING,
            VpnStatusRules.headline(tunnelUp = true, reachability = VpnReachability.VERIFYING),
        )
        assertEquals(
            VpnStatusHeadline.VERIFYING,
            VpnStatusRules.headline(tunnelUp = true, reachability = VpnReachability.UNKNOWN),
        )
    }

    @Test
    fun `degraded for stale or unreachable`() {
        assertEquals(
            VpnStatusHeadline.DEGRADED,
            VpnStatusRules.headline(tunnelUp = true, reachability = VpnReachability.STALE),
        )
        assertEquals(
            VpnStatusHeadline.DEGRADED,
            VpnStatusRules.headline(tunnelUp = true, reachability = VpnReachability.UNREACHABLE),
        )
        assertTrue(VpnStatusRules.isWarning(VpnStatusHeadline.DEGRADED))
        assertFalse(VpnStatusRules.isWarning(VpnStatusHeadline.CONNECTED))
    }

    @Test
    fun `transition states win over reachability`() {
        assertEquals(
            VpnStatusHeadline.CONNECTING,
            VpnStatusRules.headline(true, VpnReachability.HEALTHY, connecting = true),
        )
        assertEquals(
            VpnStatusHeadline.DISCONNECTING,
            VpnStatusRules.headline(true, VpnReachability.HEALTHY, disconnecting = true),
        )
        assertEquals(
            VpnStatusHeadline.FAILED,
            VpnStatusRules.headline(false, VpnReachability.UNKNOWN, failed = true),
        )
        assertEquals(
            VpnStatusHeadline.RECONNECTING,
            VpnStatusRules.headline(true, VpnReachability.STALE, autoReconnecting = true),
        )
    }

    @Test
    fun `auto recovering only when enabled, consented and broken`() {
        assertTrue(
            VpnStatusRules.isAutoRecovering(VpnReachability.STALE, autoReconnect = true, consentGranted = true),
        )
        assertTrue(
            VpnStatusRules.isAutoRecovering(VpnReachability.UNREACHABLE, autoReconnect = true, consentGranted = true),
        )
        // A healthy tunnel is not "recovering".
        assertFalse(
            VpnStatusRules.isAutoRecovering(VpnReachability.HEALTHY, autoReconnect = true, consentGranted = true),
        )
        // Neither is one the user asked us to leave alone, or one we cannot fix.
        assertFalse(
            VpnStatusRules.isAutoRecovering(VpnReachability.STALE, autoReconnect = false, consentGranted = true),
        )
        assertFalse(
            VpnStatusRules.isAutoRecovering(VpnReachability.STALE, autoReconnect = true, consentGranted = false),
        )
    }

    @Test
    fun `age text buckets`() {
        assertEquals(VpnAge.JUST_NOW, VpnStatusRules.ageText(0))
        assertEquals(VpnAge.JUST_NOW, VpnStatusRules.ageText(59_000))
        assertEquals(VpnAge.MINUTES(1), VpnStatusRules.ageText(60_000))
        assertEquals(VpnAge.MINUTES(59), VpnStatusRules.ageText(3_599_000))
        assertEquals(VpnAge.HOURS(1), VpnStatusRules.ageText(3_600_000))
        assertEquals(VpnAge.HOURS(23), VpnStatusRules.ageText(86_399_000))
        assertEquals(VpnAge.DAYS(1), VpnStatusRules.ageText(86_400_000))
        assertNull(VpnStatusRules.ageText(-1))
    }
}
