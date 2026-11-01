package com.yukiorita.halfbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryRulesTest {

    @Test
    fun `new pages go first`() {
        var list = HistoryRules.record(emptyList(), "https://a.example/", "A", 1000)
        list = HistoryRules.record(list, "https://b.example/", "B", 2000)
        assertEquals(listOf("https://b.example/", "https://a.example/"), list.map { it.url })
        assertEquals(1, list.first().visitCount)
    }

    @Test
    fun `revisit inside the merge window updates instead of adding`() {
        var list = HistoryRules.record(emptyList(), "https://a.example/", "A", 1_000)
        list = HistoryRules.record(list, "https://a.example/", "A2", 2_000)
        assertEquals(1, list.size)
        assertEquals("A2", list.first().title)
        assertEquals(1, list.first().visitCount)
        assertEquals(2_000L, list.first().visitedAt)
    }

    @Test
    fun `revisit after the merge window increments the counter`() {
        var list = HistoryRules.record(emptyList(), "https://a.example/", "A", 1_000)
        list = HistoryRules.record(
            list,
            "https://a.example/",
            "A",
            1_000 + HistoryRules.MERGE_WINDOW_MS + 1,
        )
        assertEquals(2, list.first().visitCount)
    }

    @Test
    fun `invalid urls are ignored`() {
        val list = HistoryRules.record(emptyList(), "javascript:alert(1)", "x", 1)
        assertTrue(list.isEmpty())
    }

    @Test
    fun `history is capped`() {
        var list = emptyList<HistoryEntry>()
        repeat(HistoryRules.MAX_ENTRIES + 20) { index ->
            list = HistoryRules.record(list, "https://site$index.example/", "S$index", index.toLong())
        }
        assertEquals(HistoryRules.MAX_ENTRIES, list.size)
    }

    @Test
    fun `sanitize drops bad rows and duplicates`() {
        val clean = HistoryRules.sanitize(
            listOf(
                HistoryEntry("https://a.example/", "A"),
                HistoryEntry("https://a.example/", "A again"),
                HistoryEntry("file:///etc/passwd", "x"),
            ),
        )
        assertEquals(1, clean.size)
    }
}
