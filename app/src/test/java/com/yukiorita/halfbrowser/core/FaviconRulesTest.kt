package com.yukiorita.halfbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FaviconRulesTest {

    @Test
    fun `candidate urls keep scheme, host and port`() {
        assertEquals(
            listOf(
                "https://example.com/apple-touch-icon.png",
                "https://example.com/apple-touch-icon-precomposed.png",
                "https://example.com/favicon.ico",
            ),
            FaviconRules.candidateUrls("https://example.com/some/page"),
        )
        assertEquals(
            "http://192.168.0.10:8080/favicon.ico",
            FaviconRules.candidateUrls("http://192.168.0.10:8080/x")!!.last(),
        )
        assertTrue(FaviconRules.candidateUrls("ftp://example.com/").isEmpty())
        assertTrue(FaviconRules.candidateUrls("not a url").isEmpty())
    }

    @Test
    fun `icon href is resolved from link tags`() {
        val html = """
            <head>
              <link rel="stylesheet" href="/style.css">
              <link rel="shortcut icon" href="/assets/icon.png">
            </head>
        """.trimIndent()
        assertEquals(
            "https://example.com/assets/icon.png",
            FaviconRules.parseIconHref(html, "https://example.com/a/b"),
        )
    }

    @Test
    fun `single quoted and relative hrefs work`() {
        val html = "<link rel='icon' type='image/png' href='favicon-32.png'>"
        assertEquals(
            "https://example.com/a/favicon-32.png",
            FaviconRules.parseIconHref(html, "https://example.com/a/page"),
        )
    }

    @Test
    fun `data and non-http icons are skipped`() {
        assertNull(
            FaviconRules.parseIconHref(
                "<link rel=icon href=\"data:image/png;base64,AAAA\">",
                "https://example.com/",
            ),
        )
        assertNull(FaviconRules.parseIconHref("<html></html>", "https://example.com/"))
    }

    @Test
    fun `magic numbers detect common icon formats`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
        val html = "<!doctype html>".toByteArray()
        assertTrue(FaviconRules.looksLikeImage(png))
        assertTrue(FaviconRules.looksLikeImage(jpeg))
        assertFalse(FaviconRules.looksLikeImage(html))
        assertFalse(FaviconRules.looksLikeImage(ByteArray(4)))
    }

    @Test
    fun `png payload is extracted from an ico container`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4, 5, 6, 7, 8)
        val ico = ByteArray(6 + 16 + png.size)
        ico[2] = 1 // icon type
        ico[4] = 1 // one entry
        val offset = 6 + 16
        ico[6 + 8] = png.size.toByte()
        ico[6 + 12] = offset.toByte()
        png.copyInto(ico, offset)
        val extracted = FaviconRules.extractEmbeddedPng(ico)
        assertNotNull(extracted)
        assertEquals(png.toList(), extracted!!.toList())
    }

    @Test
    fun `file names are sanitised`() {
        assertEquals("abc_123.png", FaviconRules.iconFileName("abc/123"))
        assertEquals("icon.png", FaviconRules.iconFileName("///"))
    }
}
