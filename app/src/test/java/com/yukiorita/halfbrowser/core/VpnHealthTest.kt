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

    // ---------------------------------------------------- poll schedule (v1.9.0)

    @Test
    fun `foreground polling uses the fast interval regardless of health`() {
        for (reach in VpnReachability.entries) {
            assertEquals(
                VpnHealthRules.POLL_FOREGROUND_MS,
                VpnHealthRules.nextPollDelayMs(true, reach, consecutiveHealthy = 0),
            )
            assertEquals(
                VpnHealthRules.POLL_FOREGROUND_MS,
                VpnHealthRules.nextPollDelayMs(true, reach, consecutiveHealthy = 50),
            )
        }
    }

    @Test
    fun `background polling backs off while the tunnel stays healthy`() {
        // consecutiveHealthy is the count *after* the observation: 0 means the
        // tunnel is not proven healthy yet, so the fast background rate applies.
        assertEquals(
            VpnHealthRules.POLL_BACKGROUND_MIN_MS,
            VpnHealthRules.nextPollDelayMs(false, VpnReachability.HEALTHY, 0),
        )
        val first = VpnHealthRules.nextPollDelayMs(false, VpnReachability.HEALTHY, 1)
        val second = VpnHealthRules.nextPollDelayMs(false, VpnReachability.HEALTHY, 2)
        val third = VpnHealthRules.nextPollDelayMs(false, VpnReachability.HEALTHY, 3)
        // One healthy check already applies the first step (60 s * 1.5).
        assertEquals(
            (VpnHealthRules.POLL_BACKGROUND_MIN_MS * VpnHealthRules.POLL_BACKOFF_FACTOR).toLong(),
            first,
        )
        assertTrue(second > first)
        assertTrue(third > second)
    }

    @Test
    fun `background backoff is capped`() {
        val capped = VpnHealthRules.nextPollDelayMs(false, VpnReachability.HEALTHY, 100)
        assertEquals(VpnHealthRules.POLL_BACKGROUND_MAX_MS, capped)
    }

    @Test
    fun `background polling stays fast when the tunnel is not healthy`() {
        for (reach in listOf(
            VpnReachability.UNKNOWN,
            VpnReachability.VERIFYING,
            VpnReachability.STALE,
            VpnReachability.UNREACHABLE,
            VpnReachability.TUNNEL_ONLY,
        )) {
            assertEquals(
                VpnHealthRules.POLL_BACKGROUND_MIN_MS,
                VpnHealthRules.nextPollDelayMs(false, reach, consecutiveHealthy = 9),
            )
        }
    }

    @Test
    fun `a broken tunnel resets the backoff`() {
        // Simulates the counter the controller keeps: any non-healthy result
        // resets it, so the next delay drops back to the minimum.
        assertEquals(
            VpnHealthRules.POLL_BACKGROUND_MIN_MS,
            VpnHealthRules.nextPollDelayMs(false, VpnReachability.STALE, consecutiveHealthy = 0),
        )
    }

    @Test
    fun `every delay is within the documented bounds`() {
        for (fg in listOf(true, false)) {
            for (reach in VpnReachability.entries) {
                for (n in 0..20) {
                    val d = VpnHealthRules.nextPollDelayMs(fg, reach, n)
                    assertTrue("delay $d out of range", d >= VpnHealthRules.POLL_FOREGROUND_MS)
                    assertTrue("delay $d out of range", d <= VpnHealthRules.POLL_BACKGROUND_MAX_MS)
                }
            }
        }
    }

    @Test
    fun `tunnel only is appended last so persisted names keep decoding`() {
        // VpnHealthRecord stores the enum by name; adding a value must not
        // change the meaning of the existing ones.
        assertEquals(VpnReachability.UNREACHABLE, VpnReachability.entries[4])
        assertEquals(VpnReachability.TUNNEL_ONLY, VpnReachability.entries.last())
    }

    // ------------------------------------------- publish rule (v1.9.0, audit 1-2)

    @Test
    fun `a tunnel adopted from the OS keeps its state across empty passes`() {
        // Regression guard for the audit finding: without this, the adopted
        // state was overwritten by UNKNOWN on the very next pass, which is not
        // a reconnect trigger, so verification stopped for good.
        assertEquals(
            VpnReachability.TUNNEL_ONLY,
            VpnHealthRules.publish(VpnReachability.UNKNOWN, VpnReachability.TUNNEL_ONLY),
        )
    }

    @Test
    fun `a real observation always wins over the adopted state`() {
        for (observed in listOf(
            VpnReachability.HEALTHY,
            VpnReachability.STALE,
            VpnReachability.UNREACHABLE,
            VpnReachability.VERIFYING,
        )) {
            assertEquals(observed, VpnHealthRules.publish(observed, VpnReachability.TUNNEL_ONLY))
        }
    }

    @Test
    fun `unknown is published when nothing was adopted`() {
        for (previous in listOf(
            VpnReachability.UNKNOWN,
            VpnReachability.VERIFYING,
            VpnReachability.HEALTHY,
            VpnReachability.STALE,
            VpnReachability.UNREACHABLE,
        )) {
            assertEquals(
                VpnReachability.UNKNOWN,
                VpnHealthRules.publish(VpnReachability.UNKNOWN, previous),
            )
        }
    }

    @Test
    fun `the shared backoff cap matches the controller counter`() {
        // Both sides cap at the same value; a mismatch would make the published
        // delay and the counter disagree.
        assertEquals(16, VpnHealthRules.MAX_BACKOFF_STEPS)
    }

    // -------------------------------------- network-change grace (v1.9.0, audit 2-3)

    @Test
    fun `a network change re-arms the handshake grace`() {
        val connectedAt = 1_000_000L
        val snapshot = VpnHealthSnapshot(profileId = "p", connectedAt = connectedAt)
        // 60 s after connecting the grace is over...
        assertEquals(
            VpnReachability.UNREACHABLE,
            VpnHealthRules.classify(snapshot, connectedAt + 60_000L),
        )
        // ...but a network change 5 s ago restarts it.
        assertEquals(
            VpnReachability.VERIFYING,
            VpnHealthRules.classify(
                snapshot,
                connectedAt + 60_000L,
                graceStartOverride = connectedAt + 55_000L,
            ),
        )
    }

    @Test
    fun `the override never shortens an existing grace`() {
        val connectedAt = 1_000_000L
        val snapshot = VpnHealthSnapshot(profileId = "p", connectedAt = connectedAt)
        // An override older than connectedAt must be ignored, not used to expire
        // the grace early.
        assertEquals(
            VpnReachability.VERIFYING,
            VpnHealthRules.classify(snapshot, connectedAt + 10_000L, graceStartOverride = 1L),
        )
    }

    @Test
    fun `a fresh handshake still wins over the grace`() {
        val now = 1_000_000L
        val snapshot = VpnHealthSnapshot(
            profileId = "p",
            connectedAt = now - 60_000L,
            lastHandshakeAt = now - 5_000L,
        )
        assertEquals(
            VpnReachability.HEALTHY,
            VpnHealthRules.classify(snapshot, now, graceStartOverride = now - 1_000L),
        )
    }
}
