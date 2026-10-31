package com.yukiorita.halfbrowser.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * Applies the user's interface/text scale without touching the system settings.
 *
 * The density is scaled for dp sizes (tile size, spacing, controls) and the
 * fontScale is scaled separately, so "make everything bigger" and "make text
 * bigger" remain independent controls. Both are clamped by the settings codec.
 */
@Composable
fun AppScale(
    uiScale: Float,
    fontScale: Float,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val safeUi = uiScale.coerceIn(0.8f, 1.4f)
    val safeFont = fontScale.coerceIn(0.8f, 1.5f)
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = density.density * safeUi,
            fontScale = density.fontScale * safeFont,
        ),
    ) {
        content()
    }
}
