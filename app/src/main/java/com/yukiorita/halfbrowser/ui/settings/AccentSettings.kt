package com.yukiorita.halfbrowser.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yukiorita.halfbrowser.R
import com.yukiorita.halfbrowser.core.AppSettings
import com.yukiorita.halfbrowser.core.AccentMath
import kotlin.math.roundToInt

private data class AccentPreset(
    val hue: Float,
    val saturation: Float,
    val tone: Float,
)

private val PRESETS = listOf(
    AccentPreset(222f, 0.86f, 0.5f), // brand blue
    AccentPreset(190f, 0.72f, 0.5f), // teal
    AccentPreset(146f, 0.62f, 0.5f), // green
    AccentPreset(38f, 0.92f, 0.5f), // amber
    AccentPreset(18f, 0.86f, 0.5f), // orange
    AccentPreset(345f, 0.74f, 0.5f), // rose
    AccentPreset(278f, 0.66f, 0.5f), // violet
    AccentPreset(215f, 0.16f, 0.45f), // graphite
)

/**
 * Fine-grained accent controls: hue, saturation and brightness, plus presets.
 * Every colour is derived by [AccentMath], so text contrast can never break.
 */
@Composable
fun AccentSettings(
    settings: AppSettings,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
) {
    val hue = settings.accentHue
    val saturation = settings.accentSaturation
    val tone = settings.accentTone

    fun apply(newHue: Float, newSaturation: Float, newTone: Float) {
        // A custom accent and wallpaper colours are mutually exclusive: the
        // wallpaper palette would silently override the user's choice.
        onUpdate {
            it.copy(
                accentHue = newHue,
                accentSaturation = newSaturation,
                accentTone = newTone,
                dynamicColor = false,
            )
        }
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    Text(
        text = stringResource(R.string.settings_accent),
        style = MaterialTheme.typography.bodyMedium,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PRESETS.forEach { preset ->
            val selected = kotlin.math.abs(preset.hue - hue) < 0.5f &&
                kotlin.math.abs(preset.saturation - saturation) < 0.02f
            val swatch = AccentMath.palette(preset.hue, preset.saturation, preset.tone, dark = false)
            val ringColor = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                Color.Transparent
            }
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .border(2.dp, ringColor, CircleShape)
                    .padding(3.dp)
                    .background(Color(swatch.primary), CircleShape)
                    .clickable { apply(preset.hue, preset.saturation, preset.tone) },
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PreviewTile(
            label = stringResource(R.string.settings_accent_preview_primary),
            background = MaterialTheme.colorScheme.primary,
            foreground = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.weight(1f),
        )
        PreviewTile(
            label = stringResource(R.string.settings_accent_preview_container),
            background = MaterialTheme.colorScheme.primaryContainer,
            foreground = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.weight(1f),
        )
        PreviewTile(
            label = stringResource(R.string.settings_accent_preview_tertiary),
            background = MaterialTheme.colorScheme.tertiary,
            foreground = MaterialTheme.colorScheme.onTertiary,
            modifier = Modifier.weight(1f),
        )
    }

    AccentSlider(
        label = stringResource(R.string.settings_accent_hue),
        valueLabel = stringResource(R.string.settings_accent_degrees, hue.roundToInt()),
        initial = hue,
        range = 0f..360f,
        steps = 71,
    ) { apply(it, saturation, tone) }

    AccentSlider(
        label = stringResource(R.string.settings_accent_saturation),
        valueLabel = stringResource(R.string.settings_accent_percent, (saturation * 100).roundToInt()),
        initial = saturation * 100f,
        range = 15f..100f,
        steps = 16,
    ) { apply(hue, it / 100f, tone) }

    AccentSlider(
        label = stringResource(R.string.settings_accent_brightness),
        valueLabel = stringResource(R.string.settings_accent_percent, (tone * 100).roundToInt()),
        initial = tone * 100f,
        range = 0f..100f,
        steps = 19,
    ) { apply(hue, saturation, it / 100f) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = { apply(AccentMath.normalizeHue(AppSettings.DEFAULT_ACCENT_HUE), AppSettings.DEFAULT_ACCENT_SATURATION, AppSettings.DEFAULT_ACCENT_TONE) },
        ) {
            Text(stringResource(R.string.settings_accent_reset))
        }
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.settings_accent_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PreviewTile(
    label: String,
    background: Color,
    foreground: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = background,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "Aa",
                style = MaterialTheme.typography.titleMedium,
                color = foreground,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = foreground,
            )
        }
    }
}

@Composable
private fun AccentSlider(
    label: String,
    valueLabel: String,
    initial: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onCommit: (Float) -> Unit,
) {
    var value by remember(initial) { mutableFloatStateOf(initial) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onCommit(value) },
            valueRange = range,
            steps = steps,
        )
    }
}
