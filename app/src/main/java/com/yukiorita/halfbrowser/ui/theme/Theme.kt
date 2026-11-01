package com.yukiorita.halfbrowser.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.yukiorita.halfbrowser.core.AccentMath
import com.yukiorita.halfbrowser.core.AppSettings
import com.yukiorita.halfbrowser.core.ThemeMode

private fun accentScheme(dark: Boolean, hue: Float, saturation: Float, tone: Float): ColorScheme {
    val p = AccentMath.palette(hue, saturation, tone, dark)
    return if (dark) {
        darkColorScheme(
            primary = Color(p.primary),
            onPrimary = Color(p.onPrimary),
            primaryContainer = Color(p.primaryContainer),
            onPrimaryContainer = Color(p.onPrimaryContainer),
            secondary = Color(p.secondary),
            onSecondary = Color(p.onSecondary),
            secondaryContainer = Color(p.secondaryContainer),
            onSecondaryContainer = Color(p.onSecondaryContainer),
            tertiary = Color(p.tertiary),
            onTertiary = Color(p.onTertiary),
            tertiaryContainer = Color(p.tertiaryContainer),
            onTertiaryContainer = Color(p.onTertiaryContainer),
            background = DarkBackground,
            onBackground = DarkOnBackground,
            surface = DarkSurface,
            onSurface = DarkOnSurface,
            surfaceVariant = DarkSurfaceVariant,
            onSurfaceVariant = DarkOnSurfaceVariant,
            outline = DarkOutline,
            outlineVariant = DarkOutlineVariant,
            surfaceContainerLowest = DarkSurfaceContainerLowest,
            surfaceContainerLow = DarkSurfaceContainerLow,
            surfaceContainer = DarkSurfaceContainer,
            surfaceContainerHigh = DarkSurfaceContainerHigh,
            surfaceContainerHighest = DarkSurfaceContainerHighest,
        )
    } else {
        lightColorScheme(
            primary = Color(p.primary),
            onPrimary = Color(p.onPrimary),
            primaryContainer = Color(p.primaryContainer),
            onPrimaryContainer = Color(p.onPrimaryContainer),
            secondary = Color(p.secondary),
            onSecondary = Color(p.onSecondary),
            secondaryContainer = Color(p.secondaryContainer),
            onSecondaryContainer = Color(p.onSecondaryContainer),
            tertiary = Color(p.tertiary),
            onTertiary = Color(p.onTertiary),
            tertiaryContainer = Color(p.tertiaryContainer),
            onTertiaryContainer = Color(p.onTertiaryContainer),
            background = LightBackground,
            onBackground = LightOnBackground,
            surface = LightSurface,
            onSurface = LightOnSurface,
            surfaceVariant = LightSurfaceVariant,
            onSurfaceVariant = LightOnSurfaceVariant,
            outline = LightOutline,
            outlineVariant = LightOutlineVariant,
            surfaceContainerLowest = LightSurfaceContainerLowest,
            surfaceContainerLow = LightSurfaceContainerLow,
            surfaceContainer = LightSurfaceContainer,
            surfaceContainerHigh = LightSurfaceContainerHigh,
            surfaceContainerHighest = LightSurfaceContainerHighest,
        )
    }
}

/**
 * Wallpaper colours keep their accent family, but dark mode still forces black
 * surfaces and white text so the "black background, white text" contract holds
 * even when the device palette is colourful.
 */
private fun withBlackSurfaces(scheme: ColorScheme): ColorScheme = scheme.copy(
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
)

@Composable
fun HalfBrowserTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    accentHue: Float = AppSettings.DEFAULT_ACCENT_HUE,
    accentSaturation: Float = AppSettings.DEFAULT_ACCENT_SATURATION,
    accentTone: Float = AppSettings.DEFAULT_ACCENT_TONE,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val accent = accentScheme(dark, accentHue, accentSaturation, accentTone)
    val useDynamic = dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colors = when {
        useDynamic && dark -> withBlackSurfaces(dynamicDarkColorScheme(context))
        useDynamic -> dynamicLightColorScheme(context)
        else -> accent
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
