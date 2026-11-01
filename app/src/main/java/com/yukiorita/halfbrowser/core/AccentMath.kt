package com.yukiorita.halfbrowser.core

/**
 * Deterministic accent-colour derivation.
 *
 * The settings UI only exposes a hue, a saturation and a brightness bias; every
 * concrete colour is computed here so the result never depends on the device
 * palette. Everything is plain integer ARGB math, which keeps it unit-testable
 * on the JVM (no Compose/Android colour classes).
 *
 * Contrast is a hard rule, not a suggestion: the "on" colour of every pair is
 * pure black or pure white, whichever is readable, so a user cannot pick a
 * combination that makes text disappear (WCAG AA, 4.5:1).
 */
object AccentMath {

    /** ARGB colours for one mode (light or dark). */
    data class AccentPalette(
        val primary: Int,
        val onPrimary: Int,
        val primaryContainer: Int,
        val onPrimaryContainer: Int,
        val secondary: Int,
        val onSecondary: Int,
        val secondaryContainer: Int,
        val onSecondaryContainer: Int,
        val tertiary: Int,
        val onTertiary: Int,
        val tertiaryContainer: Int,
        val onTertiaryContainer: Int,
    )

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()

    /** Hues used for the companion roles so a custom accent still has a triad. */
    private const val SECONDARY_SHIFT = 30f
    private const val TERTIARY_SHIFT = -32f

    fun palette(hue: Float, saturation: Float, tone: Float, dark: Boolean): AccentPalette {
        val h = normalizeHue(hue)
        val s = saturation.coerceIn(0f, 1f)
        val t = tone.coerceIn(0f, 1f)

        return if (dark) {
            val primary = hsl(h, s * 0.82f, lerp(0.68f, 0.84f, t))
            val primaryContainer = hsl(h, s * 0.70f, lerp(0.24f, 0.32f, t))
            val secondary = hsl(h + SECONDARY_SHIFT, s * 0.70f, lerp(0.72f, 0.86f, t))
            val secondaryContainer = hsl(h + SECONDARY_SHIFT, s * 0.62f, lerp(0.24f, 0.32f, t))
            val tertiary = hsl(h + TERTIARY_SHIFT, s * 0.72f, lerp(0.74f, 0.88f, t))
            val tertiaryContainer = hsl(h + TERTIARY_SHIFT, s * 0.62f, lerp(0.26f, 0.34f, t))
            AccentPalette(
                primary = primary,
                onPrimary = readableOn(primary),
                primaryContainer = primaryContainer,
                onPrimaryContainer = readableOn(primaryContainer),
                secondary = secondary,
                onSecondary = readableOn(secondary),
                secondaryContainer = secondaryContainer,
                onSecondaryContainer = readableOn(secondaryContainer),
                tertiary = tertiary,
                onTertiary = readableOn(tertiary),
                tertiaryContainer = tertiaryContainer,
                onTertiaryContainer = readableOn(tertiaryContainer),
            )
        } else {
            val primary = hsl(h, s, lerp(0.32f, 0.46f, t))
            val primaryContainer = hsl(h, s * 0.55f, lerp(0.88f, 0.94f, t))
            val secondary = hsl(h + SECONDARY_SHIFT, s * 0.80f, lerp(0.28f, 0.40f, t))
            val secondaryContainer = hsl(h + SECONDARY_SHIFT, s * 0.50f, lerp(0.88f, 0.94f, t))
            val tertiary = hsl(h + TERTIARY_SHIFT, s * 0.82f, lerp(0.30f, 0.42f, t))
            val tertiaryContainer = hsl(h + TERTIARY_SHIFT, s * 0.50f, lerp(0.88f, 0.94f, t))
            AccentPalette(
                primary = primary,
                onPrimary = readableOn(primary),
                primaryContainer = primaryContainer,
                onPrimaryContainer = readableOn(primaryContainer),
                secondary = secondary,
                onSecondary = readableOn(secondary),
                secondaryContainer = secondaryContainer,
                onSecondaryContainer = readableOn(secondaryContainer),
                tertiary = tertiary,
                onTertiary = readableOn(tertiary),
                tertiaryContainer = tertiaryContainer,
                onTertiaryContainer = readableOn(tertiaryContainer),
            )
        }
    }

    // --- colour primitives -------------------------------------------------

    fun normalizeHue(hue: Float): Float {
        if (hue.isNaN() || hue.isInfinite()) return AppSettings.DEFAULT_ACCENT_HUE
        val wrapped = ((hue % 360f) + 360f) % 360f
        return if (wrapped.isNaN()) 0f else wrapped
    }

    /** HSL -> opaque ARGB. */
    fun hsl(hue: Float, saturation: Float, lightness: Float): Int {
        val h = normalizeHue(hue) / 60f
        val s = saturation.coerceIn(0f, 1f)
        val l = lightness.coerceIn(0f, 1f)
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val x = c * (1f - kotlin.math.abs(h % 2f - 1f))
        val m = l - c / 2f
        val (r1, g1, b1) = when (h.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val r = ((r1 + m) * 255f).roundToByte()
        val g = ((g1 + m) * 255f).roundToByte()
        val b = ((b1 + m) * 255f).roundToByte()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Relative luminance per WCAG 2.x. */
    fun luminance(color: Int): Double {
        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        val r = channel((color shr 16) and 0xFF)
        val g = channel((color shr 8) and 0xFF)
        val b = channel(color and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    fun contrastRatio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val lighter = kotlin.math.max(la, lb)
        val darker = kotlin.math.min(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * White or black, whichever is more readable on [background]. The two
     * extremes are always at least ~4.58:1 apart in luminance, so the result is
     * guaranteed to clear WCAG AA.
     */
    fun readableOn(background: Int): Int {
        val white = contrastRatio(WHITE, background)
        val black = contrastRatio(BLACK, background)
        return if (white >= black) WHITE else BLACK
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float =
        start + (end - start) * fraction

    private fun Float.roundToByte(): Int = (this.coerceIn(0f, 255f) + 0.5f).toInt()
}
