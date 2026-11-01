package com.yukiorita.halfbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchEngineTest {

    @Test
    fun `removed bing key falls back to duckduckgo`() {
        assertEquals(SearchEngine.DUCKDUCKGO, SearchEngine.fromKey("bing"))
        assertEquals(SearchEngine.DUCKDUCKGO, SearchEngine.fromKey("nonsense"))
        assertEquals(SearchEngine.DUCKDUCKGO, SearchEngine.fromKey(null))
    }

    @Test
    fun `all engines have a unique key and a percent-s template`() {
        val keys = SearchEngine.entries.map { it.key }
        assertEquals(keys.size, keys.distinct().size)
        SearchEngine.entries.forEach { engine ->
            assertTrue(engine.key, engine.template.startsWith("https://"))
            assertTrue(engine.key, engine.template.contains("%s"))
            assertTrue(engine.key, engine.label.isNotBlank())
        }
    }

    @Test
    fun `new engines resolve by key and build encoded search urls`() {
        listOf(
            "brave" to "https://search.brave.com/search?q=",
            "ecosia" to "https://www.ecosia.org/search?q=",
            "kagi" to "https://kagi.com/search?q=",
            "mojeek" to "https://www.mojeek.com/search?q=",
        ).forEach { (key, prefix) ->
            val engine = SearchEngine.fromKey(key)
            assertEquals(key, engine.key)
            val url = UrlNormalizer.searchUrl("hello world", engine.template)
            assertTrue(url.startsWith(prefix))
            assertTrue("space must be encoded: $url", !url.contains(" "))
        }
    }

    @Test
    fun `bing is no longer offered`() {
        assertFalse(SearchEngine.entries.any { it.key == "bing" })
    }
}
