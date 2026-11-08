package com.yukiorita.halfbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class VpnConfigTest {

    private val privateKey = key(0x11)
    private val publicKey = key(0x22)
    private val presharedKey = key(0x33)

    private fun key(seed: Int): String {
        val bytes = ByteArray(32) { (seed + it).toByte() }
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun config(
        interfaceSection: String = """
            [Interface]
            PrivateKey = $privateKey
            Address = 10.0.0.2/32
            DNS = 1.1.1.1
        """.trimIndent(),
        peer: String = """
            [Peer]
            PublicKey = $publicKey
            AllowedIPs = 0.0.0.0/0
            Endpoint = vpn.example.com:51820
        """.trimIndent(),
    ): String = "$interfaceSection\n\n$peer\n"

    private fun ok(raw: String): ParsedVpnConfig {
        val result = VpnConfigParser.parse(raw)
        assertTrue("expected Ok but was $result", result is VpnConfigResult.Ok)
        return (result as VpnConfigResult.Ok).config
    }

    private fun error(raw: String): VpnConfigError {
        val result = VpnConfigParser.parse(raw)
        assertTrue("expected Error but was $result", result is VpnConfigResult.Error)
        return (result as VpnConfigResult.Error).reason
    }

    @Test
    fun `parses a complete configuration`() {
        val parsed = ok(config())
        assertEquals(listOf("10.0.0.2/32"), parsed.addresses)
        assertEquals(listOf("1.1.1.1"), parsed.dns)
        assertEquals("vpn.example.com:51820", parsed.endpoint)
        assertEquals(listOf("0.0.0.0/0"), parsed.allowedIps)
        assertEquals(1, parsed.peers.size)
    }

    @Test
    fun `parses multiple peers and optional keys`() {
        val raw = config(
            interfaceSection = """
                [Interface]
                PrivateKey = $privateKey
                Address = 10.0.0.2/32, fd00::2/128
                DNS = 1.1.1.1, 9.9.9.9
                ListenPort = 51821
                MTU = 1420
            """.trimIndent(),
            peer = """
                [Peer]
                PublicKey = $publicKey
                PresharedKey = $presharedKey
                AllowedIPs = 10.0.0.0/24, 192.168.0.0/16
                Endpoint = 203.0.113.5:51820
                PersistentKeepalive = 25

                [Peer]
                PublicKey = ${key(0x44)}
                AllowedIPs = fd00::/8
                Endpoint = [2001:db8::1]:51820
            """.trimIndent(),
        )
        val parsed = ok(raw)
        assertEquals(2, parsed.peers.size)
        assertEquals(listOf("10.0.0.2/32", "fd00::2/128"), parsed.addresses)
        assertEquals(51821, parsed.listenPort)
        assertEquals(1420, parsed.mtu)
        assertTrue(parsed.peers[0].hasPresharedKey)
        assertEquals(25, parsed.peers[0].persistentKeepalive)
        assertEquals("[2001:db8::1]:51820", parsed.peers[1].endpoint)
    }

    @Test
    fun `normalizes CRLF and BOM`() {
        val raw = "\uFEFF" + config().replace("\n", "\r\n")
        val parsed = ok(raw)
        assertFalse(parsed.source.contains("\r"))
        assertFalse(parsed.source.startsWith("\uFEFF"))
    }

    @Test
    fun `ignores comments and inline comments`() {
        val raw = """
            # a comment
            [Interface] ; trailing
            PrivateKey = $privateKey   # key
            Address = 10.0.0.2/32
            [Peer]
            PublicKey = $publicKey
            AllowedIPs = 0.0.0.0/0
            Endpoint = 203.0.113.5:51820
        """.trimIndent()
        val parsed = ok(raw)
        assertEquals("203.0.113.5:51820", parsed.endpoint)
    }

    @Test
    fun `rejects empty input`() {
        assertEquals(VpnConfigError.EMPTY, error(""))
        assertEquals(VpnConfigError.EMPTY, error("   \n\n  "))
    }

    @Test
    fun `rejects oversized input`() {
        val huge = config() + "#".repeat(VpnConfigParser.MAX_BYTES)
        assertEquals(VpnConfigError.TOO_LARGE, error(huge))
    }

    @Test
    fun `rejects missing interface or peer`() {
        assertEquals(
            VpnConfigError.MISSING_INTERFACE,
            error("[Peer]\nPublicKey = $publicKey\nAllowedIPs = 0.0.0.0/0\nEndpoint = 1.2.3.4:1"),
        )
        assertEquals(
            VpnConfigError.MISSING_PEER,
            error("[Interface]\nPrivateKey = $privateKey\nAddress = 10.0.0.2/32"),
        )
    }

    @Test
    fun `rejects duplicate interface sections`() {
        val raw = "[Interface]\nPrivateKey = $privateKey\n[Interface]\nPrivateKey = $privateKey\n" +
            "[Peer]\nPublicKey = $publicKey\nAllowedIPs = 0.0.0.0/0\nEndpoint = 1.2.3.4:1"
        assertEquals(VpnConfigError.DUPLICATE_SECTION, error(raw))
    }

    @Test
    fun `rejects invalid keys`() {
        assertEquals(
            VpnConfigError.INVALID_PRIVATE_KEY,
            error(config().replace(privateKey, "not-a-key")),
        )
        assertEquals(
            VpnConfigError.INVALID_PUBLIC_KEY,
            error(config().replace(publicKey, key(0x22).dropLast(1))),
        )
        assertEquals(
            VpnConfigError.INVALID_PRESHARED_KEY,
            error(config().replace("Endpoint", "PresharedKey = abc\nEndpoint")),
        )
    }

    @Test
    fun `rejects a non canonical base64 key`() {
        // Same 32 bytes, but the padding bits are not zero: the library would
        // accept it, and two different strings would mean the same key.
        val bytes = ByteArray(32) { 0x01 }
        val canonical = Base64.getEncoder().encodeToString(bytes)
        val last = canonical[42]
        val bumped = when (last) {
            'A' -> 'B'
            'B' -> 'C'
            else -> return // encoding differs across JDKs; the canonical check covers it
        }
        val nonCanonical = canonical.substring(0, 42) + bumped + "="
        assertFalse(VpnConfigParser.isWireGuardKey(nonCanonical))
        assertTrue(VpnConfigParser.isWireGuardKey(canonical))
    }

    @Test
    fun `rejects missing required peer fields`() {
        assertEquals(
            VpnConfigError.MISSING_ENDPOINT,
            error(config().replace("Endpoint = vpn.example.com:51820", "")),
        )
        assertEquals(
            VpnConfigError.MISSING_ALLOWED_IPS,
            error(config().replace("AllowedIPs = 0.0.0.0/0", "")),
        )
        assertEquals(
            VpnConfigError.MISSING_PUBLIC_KEY,
            error(config().replace("PublicKey = $publicKey", "")),
        )
    }

    @Test
    fun `rejects invalid endpoints`() {
        for (bad in listOf(
            "vpn.example.com",
            "vpn.example.com:0",
            "vpn.example.com:70000",
            "host with space:51820",
            "[not-an-ipv6]:51820",
            "1.2.3.4:abc",
            ":51820",
        )) {
            assertEquals(
                "endpoint '$bad' should be rejected",
                VpnConfigError.INVALID_ENDPOINT,
                error(config().replace("vpn.example.com:51820", bad)),
            )
        }
    }

    @Test
    fun `accepts valid endpoints`() {
        for (good in listOf(
            "vpn.example.com:51820",
            "1.2.3.4:1",
            "[2001:db8::1]:65535",
            "my-server_1.lan:51820",
        )) {
            assertTrue("endpoint '$good' should be accepted", VpnConfigParser.isEndpoint(good))
        }
    }

    @Test
    fun `rejects invalid addresses and allowed ips`() {
        assertEquals(
            VpnConfigError.INVALID_ADDRESS,
            error(config().replace("10.0.0.2/32", "10.0.0.2")),
        )
        assertEquals(
            VpnConfigError.INVALID_ADDRESS,
            error(config().replace("10.0.0.2/32", "10.0.0.2/33")),
        )
        assertEquals(
            VpnConfigError.INVALID_ALLOWED_IPS,
            error(config().replace("0.0.0.0/0", "0.0.0.0/33")),
        )
    }

    @Test
    fun `rejects invalid numeric options`() {
        assertEquals(
            VpnConfigError.INVALID_PORT,
            error(config().replace("DNS = 1.1.1.1", "DNS = 1.1.1.1\nListenPort = 0")),
        )
        assertEquals(
            VpnConfigError.INVALID_MTU,
            error(config().replace("DNS = 1.1.1.1", "DNS = 1.1.1.1\nMTU = 100")),
        )
        assertEquals(
            VpnConfigError.INVALID_KEEPALIVE,
            error(config().replace("Endpoint", "PersistentKeepalive = -1\nEndpoint")),
        )
    }

    @Test
    fun `rejects unknown keys and malformed lines`() {
        assertEquals(
            VpnConfigError.UNKNOWN_KEY,
            error(config().replace("DNS = 1.1.1.1", "DNS = 1.1.1.1\nBogus = 1")),
        )
        assertEquals(
            VpnConfigError.MALFORMED_LINE,
            error(config().replace("DNS = 1.1.1.1", "DNS 1.1.1.1")),
        )
        assertEquals(
            VpnConfigError.MALFORMED_LINE,
            error("PrivateKey = $privateKey"),
        )
    }

    @Test
    fun `rejects too many peers`() {
        val peer = """
            [Peer]
            PublicKey = $publicKey
            AllowedIPs = 0.0.0.0/0
            Endpoint = 1.2.3.4:51820
        """.trimIndent()
        val raw = "[Interface]\nPrivateKey = $privateKey\nAddress = 10.0.0.2/32\n" +
            (1..(VpnConfigParser.MAX_PEERS + 1)).joinToString("\n") { peer }
        assertEquals(VpnConfigError.TOO_LARGE, error(raw))
    }

    @Test
    fun `accepts advanced interface keys without surfacing them`() {
        val raw = config().replace(
            "DNS = 1.1.1.1",
            "DNS = 1.1.1.1\nFwMark = 0x1234\nJc = 3\nS1 = 10",
        )
        // Jc is not a wg-quick key (it is AmneziaWG); it must be rejected rather
        // than silently ignored, so the user learns the config will not work.
        assertEquals(VpnConfigError.UNKNOWN_KEY, error(raw))
    }

    @Test
    fun `accepts fwmark which wg-quick understands`() {
        val raw = config().replace("DNS = 1.1.1.1", "DNS = 1.1.1.1\nFwMark = 0x1234")
        assertNotNull(ok(raw))
    }

    @Test
    fun `wireguard key validation matches 32 byte base64`() {
        assertTrue(VpnConfigParser.isWireGuardKey(key(1)))
        assertFalse(VpnConfigParser.isWireGuardKey(""))
        assertFalse(VpnConfigParser.isWireGuardKey(key(1) + "A"))
        assertFalse(VpnConfigParser.isWireGuardKey(key(1).dropLast(1)))
        assertFalse(VpnConfigParser.isWireGuardKey("!!!notbase64!!!"))
    }

    @Test
    fun `cidr validation`() {
        assertTrue(VpnConfigParser.isCidr("0.0.0.0/0"))
        assertTrue(VpnConfigParser.isCidr("10.0.0.0/8"))
        assertTrue(VpnConfigParser.isCidr("::/0"))
        assertTrue(VpnConfigParser.isCidr("fd00::/8"))
        assertFalse(VpnConfigParser.isCidr("10.0.0.0"))
        assertFalse(VpnConfigParser.isCidr("10.0.0.0/33"))
        assertFalse(VpnConfigParser.isCidr("::/129"))
        assertFalse(VpnConfigParser.isCidr("/24"))
        assertFalse(VpnConfigParser.isCidr("256.0.0.0/8"))
    }
}

class VpnNamingTest {

    @Test
    fun `tunnel names satisfy the wireguard pattern`() {
        val ids = listOf("a", "abc-123", "550e8400-e29b-41d4-a716-446655440000", "日本語", "", "!!!")
        for (id in ids) {
            val name = VpnNaming.tunnelNameFor(id)
            assertTrue("'$name' from '$id'", VpnNaming.isValidTunnelName(name))
        }
    }

    @Test
    fun `display name is bounded and stripped of control characters`() {
        assertEquals("Home VPN", VpnNaming.sanitizeDisplayName("  Home VPN  ", "VPN"))
        assertEquals("VPN", VpnNaming.sanitizeDisplayName("   ", "VPN"))
        assertEquals("ab", VpnNaming.sanitizeDisplayName("a\u0000b", "VPN"))
        val long = "x".repeat(200)
        assertEquals(VpnConfigParser.MAX_NAME_LENGTH, VpnNaming.sanitizeDisplayName(long, "VPN").length)
    }

    @Test
    fun `tunnel names differ for different ids`() {
        assertFalse(
            VpnNaming.tunnelNameFor("aaaaaaaa-1") == VpnNaming.tunnelNameFor("bbbbbbbb-2"),
        )
    }
}

class VpnScopeTest {

    private val config = """
        [Interface]
        PrivateKey = k
        Address = 10.0.0.2/32
        DNS = 1.1.1.1

        [Peer]
        PublicKey = p
        AllowedIPs = 0.0.0.0/0
        Endpoint = 1.2.3.4:51820
    """.trimIndent()

    @Test
    fun `allow-lists only this app`() {
        val scoped = VpnScope.applyAppOnlyScope(config, "com.yukiorita.halfbrowser")
        assertTrue(scoped.contains("IncludedApplications = com.yukiorita.halfbrowser"))
        assertFalse(scoped.contains("ExcludedApplications"))
        // The rest of the config is preserved verbatim.
        assertTrue(scoped.contains("PrivateKey = k"))
        assertTrue(scoped.contains("Endpoint = 1.2.3.4:51820"))
    }

    @Test
    fun `drops a user supplied scope`() {
        val withScope = config.replace(
            "DNS = 1.1.1.1",
            "DNS = 1.1.1.1\nExcludedApplications = com.bank.app\nIncludedApplications = com.evil",
        )
        val scoped = VpnScope.applyAppOnlyScope(withScope, "com.yukiorita.halfbrowser")
        assertFalse(scoped.contains("com.bank.app"))
        assertFalse(scoped.contains("com.evil"))
        assertTrue(scoped.contains("IncludedApplications = com.yukiorita.halfbrowser"))
    }

    @Test
    fun `is idempotent`() {
        val once = VpnScope.applyAppOnlyScope(config, "com.yukiorita.halfbrowser")
        val twice = VpnScope.applyAppOnlyScope(once, "com.yukiorita.halfbrowser")
        assertEquals(once, twice)
    }

    @Test
    fun `exclusion form is available as a fallback`() {
        val scoped = VpnScope.applyScope(
            config,
            listOf(
                VpnScope.EXCLUDED_KEY to "com.example.other",
                VpnScope.EXCLUDED_KEY to "com.foo.bar",
            ),
        )
        assertTrue(scoped.contains("ExcludedApplications = com.example.other"))
        assertTrue(scoped.contains("ExcludedApplications = com.foo.bar"))
        assertFalse(scoped.contains("IncludedApplications"))
    }

    @Test
    fun `system packages are exempt`() {
        assertTrue(VpnScope.isSystemExempt("android"))
        assertTrue(VpnScope.isSystemExempt("com.android.systemui"))
        assertFalse(VpnScope.isSystemExempt("com.example.other"))
    }

    @Test
    fun `scope keys land inside the interface section`() {
        val scoped = VpnScope.applyAppOnlyScope(config, "com.yukiorita.halfbrowser")
        val interfaceEnd = scoped.indexOf("[Peer]")
        val scopeIndex = scoped.indexOf("IncludedApplications")
        assertTrue(scopeIndex in 0..interfaceEnd)
    }
}
