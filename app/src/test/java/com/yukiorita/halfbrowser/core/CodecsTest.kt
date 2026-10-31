package com.yukiorita.halfbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecsTest {

    @Test
    fun `settings round trip`() {
        val settings = AppSettings(
            themeMode = ThemeMode.DARK,
            dynamicColor = true,
            uiScale = 1.2f,
            fontScale = 1.1f,
            gridColumns = 5,
            language = LanguageMode.JAPANESE,
            searchEngine = SearchEngine.GOOGLE,
            showAddressBar = false,
        )
        val decoded = Codecs.decodeSettings(Codecs.encodeSettings(settings))
        assertEquals(settings, decoded)
    }

    @Test
    fun `corrupt settings fall back to defaults`() {
        assertEquals(AppSettings.DEFAULT, Codecs.decodeSettings("{not json"))
        assertEquals(AppSettings.DEFAULT, Codecs.decodeSettings(null))
    }

    @Test
    fun `settings are clamped`() {
        val wild = AppSettings(uiScale = 5f, fontScale = 0.1f, gridColumns = 7)
        val safe = Codecs.sanitize(wild)
        assertEquals(AppSettings.MAX_UI_SCALE, safe.uiScale, 0.0001f)
        assertEquals(AppSettings.MIN_FONT_SCALE, safe.fontScale, 0.0001f)
        assertEquals(0, safe.gridColumns)
    }

    @Test
    fun `unknown json keys are ignored for forward compatibility`() {
        val raw = """{"themeMode":"DARK","futureOption":true}"""
        assertEquals(ThemeMode.DARK, Codecs.decodeSettings(raw).themeMode)
    }

    @Test
    fun `bookmarks round trip and filter invalid entries`() {
        val items = listOf(
            Bookmark(id = "1", title = "Example", url = "https://example.com/"),
            Bookmark(id = "2", title = "Local", url = "https://192.168.0.165:8844/"),
        )
        val decoded = Codecs.decodeBookmarks(Codecs.encodeBookmarks(items))
        assertEquals(items, decoded)

        val mixed = """{"version":1,"items":[
            {"id":"a","title":"ok","url":"https://example.com"},
            {"id":"","title":"bad","url":"https://example.com"},
            {"id":"c","title":"bad url","url":"javascript:alert(1)"}
        ]}"""
        val filtered = Codecs.decodeBookmarks(mixed)
        assertEquals(1, filtered.size)
        assertEquals("a", filtered.first().id)
    }

    @Test
    fun `duplicate bookmark ids are removed`() {
        val raw = """{"items":[
            {"id":"a","title":"one","url":"https://example.com"},
            {"id":"a","title":"two","url":"https://example.org"}
        ]}"""
        assertEquals(1, Codecs.decodeBookmarks(raw).size)
    }

    @Test
    fun `site watches round trip and deduplicate`() {
        val watches = listOf(
            SiteWatch(bookmarkId = "a", enabled = true, lastCheckedAt = 5, fingerprint = "ff"),
            SiteWatch(bookmarkId = "a", enabled = false),
            SiteWatch(bookmarkId = "", enabled = true),
        )
        val decoded = Codecs.decodeSiteWatches(Codecs.encodeSiteWatches(watches))
        assertEquals(1, decoded.size)
        assertEquals("a", decoded.first().bookmarkId)
        assertTrue(Codecs.decodeSiteWatches("garbage").isEmpty())
    }

    @Test
    fun `watch settings sanitize the interval`() {
        val raw = """{"enabled":true,"intervalMinutes":7,"wifiOnly":true}"""
        val decoded = Codecs.decodeWatchSettings(raw)
        assertEquals(30, decoded.intervalMinutes)
        assertTrue(decoded.wifiOnly)
        assertEquals(WatchRules.DEFAULT_INTERVAL, Codecs.decodeWatchSettings(null).intervalMinutes)
    }

    @Test
    fun `trusted sites round trip`() {
        val sites = listOf(
            TrustedSite("example.com", 443, "ab".repeat(32), subject = "CN=example.com", issuer = "CN=example.com"),
        )
        assertEquals(sites, Codecs.decodeTrustedSites(Codecs.encodeTrustedSites(sites)))
        assertTrue(Codecs.decodeTrustedSites("garbage").isEmpty())
        assertFalse(Codecs.encodeSettings(AppSettings.DEFAULT).isEmpty())
    }
}
