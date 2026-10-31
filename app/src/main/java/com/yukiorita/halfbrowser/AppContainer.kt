package com.yukiorita.halfbrowser

import android.content.Context
import com.yukiorita.halfbrowser.core.NavigationRequest
import com.yukiorita.halfbrowser.data.BookmarkRepository
import com.yukiorita.halfbrowser.data.CredentialStore
import com.yukiorita.halfbrowser.data.SettingsRepository
import com.yukiorita.halfbrowser.data.TrustRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NavEvent { HOME, BROWSER, SETTINGS }

/**
 * Process-wide object graph. Small and explicit on purpose: the app has no DI
 * framework, and every repository is lazily created once from the Application.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val bookmarks: BookmarkRepository by lazy { BookmarkRepository(appContext) }
    val settings: SettingsRepository by lazy { SettingsRepository(appContext) }
    val trust: TrustRepository by lazy { TrustRepository(appContext) }
    val credentials: CredentialStore by lazy { CredentialStore(appContext) }

    private val _openRequest = MutableStateFlow<NavigationRequest.Open?>(null)
    val openRequest: StateFlow<NavigationRequest.Open?> = _openRequest.asStateFlow()

    private val _navEvents = MutableSharedFlow<NavEvent>(extraBufferCapacity = 8)
    val navEvents: SharedFlow<NavEvent> = _navEvents.asSharedFlow()

    fun requestOpen(
        url: String,
        bookmarkId: String? = null,
        fromExternalApp: Boolean = false,
    ) {
        _openRequest.value = NavigationRequest.Open(url, bookmarkId, fromExternalApp)
        _navEvents.tryEmit(NavEvent.BROWSER)
    }

    fun requestHome() {
        _navEvents.tryEmit(NavEvent.HOME)
    }

    fun requestSettings() {
        _navEvents.tryEmit(NavEvent.SETTINGS)
    }
}
