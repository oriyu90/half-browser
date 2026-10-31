package com.yukiorita.halfbrowser.ui.browser

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.yukiorita.halfbrowser.AppContainer
import com.yukiorita.halfbrowser.core.NavigationRequest

/**
 * Placeholder replaced by the WebView browser implementation.
 * Contract: (request, container, onHome, onOpenSettings).
 */
@Composable
fun BrowserScreen(
    request: NavigationRequest.Open,
    container: AppContainer,
    onHome: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("BrowserScreen")
    }
}
