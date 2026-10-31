package com.yukiorita.halfbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CredentialKeyTest {

    @Test
    fun `scheme host port and realm all participate`() {
        val base = CredentialKey.of("https", "example.com", 443, "Realm")
        assertNotEquals(base, CredentialKey.of("http", "example.com", 443, "Realm"))
        assertNotEquals(base, CredentialKey.of("https", "example.com", 8443, "Realm"))
        assertNotEquals(base, CredentialKey.of("https", "other.com", 443, "Realm"))
        assertNotEquals(base, CredentialKey.of("https", "example.com", 443, "Other"))
    }

    @Test
    fun `host and scheme are case insensitive`() {
        assertEquals(
            CredentialKey.of("https", "example.com", 443, "Realm"),
            CredentialKey.of("HTTPS", "Example.COM", 443, "Realm"),
        )
    }

    @Test
    fun `realm whitespace is trimmed`() {
        assertEquals(
            CredentialKey.of("https", "example.com", 443, "Realm"),
            CredentialKey.of("https", "example.com", 443, " Realm "),
        )
    }
}
