package com.yukiorita.halfbrowser.core

import org.junit.Assert.assertTrue
import org.junit.Test

class WebCompatTest {

    @Test
    fun `polyfill defines every covered api`() {
        WebCompat.COVERED_APIS.forEach { api ->
            assertTrue("missing $api", WebCompat.POLYFILL_JS.contains(api))
        }
    }

    @Test
    fun `viewport repair only rewrites viewport units`() {
        val script = WebCompat.VIEWPORT_UNIT_FIX_JS
        assertTrue(script.contains("100vh"))
        assertTrue(script.contains("innerHeight"))
        assertTrue(script.contains("getPropertyPriority"))
        listOf("fetch(", "XMLHttpRequest", "localStorage", "document.cookie").forEach { needle ->
            assertTrue("unexpected $needle", !script.contains(needle))
        }
    }

    @Test
    fun `polyfill never touches networking or storage`() {
        val script = WebCompat.POLYFILL_JS
        listOf("fetch(", "XMLHttpRequest", "localStorage", "document.cookie", "eval(").forEach { needle ->
            assertTrue("unexpected $needle", !script.contains(needle))
        }
    }
}
