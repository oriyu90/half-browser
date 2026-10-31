package com.yukiorita.halfbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookmarkIconsTest {

    @Test
    fun `color is deterministic for the same url`() {
        assertEquals(
            BookmarkIcons.colorFor("https://example.com"),
            BookmarkIcons.colorFor("https://example.com"),
        )
    }

    @Test
    fun `different urls can differ`() {
        val colors = (0..20).map { BookmarkIcons.colorFor("https://example$it.com") }.toSet()
        assertTrue(colors.size > 1)
    }

    @Test
    fun `explicit seed wins`() {
        assertEquals(BookmarkIcons.PALETTE[3], BookmarkIcons.colorFor("https://example.com", 3))
    }

    @Test
    fun `glyph prefers user override then title then host`() {
        assertEquals("X", BookmarkIcons.glyphFor("Example", "https://example.com", "X"))
        assertEquals("E", BookmarkIcons.glyphFor("example site", "https://example.com"))
        assertEquals("E", BookmarkIcons.glyphFor("", "https://example.com"))
        assertEquals("•", BookmarkIcons.glyphFor("", ""))
    }

    @Test
    fun `shortcut id round trips`() {
        val id = "0b4c9a64-1111-2222-3333-444455556666"
        assertEquals(id, BookmarkIcons.bookmarkIdFromShortcut(BookmarkIcons.shortcutId(id)))
        assertNull(BookmarkIcons.bookmarkIdFromShortcut("other"))
    }

    @Test
    fun `shortcut labels are bounded`() {
        val long = "a".repeat(120)
        assertEquals(40, BookmarkIcons.shortcutLabel(long, "https://example.com").length)
        assertEquals("Example", BookmarkIcons.shortcutLabel("Example", "https://example.com"))
        assertNotEquals("", BookmarkIcons.shortcutLabel("", ""))
    }
}
