package com.yukiorita.halfbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Decision-table tests for the system Always-on VPN restore hook. The tunnel
 * itself needs an emulator, but the rules for when restoring is attempted are
 * pure and therefore verified here.
 */
class VpnAlwaysOnTest {

    private val profile = VpnProfile(
        id = "p1",
        name = "Home",
        configText = "[Interface]\nPrivateKey = x\n[Peer]\nAllowedIPs = 10.8.0.0/24\nEndpoint = 10.0.2.2:51820\n",
    )

    @Test
    fun `restores the selected profile when it exists`() {
        assertEquals(
            VpnAlwaysOn.Action.Connect,
            VpnAlwaysOn.decide(
                stateActive = false,
                selectedProfileId = "p1",
                profiles = listOf(profile),
                storePersistent = true,
            ),
        )
    }

    @Test
    fun `stops when the tunnel is already up`() {
        assertEquals(
            VpnAlwaysOn.Action.Stop,
            VpnAlwaysOn.decide(
                stateActive = true,
                selectedProfileId = "p1",
                profiles = listOf(profile),
                storePersistent = true,
            ),
        )
    }

    @Test
    fun `stops when no profile is selected`() {
        assertEquals(
            VpnAlwaysOn.Action.Stop,
            VpnAlwaysOn.decide(
                stateActive = false,
                selectedProfileId = "",
                profiles = listOf(profile),
                storePersistent = true,
            ),
        )
    }

    @Test
    fun `stops when the selected profile was deleted`() {
        assertEquals(
            VpnAlwaysOn.Action.Stop,
            VpnAlwaysOn.decide(
                stateActive = false,
                selectedProfileId = "gone",
                profiles = listOf(profile),
                storePersistent = true,
            ),
        )
    }

    @Test
    fun `stops when the readable store has no profiles at all`() {
        assertEquals(
            VpnAlwaysOn.Action.Stop,
            VpnAlwaysOn.decide(
                stateActive = false,
                selectedProfileId = "gone",
                profiles = emptyList(),
                storePersistent = true,
            ),
        )
    }

    @Test
    fun `retries while the encrypted store is unreadable`() {
        // Keystore pre-unlock: nothing visible and the store itself unavailable.
        assertEquals(
            VpnAlwaysOn.Action.Retry,
            VpnAlwaysOn.decide(
                stateActive = false,
                selectedProfileId = "p1",
                profiles = emptyList(),
                storePersistent = false,
            ),
        )
    }

    @Test
    fun `retry schedule is bounded`() {
        val delays = VpnAlwaysOn.RETRY_DELAYS_MS
        assertEquals(0L, delays.first())
        for (i in 1 until delays.size) {
            assertTrue(delays[i] > delays[i - 1])
        }
        // Give up after roughly two minutes so the next app open takes over.
        assertTrue(delays.last() <= 120_000L)
    }

    private fun assertTrue(value: Boolean) {
        org.junit.Assert.assertTrue(value)
    }
}
