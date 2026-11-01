package com.yukiorita.halfbrowser.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.yukiorita.halfbrowser.AppContainer
import com.yukiorita.halfbrowser.NavEvent
import com.yukiorita.halfbrowser.core.NavigationRequest
import com.yukiorita.halfbrowser.ui.browser.BrowserScreen
import com.yukiorita.halfbrowser.ui.home.HomeScreen
import com.yukiorita.halfbrowser.ui.settings.SettingsScreen
import com.yukiorita.halfbrowser.ui.theme.HalfBrowserTheme

private object Routes {
    const val HOME = "home"
    const val BROWSER = "browser"
    const val SETTINGS = "settings"
}

@Composable
fun HalfBrowserRoot(container: AppContainer) {
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val openRequest by container.openRequest.collectAsStateWithLifecycle()

    HalfBrowserTheme(
        themeMode = settings.themeMode,
        dynamicColor = settings.dynamicColor,
        accentHue = settings.accentHue,
        accentSaturation = settings.accentSaturation,
        accentTone = settings.accentTone,
    ) {
        AppScale(uiScale = settings.uiScale, fontScale = settings.fontScale) {
            val nav = rememberNavController()

            LaunchedEffect(container) {
                container.navEvents.collect { event ->
                    when (event) {
                        NavEvent.HOME -> nav.navigate(Routes.HOME) {
                            popUpTo(Routes.HOME) { inclusive = false }
                            launchSingleTop = true
                        }
                        NavEvent.BROWSER -> nav.navigate(Routes.BROWSER) {
                            launchSingleTop = true
                        }
                        NavEvent.SETTINGS -> nav.navigate(Routes.SETTINGS) {
                            launchSingleTop = true
                        }
                    }
                }
            }

            NavHost(navController = nav, startDestination = Routes.HOME) {
                composable(Routes.HOME) {
                    HomeScreen(
                        container = container,
                        onOpenRequest = { url, bookmarkId -> container.requestOpen(url, bookmarkId) },
                        onOpenSettings = { container.requestSettings() },
                    )
                }
                composable(Routes.BROWSER) {
                    val request = openRequest
                    if (request == null) {
                        // Defensive: a browser route with no request would be unreachable
                        // in normal use; fall back to the home screen instead of crashing.
                        LaunchedEffect(Unit) { container.requestHome() }
                    } else {
                        BrowserScreen(
                            request = request,
                            container = container,
                            onHome = { container.requestHome() },
                            onOpenSettings = { container.requestSettings() },
                        )
                    }
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        container = container,
                        onBack = { nav.popBackStack() },
                    )
                }
            }
        }
    }
}
