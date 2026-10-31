package com.yukiorita.halfbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchRulesTest {

    @Test
    fun `intervals below the WorkManager minimum are rejected`() {
        assertEquals(30, WatchRules.normalizeInterval(5))
        assertEquals(15, WatchRules.normalizeInterval(15))
        assertEquals(180, WatchRules.normalizeInterval(180))
        assertEquals(30, WatchRules.normalizeInterval(17))
    }

    @Test
    fun `cooldown prevents notification spam`() {
        val now = 10_000_000L
        assertTrue(WatchRules.canNotify(now, 0L))
        assertTrue(WatchRules.canNotify(now, now - WatchRules.COOLDOWN_MS))
        assertFalse(WatchRules.canNotify(now, now - WatchRules.COOLDOWN_MS + 1))
    }

    @Test
    fun `baseline detection`() {
        assertTrue(WatchRules.isBaselineMissing(SiteWatch(bookmarkId = "a")))
        assertFalse(
            WatchRules.isBaselineMissing(
                SiteWatch(bookmarkId = "a", fingerprint = "abc"),
            ),
        )
    }
}
