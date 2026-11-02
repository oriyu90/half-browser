package com.yukiorita.halfbrowser.ui.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoadingNavigationTest {
    @Test fun fragmentOnlyNavigationIsDistinguishedFromNewDocuments() {
        assertTrue(isFragmentOnlyNavigation("https://example.com/page#a", "https://example.com/page#b"))
        assertTrue(isFragmentOnlyNavigation("https://example.com/page", "https://example.com/page#b"))
        assertTrue(isFragmentOnlyNavigation("https://example.com/page#a", "https://example.com/page"))
        assertFalse(isFragmentOnlyNavigation("", "https://example.com/page#a"))
        assertFalse(isFragmentOnlyNavigation("https://example.com/page?a=1", "https://example.com/page?a=2#b"))
        assertFalse(isFragmentOnlyNavigation("https://example.com/page#a", "https://example.com/other#b"))
        assertFalse(isFragmentOnlyNavigation("https://example.com/page#a", "https://example.com/page#a"))
    }

    @Test fun staleUrlDoesNotReleaseCurrentNavigation() {
        val navigation = LoadingNavigation()
        navigation.started("https://example.com/a")
        val first = navigation.tokenFor("https://example.com/a")!!
        navigation.started("https://example.com/b")
        assertNull(navigation.tokenFor("https://example.com/a"))
        assertFalse(navigation.isCurrent(first))
        assertNotNull(navigation.tokenFor("https://example.com/b"))
    }

    @Test fun sameUrlReloadInvalidatesOutstandingVisualCallback() {
        val navigation = LoadingNavigation()
        navigation.started("https://example.com/")
        val first = navigation.tokenFor("https://example.com/")!!
        navigation.started("https://example.com/")
        assertFalse(navigation.isCurrent(first))
        assertTrue(navigation.isCurrent(navigation.tokenFor("https://example.com/")!!))
        assertNull(navigation.tokenFor(null))
    }
}
