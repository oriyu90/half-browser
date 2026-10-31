package com.yukiorita.halfbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustPolicyTest {

    private val fingerprint = "ab".repeat(32)

    @Test
    fun `unknown host asks the user`() {
        assertEquals(TrustDecision.Unknown, TrustPolicy.decide("example.com", fingerprint, null))
    }

    @Test
    fun `same certificate is trusted`() {
        val saved = TrustedSite(host = "example.com", fingerprintSha256 = fingerprint)
        assertEquals(TrustDecision.Trusted, TrustPolicy.decide("example.com", fingerprint, saved))
    }

    @Test
    fun `host comparison is case insensitive`() {
        val saved = TrustedSite(host = "Example.COM", fingerprintSha256 = fingerprint)
        assertEquals(TrustDecision.Trusted, TrustPolicy.decide("example.com", fingerprint, saved))
    }

    @Test
    fun `changed certificate is reported`() {
        val saved = TrustedSite(host = "example.com", fingerprintSha256 = fingerprint)
        val decision = TrustPolicy.decide("example.com", "cd".repeat(32), saved)
        assertTrue(decision is TrustDecision.Changed)
        assertEquals("example.com", (decision as TrustDecision.Changed).previous.host)
    }

    @Test
    fun `different host never inherits trust`() {
        val saved = TrustedSite(host = "a.example.com", fingerprintSha256 = fingerprint)
        assertEquals(TrustDecision.Unknown, TrustPolicy.decide("b.example.com", fingerprint, saved))
    }

    @Test
    fun `fingerprint normalization strips separators and case`() {
        val formatted = TrustPolicy.formatFingerprint(fingerprint)
        assertTrue(formatted.contains(":"))
        assertEquals(fingerprint, TrustPolicy.normalizeFingerprint(formatted))
        assertEquals("abab", TrustPolicy.normalizeFingerprint("AB:AB"))
    }

    @Test
    fun `format leaves unexpected lengths alone`() {
        assertEquals("abc", TrustPolicy.formatFingerprint("abc"))
        assertNull(null)
    }
}
