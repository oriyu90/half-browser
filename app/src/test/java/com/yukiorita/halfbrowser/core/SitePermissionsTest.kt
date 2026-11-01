package com.yukiorita.halfbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SitePermissionsTest {

    @Test
    fun `origin drops the default port and keeps an explicit one`() {
        assertEquals("https://example.com", SitePermissions.originOf("https://example.com/a/b?c=1"))
        assertEquals("https://example.com", SitePermissions.originOf("https://example.com:443/"))
        assertEquals("http://example.com", SitePermissions.originOf("http://example.com:80/x"))
        assertEquals(
            "https://example.com:8443",
            SitePermissions.originOf("https://example.com:8443/x"),
        )
        assertEquals(
            "http://192.168.0.10:8080",
            SitePermissions.originOf("http://192.168.0.10:8080/"),
        )
    }

    @Test
    fun `origin is lower case and ignores user info`() {
        assertEquals("https://example.com", SitePermissions.originOf("HTTPS://Example.COM/Path"))
        assertEquals("https://example.com", SitePermissions.originOf("https://user:pw@example.com/x"))
    }

    @Test
    fun `non web schemes and broken input have no origin`() {
        assertNull(SitePermissions.originOf(null))
        assertNull(SitePermissions.originOf(""))
        assertNull(SitePermissions.originOf("about:blank"))
        assertNull(SitePermissions.originOf("file:///sdcard/x.html"))
        assertNull(SitePermissions.originOf("ftp://example.com/"))
        assertNull(SitePermissions.originOf("https://exa mple.com/"))
        assertNull(SitePermissions.originOf("https://"))
    }

    @Test
    fun `http and https are distinct origins`() {
        val secure = SitePermissions.originOf("https://example.com/")
        val plain = SitePermissions.originOf("http://example.com/")
        assertTrue(secure != plain)
    }

    @Test
    fun `hostOf renders the host without scheme or port`() {
        assertEquals("example.com", SitePermissions.hostOf("https://example.com"))
        assertEquals("example.com", SitePermissions.hostOf("https://example.com:8443"))
        assertEquals("192.168.0.10", SitePermissions.hostOf("http://192.168.0.10:8080"))
    }

    @Test
    fun `webview resources map to the right permission kinds`() {
        assertEquals(
            SitePermissionKind.CAMERA,
            SitePermissionKind.fromWebResource("android.webkit.resource.VIDEO_CAPTURE"),
        )
        assertEquals(
            SitePermissionKind.MICROPHONE,
            SitePermissionKind.fromWebResource("android.webkit.resource.AUDIO_CAPTURE"),
        )
        assertNull(
            SitePermissionKind.fromWebResource("android.webkit.resource.PROTECTED_MEDIA_ID"),
        )
        assertNull(SitePermissionKind.fromWebResource(null))
    }

    @Test
    fun `permission kinds round trip through their stored key`() {
        SitePermissionKind.entries.forEach { kind ->
            assertEquals(kind, SitePermissionKind.fromKey(kind.key))
        }
        assertNull(SitePermissionKind.fromKey("telepathy"))
    }

    @Test
    fun `isWebOrigin only accepts http and https`() {
        assertTrue(SitePermissions.isWebOrigin("https://example.com"))
        assertTrue(SitePermissions.isWebOrigin("http://example.com"))
        assertFalse(SitePermissions.isWebOrigin("example.com"))
        assertFalse(SitePermissions.isWebOrigin("ws://example.com"))
    }

    @Test
    fun `site permission grants survive an encode decode round trip`() {
        val grants = listOf(
            SitePermissionGrant(
                origin = "https://example.com",
                kind = SitePermissionKind.NOTIFICATIONS.key,
                allowed = true,
                updatedAt = 10L,
            ),
            SitePermissionGrant(
                origin = "http://intranet:8080",
                kind = SitePermissionKind.CAMERA.key,
                allowed = false,
                updatedAt = 20L,
            ),
        )
        val decoded = Codecs.decodeSitePermissions(Codecs.encodeSitePermissions(grants))
        assertEquals(grants, decoded)
    }

    @Test
    fun `unknown or malformed grants are dropped on load`() {
        val raw = """
            {"version":1,"items":[
              {"origin":"https://ok.example","kind":"camera","allowed":true},
              {"origin":"https://ok.example","kind":"telepathy","allowed":true},
              {"origin":"file:///x","kind":"camera","allowed":true},
              {"origin":"https://ok.example","kind":"camera","allowed":false}
            ]}
        """.trimIndent()
        val decoded = Codecs.decodeSitePermissions(raw)
        assertEquals(1, decoded.size)
        assertEquals("https://ok.example", decoded.first().origin)
        assertEquals(true, decoded.first().allowed)
    }

    @Test
    fun `blank stored json decodes to no grants`() {
        assertTrue(Codecs.decodeSitePermissions(null).isEmpty())
        assertTrue(Codecs.decodeSitePermissions("").isEmpty())
        assertTrue(Codecs.decodeSitePermissions("not json").isEmpty())
    }
}
