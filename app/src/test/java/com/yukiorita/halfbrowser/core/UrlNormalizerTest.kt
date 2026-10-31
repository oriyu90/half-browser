package com.yukiorita.halfbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlNormalizerTest {

    @Test
    fun `explicit https is kept`() {
        assertEquals(
            "https://example.com/path?q=1",
            UrlNormalizer.toNavigable("https://example.com/path?q=1"),
        )
    }

    @Test
    fun `host with port gets https prefix`() {
        assertEquals(
            "https://192.168.0.165:8844",
            UrlNormalizer.toNavigable("192.168.0.165:8844"),
        )
    }

    @Test
    fun `bare host gets https prefix`() {
        assertEquals("https://example.com", UrlNormalizer.toNavigable("example.com"))
    }

    @Test
    fun `localhost with path and port`() {
        assertEquals(
            "https://localhost:8443/admin",
            UrlNormalizer.toNavigable("localhost:8443/admin"),
        )
    }

    @Test
    fun `spaces become a search query`() {
        val url = UrlNormalizer.toNavigable("hello world")
        assertTrue(url!!.startsWith("https://duckduckgo.com/?q="))
        assertTrue(url.contains("hello+world"))
    }

    @Test
    fun `single word becomes a search query`() {
        val url = UrlNormalizer.toNavigable("kotlin")
        assertTrue(url!!.startsWith("https://duckduckgo.com/?q="))
    }

    @Test
    fun `javascript scheme is rejected`() {
        assertNull(UrlNormalizer.toNavigable("javascript:alert(1)"))
        assertNull(UrlNormalizer.toNavigable(" JavaScript:alert(1)"))
    }

    @Test
    fun `file and intent schemes are rejected`() {
        assertNull(UrlNormalizer.toNavigable("file:///etc/passwd"))
        assertNull(UrlNormalizer.toNavigable("intent://x#Intent;end"))
        assertNull(UrlNormalizer.toNavigable("data:text/html,<h1>x</h1>"))
    }

    @Test
    fun `blank input is rejected`() {
        assertNull(UrlNormalizer.toNavigable(""))
        assertNull(UrlNormalizer.toNavigable("   "))
    }

    @Test
    fun `missing host is rejected`() {
        assertNull(UrlNormalizer.toNavigable("https:///path"))
        assertNull(UrlNormalizer.validateWebUrl("https://"))
    }

    @Test
    fun `canonical ignores trailing slash default port fragment and case`() {
        val a = UrlNormalizer.canonicalForCompare("https://Example.COM:443/")
        val b = UrlNormalizer.canonicalForCompare("https://example.com")
        val c = UrlNormalizer.canonicalForCompare("https://example.com/#section")
        assertEquals(a, b)
        assertEquals(b, c)
    }

    @Test
    fun `canonical keeps path query and non default port`() {
        val a = UrlNormalizer.canonicalForCompare("https://example.com/a/?x=1")
        val b = UrlNormalizer.canonicalForCompare("https://example.com/a?x=1")
        assertEquals(a, b)
        assertTrue(UrlNormalizer.canonicalForCompare("https://example.com:8443/")!!.contains(":8443"))
    }

    @Test
    fun `canonical returns null for non web urls`() {
        assertNull(UrlNormalizer.canonicalForCompare("ftp://example.com"))
        assertNull(UrlNormalizer.canonicalForCompare("not a url"))
    }

    @Test
    fun `display host works`() {
        assertEquals("example.com", UrlNormalizer.displayHost("https://example.com/x"))
        assertNull(UrlNormalizer.displayHost("nope"))
    }

    @Test
    fun `search template is respected`() {
        val url = UrlNormalizer.toNavigable("a b", "https://www.google.com/search?q=%s")
        assertEquals("https://www.google.com/search?q=a+b", url)
    }
}
