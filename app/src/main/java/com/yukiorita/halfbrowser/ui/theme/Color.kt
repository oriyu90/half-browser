package com.yukiorita.halfbrowser.ui.theme

import androidx.compose.ui.graphics.Color

// Brand palette: a deep "browser chrome" blue with a cyan accent. The primary
// family is only a fallback now: the live palette comes from the user's accent
// settings (see core/AccentMath.kt). Light surface values are kept neutral so a
// custom accent never fights with the background.
val LightPrimary = Color(0xFF0F4CD9)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFDCE6FF)
val LightOnPrimaryContainer = Color(0xFF00174A)

val LightSecondary = Color(0xFF0E7490)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFCFF5FF)
val LightOnSecondaryContainer = Color(0xFF00343F)

val LightTertiary = Color(0xFF6D28D9)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFEBDDFF)
val LightOnTertiaryContainer = Color(0xFF25005F)

val LightBackground = Color(0xFFF7F9FC)
val LightOnBackground = Color(0xFF101828)
val LightSurface = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF101828)
val LightSurfaceVariant = Color(0xFFE3E8F0)
val LightOnSurfaceVariant = Color(0xFF445065)
val LightOutline = Color(0xFF6D7A8F)
val LightOutlineVariant = Color(0xFFC3CBD9)

val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
val LightSurfaceContainerLow = Color(0xFFF2F5FA)
val LightSurfaceContainer = Color(0xFFECF1F8)
val LightSurfaceContainerHigh = Color(0xFFE6EBF3)
val LightSurfaceContainerHighest = Color(0xFFDFE5EF)

// Dark mode is deliberately black-on-white: the background and both surface
// levels sit on pure #000000, containers are near-black greys, and every text
// role is pure white. This is a readability choice, not an aesthetic one: an
// OLED panel shows true black, and white text on it clears WCAG AAA.
val DarkBackground = Color(0xFF000000)
val DarkOnBackground = Color(0xFFFFFFFF)
val DarkSurface = Color(0xFF000000)
val DarkOnSurface = Color(0xFFFFFFFF)
val DarkSurfaceVariant = Color(0xFF1C1C1E)
val DarkOnSurfaceVariant = Color(0xFFC7C7CC)
val DarkOutline = Color(0xFF8E8E93)
val DarkOutlineVariant = Color(0xFF38383A)

val DarkSurfaceContainerLowest = Color(0xFF000000)
val DarkSurfaceContainerLow = Color(0xFF0C0C0D)
val DarkSurfaceContainer = Color(0xFF141415)
val DarkSurfaceContainerHigh = Color(0xFF1C1C1E)
val DarkSurfaceContainerHighest = Color(0xFF242426)

val BrandCyan = Color(0xFF38BDF8)
val DangerRed = Color(0xFFB3261E)
val WarningAmber = Color(0xFFB45309)
