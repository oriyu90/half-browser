package com.yukiorita.halfbrowser.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.yukiorita.halfbrowser.AppContainer

/**
 * Placeholder replaced by the settings implementation.
 * Contract: (container, onBack).
 */
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("SettingsScreen")
    }
}
