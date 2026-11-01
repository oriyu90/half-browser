package com.yukiorita.halfbrowser.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccentMathTest {

    private val hues = (0..350 step 10).map { it.toFloat() }
    private val saturations = listOf(0.15f, 0.4f, 0.75f, 1.0f)
    private val tones = listOf(0f, 0.5f, 1f)

    @Test
    fun `every accent combination keeps AA contrast on its own colours`() {
        hues.forEach { hue ->
            saturations.forEach { saturation ->
                tones.forEach { tone ->
                    listOf(false, true).forEach { dark ->
                        val p = AccentMath.palette(hue, saturation, tone, dark)
                        val pairs = listOf(
                            "primary" to (p.onPrimary to p.primary),
                            "primaryContainer" to (p.onPrimaryContainer to p.primaryContainer),
                            "secondary" to (p.onSecondary to p.secondary),
                            "secondaryContainer" to (p.onSecondaryContainer to p.secondaryContainer),
                            "tertiary" to (p.onTertiary to p.tertiary),
                            "tertiaryContainer" to (p.onTertiaryContainer to p.tertiaryContainer),
                        )
                        pairs.forEach { (name, pair) ->
                            val ratio = AccentMath.contrastRatio(pair.first, pair.second)
                            assertTrue(
                                "hue=$hue sat=$saturation tone=$tone dark=$dark $name ratio=$ratio",
                                ratio >= 4.5,
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `palette is deterministic and hue wraps`() {
        val a = AccentMath.palette(222f, 0.86f, 0.5f, dark = false)
        val b = AccentMath.palette(222f, 0.86f, 0.5f, dark = false)
        val wrapped = AccentMath.palette(582f, 0.86f, 0.5f, dark = false)
        assertEquals(a, b)
        assertEquals(a, wrapped)
    }

    @Test
    fun `default accent stays in the blue range`() {
        val p = AccentMath.palette(
            AppSettings.DEFAULT_ACCENT_HUE,
            AppSettings.DEFAULT_ACCENT_SATURATION,
            AppSettings.DEFAULT_ACCENT_TONE,
            dark = false,
        )
        val red = (p.primary shr 16) and 0xFF
        val green = (p.primary shr 8) and 0xFF
        val blue = p.primary and 0xFF
        assertTrue("blue channel should dominate: $red/$green/$blue", blue > red && blue > green)
    }

    @Test
    fun `dark mode primary is lighter than light mode primary`() {
        val light = AccentMath.palette(200f, 0.8f, 0.5f, dark = false).primary
        val dark = AccentMath.palette(200f, 0.8f, 0.5f, dark = true).primary
        assertTrue(AccentMath.luminance(dark) > AccentMath.luminance(light))
    }

    @Test
    fun `readableOn always picks the higher contrast`() {
        listOf(
            AccentMath.hsl(0f, 0f, 0f),
            AccentMath.hsl(0f, 0f, 1f),
            AccentMath.hsl(210f, 0.9f, 0.4f),
            AccentMath.hsl(48f, 1f, 0.6f),
        ).forEach { background ->
            val on = AccentMath.readableOn(background)
            assertTrue(AccentMath.contrastRatio(on, background) >= 4.5)
        }
    }

    @Test
    fun `invalid hue falls back instead of producing NaN`() {
        assertEquals(AppSettings.DEFAULT_ACCENT_HUE, AccentMath.normalizeHue(Float.NaN), 0.001f)
        assertEquals(0f, AccentMath.normalizeHue(-360f), 0.001f)
        assertEquals(359f, AccentMath.normalizeHue(359f), 0.001f)
        assertNotEquals(0, AccentMath.palette(Float.NaN, 0.5f, 0.5f, false).primary)
    }

    @Test
    fun `hsl produces opaque colours`() {
        val color = AccentMath.hsl(120f, 0.5f, 0.5f)
        assertEquals(0xFF, (color ushr 24) and 0xFF)
    }
}
