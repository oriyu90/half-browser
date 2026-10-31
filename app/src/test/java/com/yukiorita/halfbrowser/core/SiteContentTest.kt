package com.yukiorita.halfbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteContentTest {

    private val page = """
        <html><head><title> Test &amp; Page </title>
        <style>body{color:red}</style></head>
        <body><!-- comment --><h1>Hello</h1><script>var x = 1;</script>
        <p>World&nbsp;wide</p></body></html>
    """.trimIndent()

    @Test
    fun `normalization strips markup scripts styles and comments`() {
        val text = SiteContent.normalize(page)
        assertTrue(text.contains("Hello"))
        assertTrue(text.contains("World wide"))
        assertTrue(!text.contains("color:red"))
        assertTrue(!text.contains("var x"))
        assertTrue(!text.contains("comment"))
        assertTrue(!text.contains("<h1>"))
    }

    @Test
    fun `title is extracted and entities decoded`() {
        assertEquals("Test & Page", SiteContent.titleOf(page))
        assertEquals("", SiteContent.titleOf("<html><body>no title</body></html>"))
    }

    @Test
    fun `fingerprint ignores markup, scripts and whitespace`() {
        val a = "<html><body><h1>Hello</h1><script>x=1</script></body></html>"
        val b = "<html><body>\n  <h1>Hello</h1>\n  <script>x=2</script>\n</body></html>"
        assertEquals(SiteContent.fingerprint(a), SiteContent.fingerprint(b))
    }

    @Test
    fun `fingerprint changes when the text changes`() {
        val a = "<html><body><h1>Hello</h1></body></html>"
        val b = "<html><body><h1>Hello again</h1></body></html>"
        assertNotEquals(SiteContent.fingerprint(a), SiteContent.fingerprint(b))
    }

    @Test
    fun `snippet is bounded`() {
        val long = "<html><body>" + "a".repeat(500) + "</body></html>"
        val snippet = SiteContent.snippet(long, maxChars = 50)
        assertTrue(snippet.length <= 51)
        assertTrue(snippet.endsWith("…"))
    }

    @Test
    fun `basic realm parsing handles quoted and bare values`() {
        assertEquals("Half-Browser QA", SiteContent.basicRealm("Basic realm=\"Half-Browser QA\", charset=\"UTF-8\""))
        assertEquals("Private", SiteContent.basicRealm("Basic realm=Private"))
        assertEquals("", SiteContent.basicRealm(null))
        assertEquals("", SiteContent.basicRealm("Basic"))
    }
}
