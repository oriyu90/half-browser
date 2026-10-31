package com.yukiorita.halfbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepLinksTest {

    @Test
    fun `home link`() {
        assertEquals(DeepLinks.Link.Home, DeepLinks.parse("halfbrowser://home"))
    }

    @Test
    fun `open link decodes url and bookmark id`() {
        val uri = DeepLinks.openUri("https://192.168.0.165:8844/a?b=c", "bookmark-1")
        val link = DeepLinks.parse(uri)
        assertTrue(link is DeepLinks.Link.Open)
        link as DeepLinks.Link.Open
        assertEquals("https://192.168.0.165:8844/a?b=c", link.url)
        assertEquals("bookmark-1", link.bookmarkId)
    }

    @Test
    fun `bookmark by id`() {
        val link = DeepLinks.parse(DeepLinks.bookmarkUri("abc-123"))
        assertEquals(DeepLinks.Link.BookmarkById("abc-123"), link)
    }

    @Test
    fun `malformed links return null`() {
        assertNull(DeepLinks.parse(null))
        assertNull(DeepLinks.parse(""))
        assertNull(DeepLinks.parse("https://example.com"))
        assertNull(DeepLinks.parse("halfbrowser://open"))
        assertNull(DeepLinks.parse("halfbrowser://open?url=javascript%3Aalert(1)"))
        assertNull(DeepLinks.parse("halfbrowser://bookmark/"))
        assertNull(DeepLinks.parse("halfbrowser://unknown"))
    }

    @Test
    fun `japanese url round trips`() {
        val url = "https://example.com/検索?q=テスト"
        val link = DeepLinks.parse(DeepLinks.openUri(url, null))
        assertTrue(link is DeepLinks.Link.Open)
        assertEquals(url, (link as DeepLinks.Link.Open).url)
        assertNull(link.bookmarkId)
    }
}
