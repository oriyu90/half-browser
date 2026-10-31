package com.yukiorita.halfbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustPolicyTest {

    private val fingerprint = "ab".repeat(32)

    @Test
    fun `unknown host asks the user`() {
        assertEquals(TrustDecision.Unknown, TrustPolicy.decide("example.com", 443, fingerprint, null))
    }

    @Test
    fun `same certificate is trusted`() {
        val saved = TrustedSite(host = "example.com", port = 443, fingerprintSha256 = fingerprint)
        assertEquals(TrustDecision.Trusted, TrustPolicy.decide("example.com", 443, fingerprint, saved))
    }

    @Test
    fun `host comparison is case insensitive`() {
        val saved = TrustedSite(host = "Example.COM", port = 443, fingerprintSha256 = fingerprint)
        assertEquals(TrustDecision.Trusted, TrustPolicy.decide("example.com", 443, fingerprint, saved))
    }

    @Test
    fun `changed certificate is reported`() {
        val saved = TrustedSite(host = "example.com", port = 443, fingerprintSha256 = fingerprint)
        val decision = TrustPolicy.decide("example.com", 443, "cd".repeat(32), saved)
        assertTrue(decision is TrustDecision.Changed)
        assertEquals("example.com", (decision as TrustDecision.Changed).previous.host)
    }

    @Test
    fun `different host never inherits trust`() {
        val saved = TrustedSite(host = "a.example.com", port = 443, fingerprintSha256 = fingerprint)
        assertEquals(TrustDecision.Unknown, TrustPolicy.decide("b.example.com", 443, fingerprint, saved))
    }

    @Test
    fun `fingerprint normalization strips separators and case`() {
        val formatted = TrustPolicy.formatFingerprint(fingerprint)
        assertTrue(formatted.contains(":"))
        assertEquals(fingerprint, TrustPolicy.normalizeFingerprint(formatted))
        assertEquals("abab", TrustPolicy.normalizeFingerprint("AB:AB"))
    }

    @Test
    fun `a pin is scoped to its port`() {
        val saved = TrustedSite(host = "example.com", port = 8444, fingerprintSha256 = fingerprint)
        assertEquals(TrustDecision.Trusted, TrustPolicy.decide("example.com", 8444, fingerprint, saved))
        assertEquals(TrustDecision.Unknown, TrustPolicy.decide("example.com", 9443, fingerprint, saved))
    }

    @Test
    fun `only untrusted certificates may be remembered`() {
        assertEquals(true, TrustPolicy.canPin(3))
        assertEquals(false, TrustPolicy.canPin(1))
        assertEquals(false, TrustPolicy.canPin(2))
        assertEquals(false, TrustPolicy.canPin(4))
    }

    @Test
    fun `format leaves unexpected lengths alone`() {
        assertEquals("abc", TrustPolicy.formatFingerprint("abc"))
        assertNull(null)
    }
}
