package com.yukiorita.halfbrowser

import android.content.Context
import com.yukiorita.halfbrowser.core.NavigationRequest
import com.yukiorita.halfbrowser.data.BookmarkRepository
import com.yukiorita.halfbrowser.data.CredentialStore
import com.yukiorita.halfbrowser.data.SettingsRepository
import com.yukiorita.halfbrowser.data.SiteFetcher
import com.yukiorita.halfbrowser.data.SiteWatchRepository
import com.yukiorita.halfbrowser.data.TrustRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

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
    val siteWatch: SiteWatchRepository by lazy { SiteWatchRepository(appContext) }
    val siteFetcher: SiteFetcher by lazy { SiteFetcher(trust, credentials) }

    private val _trustRevision = MutableStateFlow(0)
    /** Incremented when the user trusts/revokes a certificate in settings. */
    val trustRevision: StateFlow<Int> = _trustRevision.asStateFlow()

    private val _openRequest = MutableStateFlow<NavigationRequest.Open?>(null)
    val openRequest: StateFlow<NavigationRequest.Open?> = _openRequest.asStateFlow()

    // A Channel (not SharedFlow) so a navigation requested before Compose starts
    // collecting (a cold start from an external link) is buffered, not dropped.
    private val _navEvents = Channel<NavEvent>(Channel.BUFFERED)
    val navEvents: Flow<NavEvent> = _navEvents.receiveAsFlow()

    fun requestOpen(
        url: String,
        bookmarkId: String? = null,
        fromExternalApp: Boolean = false,
    ) {
        _openRequest.value = NavigationRequest.Open(url, bookmarkId, fromExternalApp)
        _navEvents.trySend(NavEvent.BROWSER)
    }

    fun notifyTrustChanged() {
        _trustRevision.value += 1
    }

    fun requestHome() {
        _navEvents.trySend(NavEvent.HOME)
    }

    fun requestSettings() {
        _navEvents.trySend(NavEvent.SETTINGS)
    }
}
