package com.yukiorita.halfbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnHealthTest {

    private val t0 = 1_700_000_000_000L

    private fun snapshot(connectedAt: Long, handshakeAt: Long) =
        VpnHealthSnapshot(profileId = "p", connectedAt = connectedAt, lastHandshakeAt = handshakeAt, checkedAt = t0)

    @Test
    fun `no handshake right after connect is verifying`() {
        val s = snapshot(connectedAt = t0, handshakeAt = 0L)
        assertEquals(VpnReachability.VERIFYING, VpnHealthRules.classify(s, t0 + 1_000))
        assertEquals(VpnReachability.VERIFYING, VpnHealthRules.classify(s, t0 + 44_000))
    }

    @Test
    fun `no handshake after the grace period is unreachable`() {
        val s = snapshot(connectedAt = t0, handshakeAt = 0L)
        assertEquals(VpnReachability.UNREACHABLE, VpnHealthRules.classify(s, t0 + 45_000))
        assertEquals(VpnReachability.UNREACHABLE, VpnHealthRules.classify(s, t0 + 600_000))
    }

    @Test
    fun `fresh handshake is healthy`() {
        val s = snapshot(connectedAt = t0, handshakeAt = t0 + 2_000)
        assertEquals(VpnReachability.HEALTHY, VpnHealthRules.classify(s, t0 + 5_000))
        assertEquals(VpnReachability.HEALTHY, VpnHealthRules.classify(s, t0 + 2_000 + 180_000))
    }

    @Test
    fun `old handshake is stale`() {
        val s = snapshot(connectedAt = t0, handshakeAt = t0 + 2_000)
        assertEquals(VpnReachability.STALE, VpnHealthRules.classify(s, t0 + 2_000 + 180_001))
        assertEquals(VpnReachability.STALE, VpnHealthRules.classify(s, t0 + 3_600_000))
    }

    @Test
    fun `handshake from a previous session does not prove the current one`() {
        // The previous session handshaked 10 minutes ago; the tunnel was just
        // restarted, so no handshake belongs to this session yet.
        val s = snapshot(connectedAt = t0, handshakeAt = t0 - 600_000)
        assertEquals(VpnReachability.VERIFYING, VpnHealthRules.classify(s, t0 + 1_000))
        assertEquals(VpnReachability.STALE, VpnHealthRules.classify(s, t0 + 60_000))
    }

    @Test
    fun `clock going backwards never reports unreachable`() {
        val s = snapshot(connectedAt = t0, handshakeAt = 0L)
        assertEquals(VpnReachability.VERIFYING, VpnHealthRules.classify(s, t0 - 10_000))
    }

    @Test
    fun `unknown connectedAt is treated as long elapsed`() {
        val s = snapshot(connectedAt = 0L, handshakeAt = 0L)
        assertEquals(VpnReachability.UNREACHABLE, VpnHealthRules.classify(s, t0))
    }

    @Test
    fun `needs reconnect only for stale or unreachable`() {
        assertTrue(VpnHealthRules.needsReconnect(VpnReachability.STALE))
        assertTrue(VpnHealthRules.needsReconnect(VpnReachability.UNREACHABLE))
        assertFalse(VpnHealthRules.needsReconnect(VpnReachability.HEALTHY))
        assertFalse(VpnHealthRules.needsReconnect(VpnReachability.VERIFYING))
        assertFalse(VpnHealthRules.needsReconnect(VpnReachability.UNKNOWN))
    }

    @Test
    fun `auto reconnect respects setting consent and cooldown`() {
        assertFalse(
            VpnHealthRules.shouldAutoReconnect(VpnReachability.STALE, false, true, 0L, t0),
        )
        assertFalse(
            VpnHealthRules.shouldAutoReconnect(VpnReachability.STALE, true, false, 0L, t0),
        )
        assertFalse(
            VpnHealthRules.shouldAutoReconnect(VpnReachability.HEALTHY, true, true, 0L, t0),
        )
        assertTrue(
            VpnHealthRules.shouldAutoReconnect(VpnReachability.STALE, true, true, 0L, t0),
        )
        assertFalse(
            VpnHealthRules.shouldAutoReconnect(
                VpnReachability.STALE, true, true, t0 - 10_000, t0,
            ),
        )
        assertTrue(
            VpnHealthRules.shouldAutoReconnect(
                VpnReachability.UNREACHABLE, true, true, t0 - 30_000, t0,
            ),
        )
    }

    @Test
    fun `newest handshake ignores zeros`() {
        assertEquals(0L, VpnHealthRules.newestHandshake(listOf(0L, 0L)))
        assertEquals(30L, VpnHealthRules.newestHandshake(listOf(10L, 30L, 0L, 20L)))
        assertEquals(0L, VpnHealthRules.newestHandshake(emptyList()))
    }

    @Test
    fun `snapshot reports handshake since connect`() {
        assertTrue(snapshot(t0, t0 + 1).handshakeSinceConnect())
        assertFalse(snapshot(t0, t0 - 1).handshakeSinceConnect())
        assertFalse(snapshot(t0, 0L).handshakeSinceConnect())
    }

    @Test
    fun `record round trips to snapshot`() {
        val record = VpnHealthRecord(
            profileId = "p",
            connectedAt = t0,
            lastHandshakeAt = t0 + 5,
            checkedAt = t0 + 9,
            reachability = VpnReachability.HEALTHY.name,
        )
        val snapshot = record.toSnapshot()
        assertEquals("p", snapshot.profileId)
        assertEquals(t0 + 5, snapshot.lastHandshakeAt)
        assertEquals(t0, snapshot.connectedAt)
    }
}
