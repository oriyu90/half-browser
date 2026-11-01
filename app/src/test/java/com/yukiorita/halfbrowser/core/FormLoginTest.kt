package com.yukiorita.halfbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormLoginTest {

    @Test
    fun `extraction json is parsed`() {
        val parsed = FormLogin.parseExtraction("""{"u":"Yuki","p":"secret","a":"https://x/login"}""")
        assertEquals("Yuki", parsed?.username)
        assertEquals("secret", parsed?.password)
    }

    @Test
    fun `empty answers mean no form`() {
        assertNull(FormLogin.parseExtraction(null))
        assertNull(FormLogin.parseExtraction(""))
        assertNull(FormLogin.parseExtraction("null"))
        assertNull(FormLogin.parseExtraction("{}"))
        assertNull(FormLogin.parseExtraction("""{"u":"Yuki","p":""}"""))
        assertNull(FormLogin.parseExtraction("not json"))
    }

    @Test
    fun `fill script escapes quotes and newlines`() {
        val script = FormLogin.fillScript("""he said "hi"""", "line1\nline2\\x")
        assertTrue(script.contains("""\"hi\""""))
        assertTrue(script.contains("\\n"))
        assertTrue(script.contains("has-value"))
    }

    @Test
    fun `realm marker is stable`() {
        assertEquals("form-login", FormLogin.REALM)
    }
}
