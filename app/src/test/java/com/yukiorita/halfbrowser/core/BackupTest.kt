package com.yukiorita.halfbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {

    private val bookmark = Bookmark(
        id = "b1",
        title = "Example",
        url = "https://example.com/",
        glyph = "EX",
    )

    private val backup = BackupFile(
        exportedAt = 1_700_000_000_000L,
        appVersion = "1.2.0",
        settings = AppSettings(
            themeMode = ThemeMode.DARK,
            searchEngine = SearchEngine.MOJEEK,
            accentHue = 280f,
            accentSaturation = 0.7f,
            accentTone = 0.6f,
        ),
        bookmarks = listOf(bookmark),
        siteWatches = listOf(SiteWatch(bookmarkId = "b1", enabled = true, fingerprint = "abc")),
        watchSettings = SiteWatchSettings(enabled = true, intervalMinutes = 60),
        trustedSites = listOf(
            TrustedSite(
                host = "192.168.0.165",
                port = 8844,
                fingerprintSha256 = "abcd",
            ),
        ),
        credentials = EncryptedCredentialPayload(salt = "s", iv = "i", ciphertext = "c"),
        credentialCount = 1,
    )

    @Test
    fun `backup round trips through json`() {
        val encoded = BackupCodec.encode(backup)
        val decoded = BackupCodec.decode(encoded)
        assertTrue(decoded is BackupDecodeResult.Success)
        val file = (decoded as BackupDecodeResult.Success).file
        assertEquals(backup.bookmarks, file.bookmarks)
        assertEquals(backup.trustedSites, file.trustedSites)
        assertEquals(BackupCodec.FORMAT, file.format)
        assertEquals(280f, file.settings.accentHue, 0.001f)
        assertEquals(SearchEngine.MOJEEK, file.settings.searchEngine)
        assertEquals(1, file.credentialCount)
        assertNotNull(file.credentials)
    }

    @Test
    fun `foreign or corrupt files are rejected`() {
        assertEquals(
            BackupError.EMPTY,
            (BackupCodec.decode("") as BackupDecodeResult.Failure).error,
        )
        assertEquals(
            BackupError.INVALID_JSON,
            (BackupCodec.decode("{oops") as BackupDecodeResult.Failure).error,
        )
        assertEquals(
            BackupError.UNSUPPORTED_FORMAT,
            (BackupCodec.decode("""{"format":"other"}""") as BackupDecodeResult.Failure).error,
        )
        assertEquals(
            BackupError.UNSUPPORTED_VERSION,
            (
                BackupCodec.decode(
                    """{"format":"halfbrowser.backup","version":99}""",
                ) as BackupDecodeResult.Failure
                ).error,
        )
        assertEquals(
            BackupError.TOO_LARGE,
            (
                BackupCodec.decode("{}", byteSize = BackupCodec.MAX_BYTES + 1)
                    as BackupDecodeResult.Failure
                ).error,
        )
    }

    @Test
    fun `sanitize drops unsafe entries and clamps settings`() {
        val messy = BackupFile(
            settings = AppSettings(accentHue = -90f, accentSaturation = 0.01f, accentTone = -2f),
            bookmarks = listOf(
                bookmark,
                bookmark.copy(id = "dup"),
                bookmark.copy(id = "bad", url = "javascript:alert(1)"),
                bookmark.copy(id = "ftp", url = "ftp://example.com/file"),
            ),
            siteWatches = listOf(
                SiteWatch(bookmarkId = "b1", enabled = true),
                SiteWatch(bookmarkId = "missing", enabled = true),
            ),
            trustedSites = listOf(
                TrustedSite(host = " Example.COM ", port = -4, fingerprintSha256 = "ab:cd"),
                TrustedSite(host = "example.com", port = 0, fingerprintSha256 = "ef"),
            ),
        )
        val clean = BackupRules.sanitize(messy)
        assertEquals(listOf("b1"), clean.bookmarks.map { it.id })
        assertEquals(listOf("b1"), clean.siteWatches.map { it.bookmarkId })
        assertEquals(AppSettings.MIN_ACCENT_SATURATION, clean.settings.accentSaturation, 0.001f)
        assertEquals(0f, clean.settings.accentTone, 0.001f)
        assertEquals(270f, clean.settings.accentHue, 0.001f)
        assertEquals(1, clean.trustedSites.size)
        assertEquals("example.com", clean.trustedSites.first().host)
    }

    @Test
    fun `merge keeps existing data and adds new items`() {
        val current = listOf(bookmark)
        val imported = listOf(
            bookmark.copy(title = "Changed"),
            bookmark.copy(id = "b2", url = "https://second.example/"),
            bookmark.copy(id = "b3", url = "https://example.com/"),
        )
        val merged = BackupRules.mergeBookmarks(current, imported)
        assertEquals(2, merged.size)
        assertEquals("Example", merged.first().title)
        assertEquals("b2", merged[1].id)
    }

    @Test
    fun `merge of watches and certificates is keyed`() {
        val watches = BackupRules.mergeWatches(
            listOf(SiteWatch(bookmarkId = "b1", enabled = false)),
            listOf(
                SiteWatch(bookmarkId = "b1", enabled = true),
                SiteWatch(bookmarkId = "b2", enabled = true),
            ),
        )
        assertEquals(2, watches.size)
        assertTrue(!watches.first { it.bookmarkId == "b1" }.enabled)

        val trusted = BackupRules.mergeTrusted(
            listOf(TrustedSite(host = "a", port = 443, fingerprintSha256 = "x")),
            listOf(
                TrustedSite(host = "a", port = 443, fingerprintSha256 = "y"),
                TrustedSite(host = "b", port = 443, fingerprintSha256 = "z"),
            ),
        )
        assertEquals(2, trusted.size)
        assertEquals("x", trusted.first { it.host == "a" }.fingerprintSha256)
    }

    @Test
    fun `prune removes watches for deleted bookmarks`() {
        val pruned = BackupRules.pruneWatches(
            listOf(
                SiteWatch(bookmarkId = "b1"),
                SiteWatch(bookmarkId = "gone"),
            ),
            listOf(bookmark),
        )
        assertEquals(listOf("b1"), pruned.map { it.bookmarkId })
    }

    @Test
    fun `unknown fields in an older file still decode`() {
        val json = """
            {
              "format": "halfbrowser.backup",
              "version": 1,
              "futureField": {"nested": true},
              "bookmarks": [{"id":"b1","title":"T","url":"https://example.com/"}]
            }
        """.trimIndent()
        val decoded = BackupCodec.decode(json)
        assertTrue(decoded is BackupDecodeResult.Success)
        assertEquals(1, (decoded as BackupDecodeResult.Success).file.bookmarks.size)
    }
}
