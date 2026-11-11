package com.yukiorita.halfbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnTuningTest {

    private val base = """
        [Interface]
        PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        Address = 10.0.0.2/32
        DNS = 1.1.1.1

        [Peer]
        PublicKey = BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=
        AllowedIPs = 0.0.0.0/0
        Endpoint = vpn.example.com:51820
    """.trimIndent()

    @Test
    fun `adds keepalive and mtu when missing`() {
        val tuned = VpnTuning.tune(base)
        assertTrue(tuned.contains("PersistentKeepalive = 25"))
        assertTrue(tuned.contains("MTU = 1280"))
        // The keepalive must land inside the peer section, after its last line.
        val peerIndex = tuned.indexOf("[Peer]")
        val keepaliveIndex = tuned.indexOf("PersistentKeepalive")
        assertTrue(keepaliveIndex > peerIndex)
        // The MTU must land inside the interface section.
        val interfaceIndex = tuned.indexOf("[Interface]")
        val mtuIndex = tuned.indexOf("MTU = 1280")
        assertTrue(mtuIndex > interfaceIndex)
        assertTrue(mtuIndex < peerIndex)
    }

    @Test
    fun `keeps an explicit non-zero keepalive`() {
        val tuned = VpnTuning.tune(base.replace("Endpoint = vpn.example.com:51820", "Endpoint = vpn.example.com:51820\nPersistentKeepalive = 30"))
        assertTrue(tuned.contains("PersistentKeepalive = 30"))
        assertEquals(1, Regex("PersistentKeepalive").findAll(tuned).count())
    }

    @Test
    fun `replaces an explicit zero keepalive`() {
        val tuned = VpnTuning.tune(base.replace("Endpoint = vpn.example.com:51820", "Endpoint = vpn.example.com:51820\nPersistentKeepalive = 0"))
        assertTrue(tuned.contains("PersistentKeepalive = 25"))
        assertFalse(tuned.contains("PersistentKeepalive = 0"))
    }

    @Test
    fun `adds keepalive to every peer`() {
        val twoPeers = base + "\n\n[Peer]\nPublicKey = CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC=\nAllowedIPs = 10.0.0.0/24\nEndpoint = 192.0.2.1:51820\n"
        val tuned = VpnTuning.tune(twoPeers)
        assertEquals(2, Regex("PersistentKeepalive = 25").findAll(tuned).count())
    }

    @Test
    fun `caps an oversized mtu and keeps a small one`() {
        assertTrue(VpnTuning.tune(base.replace("DNS = 1.1.1.1", "DNS = 1.1.1.1\nMTU = 1420")).contains("MTU = 1280"))
        assertTrue(VpnTuning.tune(base.replace("DNS = 1.1.1.1", "DNS = 1.1.1.1\nMTU = 1200")).contains("MTU = 1200"))
    }

    @Test
    fun `caps the dns list at three servers`() {
        val tuned = VpnTuning.tune(base.replace("DNS = 1.1.1.1", "DNS = 1.1.1.1, 8.8.8.8, 9.9.9.9, 1.0.0.1"))
        assertTrue(tuned.contains("DNS = 1.1.1.1, 8.8.8.8, 9.9.9.9"))
        assertFalse(tuned.contains("1.0.0.1"))
    }

    @Test
    fun `keeps an empty dns line instead of dropping it`() {
        val tuned = VpnTuning.tune(base.replace("DNS = 1.1.1.1", "DNS ="))
        assertTrue(tuned.contains("DNS ="))
        // Dropping the key would silently switch the resolver to the interface
        // default, which is a behaviour change this object must not make.
        assertEquals(1, Regex("(?m)^DNS").findAll(tuned).count())
    }

    @Test
    fun `tuning is idempotent`() {
        val once = VpnTuning.tune(base)
        assertEquals(once, VpnTuning.tune(once))
    }

    @Test
    fun `handles crlf input`() {
        val tuned = VpnTuning.tune(base.replace("\n", "\r\n"))
        assertFalse(tuned.contains("\r"))
        assertTrue(tuned.contains("PersistentKeepalive = 25"))
    }

    @Test
    fun `empty input does not throw`() {
        assertEquals("MTU = 1280\n", VpnTuning.tune(""))
    }

    @Test
    fun `power saver uses a longer keepalive`() {
        val tuned = VpnTuning.tune(base, VpnTuning.POWER_SAVER_KEEPALIVE_SECONDS)
        assertTrue(tuned.contains("PersistentKeepalive = 60"))
        assertFalse(tuned.contains("PersistentKeepalive = 25"))
    }

    @Test
    fun `power saver also replaces an explicit zero keepalive`() {
        val tuned = VpnTuning.tune(
            base.replace("Endpoint = vpn.example.com:51820", "Endpoint = vpn.example.com:51820\nPersistentKeepalive = 0"),
            VpnTuning.POWER_SAVER_KEEPALIVE_SECONDS,
        )
        assertTrue(tuned.contains("PersistentKeepalive = 60"))
    }

    @Test
    fun `an out of range keepalive is clamped instead of throwing`() {
        // Defensive: the value comes from a setting, never from the user's text,
        // but a corrupt preference must not produce an invalid config.
        assertTrue(VpnTuning.tune(base, 0).contains("PersistentKeepalive = 1"))
        assertTrue(VpnTuning.tune(base, -5).contains("PersistentKeepalive = 1"))
    }
}
