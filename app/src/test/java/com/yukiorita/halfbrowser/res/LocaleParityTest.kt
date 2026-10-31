package com.yukiorita.halfbrowser.res

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the "Japanese and English are fully supported" requirement:
 *  - both locales define exactly the same keys
 *  - no value is blank
 *  - format placeholders match per key (a missing %1$s would crash at runtime)
 */
class LocaleParityTest {

    private fun resDir(): File {
        val candidates = listOf(
            File("src/main/res"),
            File("app/src/main/res"),
            File("../app/src/main/res"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Could not locate res/ from ${File(".").absolutePath}")
    }

    private fun strings(localeDir: String): Map<String, String> {
        val file = File(resDir(), "$localeDir/strings.xml")
        assertTrue("Missing $file", file.isFile)
        val factory = DocumentBuilderFactory.newInstance().apply {
            // The files are trusted, but keep XXE protection on principle.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val doc = factory.newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val map = mutableMapOf<String, String>()
        for (i in 0 until nodes.length) {
            val element = nodes.item(i) as Element
            map[element.getAttribute("name")] = element.textContent.orEmpty()
        }
        return map
    }

    @Test
    fun `japanese and english define the same keys`() {
        val en = strings("values")
        val ja = strings("values-ja")
        assertEquals(
            "Missing in values-ja: ${en.keys - ja.keys}",
            emptySet<String>(),
            en.keys - ja.keys,
        )
        assertEquals(
            "Extra in values-ja: ${ja.keys - en.keys}",
            emptySet<String>(),
            ja.keys - en.keys,
        )
    }

    @Test
    fun `no string is blank`() {
        for (dir in listOf("values", "values-ja")) {
            strings(dir).forEach { (key, value) ->
                assertTrue("Blank string: $dir/$key", value.isNotBlank())
            }
        }
    }

    @Test
    fun `format placeholders are consistent`() {
        val placeholder = Regex("%[0-9]+\\$[sd]")
        val en = strings("values")
        val ja = strings("values-ja")
        for ((key, value) in en) {
            val expected = placeholder.findAll(value).map { it.value }.sorted().toList()
            val actual = placeholder.findAll(ja[key].orEmpty()).map { it.value }.sorted().toList()
            assertEquals("Placeholder mismatch for $key", expected, actual)
        }
    }

    @Test
    fun `core strings exist`() {
        val en = strings("values")
        listOf(
            "app_name",
            "home_bookmarks",
            "browser_back_home",
            "ssl_trust_always",
            "auth_username",
            "settings_theme",
        ).forEach { assertTrue("Missing key $it", en.containsKey(it)) }
    }
}
