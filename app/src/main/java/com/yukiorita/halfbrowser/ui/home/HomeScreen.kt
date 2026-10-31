package com.yukiorita.halfbrowser.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.yukiorita.halfbrowser.AppContainer

/**
 * Placeholder replaced by the home-screen implementation.
 * Contract: (container, onOpenRequest(url, bookmarkId), onOpenSettings).
 */
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenRequest: (url: String, bookmarkId: String?) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("HomeScreen")
    }
}
