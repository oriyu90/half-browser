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
    fun `polyfill never touches networking or storage`() {
        val script = WebCompat.POLYFILL_JS
        listOf("fetch(", "XMLHttpRequest", "localStorage", "document.cookie", "eval(").forEach { needle ->
            assertTrue("unexpected $needle", !script.contains(needle))
        }
    }
}
