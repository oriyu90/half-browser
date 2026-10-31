package com.yukiorita.halfbrowser.ui.browser

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.yukiorita.halfbrowser.AppContainer
import com.yukiorita.halfbrowser.BuildConfig
import com.yukiorita.halfbrowser.R
import com.yukiorita.halfbrowser.core.Bookmark
import com.yukiorita.halfbrowser.core.NavigationRequest
import com.yukiorita.halfbrowser.core.UrlNormalizer
import com.yukiorita.halfbrowser.ui.components.BookmarkEditorDialog
import com.yukiorita.halfbrowser.ui.settings.SettingsScreen
import kotlin.math.roundToInt

/**
 * Browser screen: a Chromium (Android WebView) surface with a compact chrome bar.
 *
 * The bar always offers "back to home" on the left, the bookmark name (or page
 * title) in the middle and a folded overflow menu on the right, as specified for
 * pages opened from a bookmark.
 */
@Composable
fun BrowserScreen(
    request: NavigationRequest.Open,
    container: AppContainer,
    onHome: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val bookmarks by container.bookmarks.items.collectAsStateWithLifecycle()
    val state = remember { BrowserState() }
    var menuOpen by remember { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var editorTarget by remember { mutableStateOf<EditorTarget?>(null) }
    var confirmChangedCertificate by remember { mutableStateOf(false) }

    val toast: (String) -> Unit = remember(context) {
        { message -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    val trustRevision by container.trustRevision.collectAsStateWithLifecycle()
    var seenTrustRevision by remember { mutableStateOf(trustRevision) }
    LaunchedEffect(trustRevision) {
        if (trustRevision != seenTrustRevision) {
            seenTrustRevision = trustRevision
            state.lastRequestedUrl = state.currentUrl.ifBlank { request.url }
            state.error = null
            // Chromium remembers allowed certificate exceptions per browser
            // profile, so recreating the WebView alone is not enough: explicitly
            // forget them, otherwise a revoked trust keeps loading silently.
            state.webView?.let { webView -> runCatching { webView.clearSslPreferences() } }
            state.generation += 1
        }
    }

    val activeBookmark = request.bookmarkId?.let { id -> bookmarks.firstOrNull { it.id == id } }
    val matchedBookmark = remember(bookmarks, state.currentUrl) {
        val current = UrlNormalizer.canonicalForCompare(state.currentUrl) ?: return@remember null
        bookmarks.firstOrNull { UrlNormalizer.canonicalForCompare(it.url) == current }
    }
    // WebView reports the URL as the title for error pages; do not show that.
    val urlLikeTitle = Regex("^[A-Za-z0-9.-]+(:\\d+)?/.*")
    val visiblePageTitle = state.pageTitle.takeUnless {
        it.startsWith("http://", true) ||
            it.startsWith("https://", true) ||
            urlLikeTitle.matches(it)
    }.orEmpty()
    val title = activeBookmark?.title
        ?: matchedBookmark?.title
        ?: visiblePageTitle.ifBlank { authorityOf(request.url) ?: request.url }
    val host = authorityOf(state.currentUrl) ?: authorityOf(request.url) ?: request.url
    val secure = state.currentUrl.startsWith("https://", ignoreCase = true)
    val darkTheme = when (settings.themeMode) {
        com.yukiorita.halfbrowser.core.ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
        com.yukiorita.halfbrowser.core.ThemeMode.LIGHT -> false
        com.yukiorita.halfbrowser.core.ThemeMode.DARK -> true
    }

    BackHandler {
        when {
            settingsOpen -> settingsOpen = false
            state.webView?.canGoBack() == true -> state.webView?.goBack()
            else -> onHome()
        }
    }

    LaunchedEffect(request.url) {
        val webView = state.webView ?: return@LaunchedEffect
        if (state.lastRequestedUrl != request.url) {
            state.lastRequestedUrl = request.url
            state.error = null
            webView.loadUrl(request.url)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
            BrowserChromeBar(
                title = title,
                subtitle = host,
                secure = secure,
                bookmarked = matchedBookmark != null,
                loading = state.loading,
                progress = state.progress,
                menuOpen = menuOpen,
                onMenuOpenChange = { menuOpen = it },
                onHome = onHome,
                menuContent = {
                    val currentBookmark = activeBookmark ?: matchedBookmark
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (currentBookmark == null) R.string.browser_menu_add_bookmark
                                    else R.string.browser_menu_edit_bookmark,
                                ),
                            )
                        },
                        leadingIcon = {
                            Icon(
                                if (currentBookmark == null) Icons.Filled.BookmarkAdd else Icons.Filled.Edit,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            editorTarget = if (currentBookmark == null) {
                                EditorTarget(null, state.currentUrl.ifBlank { request.url })
                            } else {
                                EditorTarget(currentBookmark, currentBookmark.url)
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_menu_share)) },
                        leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            sharePage(context, state.currentUrl.ifBlank { request.url }, title, toast)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_menu_copy_url)) },
                        leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            copyLink(context, state.currentUrl.ifBlank { request.url }, toast)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_menu_reload)) },
                        leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            state.error = null
                            state.webView?.reload()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_menu_desktop)) },
                        leadingIcon = { Icon(Icons.Filled.DesktopWindows, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            toggleDesktopMode(state, container, context)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_menu_external)) },
                        leadingIcon = { Icon(Icons.Filled.OpenInBrowser, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            val url = state.currentUrl.ifBlank { request.url }
                            if (startExternalView(context, url)) {
                                toast(context.getString(R.string.toast_opened_external))
                            } else {
                                toast(context.getString(R.string.toast_no_browser))
                            }
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_menu_settings)) },
                        leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            settingsOpen = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browser_menu_close)) },
                        leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onHome()
                        },
                    )
                },
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds(),
            ) {
                WebViewHost(
                    request = request,
                    state = state,
                    container = container,
                    context = context,
                    textZoomPercent = (settings.fontScale * 100).roundToInt(),
                    darkTheme = darkTheme,
                    toast = toast,
                    onSettingsRequested = { settingsOpen = true },
                )
                state.error?.let { error ->
                    BrowserErrorPane(
                        error = error,
                        onRetry = {
                            state.error = null
                            if (state.webView == null) {
                                // Renderer death: rebuild the WebView and reload the page.
                                state.generation += 1
                            } else {
                                state.webView?.reload()
                            }
                        },
                    )
                }
            }
        }

        if (settingsOpen) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                SettingsScreen(container = container, onBack = { settingsOpen = false })
            }
        }
    }

    state.ssl?.let { challenge ->
        SslInterstitialDialog(
            challenge = challenge,
            onTrustOnce = {
                challenge.proceed()
                state.ssl = null
            },
            onTrustAlways = {
                // A changed certificate replaces an existing pin: require a second
                // explicit confirmation so a single stray tap cannot downgrade trust.
                if (challenge.changed && !confirmChangedCertificate) {
                    confirmChangedCertificate = true
                } else {
                    val fingerprint = challenge.fingerprintSha256
                    if (fingerprint != null) {
                        container.trust.pin(
                            host = challenge.host,
                            port = challenge.port,
                            fingerprintSha256 = fingerprint,
                            subject = challenge.subject,
                            issuer = challenge.issuer,
                        )
                        toast(context.getString(R.string.ssl_trusted_toast, challenge.host))
                    }
                    challenge.proceed()
                    state.ssl = null
                }
            },
            onCancel = {
                challenge.cancel()
                state.ssl = null
                // Cancelling leaves the WebView on a blank page; show the standard
                // error pane with a retry action instead of silent white.
                state.error = BrowserError(null, challenge.host, kind = ErrorKind.CERT)
            },
        )
    }

    state.auth?.let { challenge ->
        AuthPromptDialog(
            challenge = challenge,
            persistentStorage = container.credentials.isPersistent,
            allowSave = challenge.allowSave,
            onSignIn = { username, password, save ->
                challenge.signIn(username, password, save)
                state.auth = null
            },
            onCancel = {
                challenge.cancel()
                state.auth = null
                // Do not leave the raw 401 response on screen; offer a retry.
                state.pendingAuthErrorUrl = state.currentUrl.ifBlank { request.url }
                state.error = BrowserError(
                    description = null,
                    failingUrl = state.currentUrl.ifBlank { request.url },
                    kind = ErrorKind.AUTH,
                )
            },
        )
    }

    if (confirmChangedCertificate) {
        val challenge = state.ssl
        AlertDialog(
            onDismissRequest = { confirmChangedCertificate = false },
            title = { Text(stringResource(R.string.ssl_changed_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.ssl_changed_confirm_message,
                        challenge?.host.orEmpty(),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmChangedCertificate = false
                        val fingerprint = challenge?.fingerprintSha256
                        if (challenge != null && fingerprint != null) {
                            container.trust.pin(
                                host = challenge.host,
                                port = challenge.port,
                                fingerprintSha256 = fingerprint,
                                subject = challenge.subject,
                                issuer = challenge.issuer,
                            )
                            challenge.proceed()
                            state.ssl = null
                            toast(context.getString(R.string.ssl_trusted_toast, challenge.host))
                        }
                    },
                ) {
                    Text(stringResource(R.string.ssl_changed_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmChangedCertificate = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    editorTarget?.let { target ->
        BookmarkEditorDialog(
            initial = target.bookmark,
            initialUrl = target.url,
            onDismiss = { editorTarget = null },
            onSave = { newTitle, newUrl, glyph ->
                val existing = container.bookmarks.findByUrl(newUrl)
                if (existing != null && existing.id != target.bookmark?.id) {
                    toast(context.getString(R.string.bookmark_duplicate))
                } else if (target.bookmark == null) {
                    container.bookmarks.add(newTitle, newUrl, glyph)
                    toast(context.getString(R.string.bookmark_saved))
                } else {
                    container.bookmarks.update(
                        target.bookmark.copy(title = newTitle, url = newUrl, glyph = glyph),
                    )
                    toast(context.getString(R.string.bookmark_saved))
                }
                editorTarget = null
            },
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            state.ssl?.cancel()
            state.auth?.cancel()
            state.webView?.let { webView ->
                runCatching {
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    webView.destroy()
                }
            }
            state.webView = null
        }
    }
}

private data class EditorTarget(val bookmark: Bookmark?, val url: String)

@Composable
private fun BrowserChromeBar(
    title: String,
    subtitle: String,
    secure: Boolean,
    bookmarked: Boolean,
    loading: Boolean,
    progress: Int,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onHome: () -> Unit,
    menuContent: @Composable () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column(modifier = Modifier.statusBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onHome) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.browser_back_home),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (secure) Icons.Filled.Lock else Icons.Filled.Language,
                            contentDescription = stringResource(R.string.browser_security),
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (bookmarked) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "★",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
                Box {
                    IconButton(onClick = { onMenuOpenChange(true) }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.browser_more),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
                        menuContent()
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
            ) {
                if (loading && progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun WebViewHost(
    request: NavigationRequest.Open,
    state: BrowserState,
    container: AppContainer,
    context: Context,
    textZoomPercent: Int,
    darkTheme: Boolean,
    toast: (String) -> Unit,
    onSettingsRequested: () -> Unit,
) {
    // Page surface stays white, like desktop browsers. Painting it with the app's
    // dark surface made ordinary pages (black text, no background) unreadable.
    val backgroundColor = android.graphics.Color.WHITE
    key(state.generation) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            ensureWebViewDebugging()
            val webView = WebView(ctx).apply {
                // Do not set layoutParams: AndroidView owns measurement/placement.
                // MATCH_PARENT here makes the native view span the whole screen and
                // cover the Compose chrome bar.
                setBackgroundColor(backgroundColor)
                configureWebView(this, textZoomPercent, darkTheme)
                state.defaultUserAgent = this.settings.userAgentString.orEmpty()
                webViewClient = HalfBrowserWebViewClient(
                    context = context,
                    state = state,
                    container = container,
                    openExternalApp = { url ->
                        val opened = startExternalView(context, url)
                        if (!opened) toast(context.getString(R.string.toast_no_browser))
                        opened
                    },
                    showToast = toast,
                )
                webChromeClient = HalfBrowserChromeClient(state)
                setDownloadListener(DownloadListener { url, _, contentDisposition, mimeType, _ ->
                    val started = enqueueDownload(context, url, contentDisposition, mimeType)
                    toast(
                        context.getString(
                            if (started) R.string.toast_download_started else R.string.toast_download_failed,
                        ),
                    )
                })
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                state.webView = this
                val initialUrl = state.lastRequestedUrl.ifBlank { request.url }
                state.lastRequestedUrl = initialUrl
                loadUrl(initialUrl)
            }
            webView
        },
        update = { },
    )
    }
}

@Composable
private fun BrowserErrorPane(error: BrowserError, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(
                    when (error.kind) {
                        ErrorKind.AUTH -> R.string.browser_error_auth_title
                        ErrorKind.CERT -> R.string.ssl_title
                        ErrorKind.NETWORK -> R.string.browser_error_title
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    when (error.kind) {
                        ErrorKind.AUTH -> R.string.browser_error_auth_body
                        ErrorKind.CERT -> R.string.browser_error_cert_body
                        ErrorKind.NETWORK -> R.string.browser_error_body
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            error.failingUrl?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            error.description?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}

private fun configureWebView(webView: WebView, textZoomPercent: Int, darkTheme: Boolean) {
    val s = webView.settings
    // Security-relevant settings first, each applied on its own so one failing
    // setter cannot leave file access enabled.
    runCatching { s.allowFileAccess = false }
    runCatching { s.allowContentAccess = false }
    runCatching { s.javaScriptCanOpenWindowsAutomatically = false }
    runCatching { s.setSupportMultipleWindows(false) }
    runCatching { s.mediaPlaybackRequiresUserGesture = true }
    runCatching { s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE }
    runCatching { s.domStorageEnabled = true }
    runCatching { s.databaseEnabled = true }
    runCatching { s.javaScriptEnabled = true }
    runCatching { s.setSupportZoom(true) }
    runCatching { s.builtInZoomControls = true }
    runCatching { s.displayZoomControls = false }
    runCatching { s.loadWithOverviewMode = true }
    runCatching { s.useWideViewPort = true }
    runCatching { s.cacheMode = WebSettings.LOAD_DEFAULT }
    runCatching { s.textZoom = textZoomPercent.coerceIn(50, 200) }
    runCatching {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            s.safeBrowsingEnabled = true
        }
    }
    runCatching { webView.setBackgroundColor(android.graphics.Color.WHITE) }
    runCatching { webView.isHorizontalScrollBarEnabled = false }
    // Algorithmic darkening lets dark mode apply to web content too. When the
    // feature is absent the page simply stays light, which is readable.
    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
        runCatching {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(s, darkTheme)
        }
    }
}

private var debuggingConfigured = false

private fun ensureWebViewDebugging() {
    if (debuggingConfigured) return
    debuggingConfigured = true
    runCatching { WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG) }
}

private fun toggleDesktopMode(state: BrowserState, container: AppContainer, context: Context) {
    val webView = state.webView ?: return
    state.desktopMode = !state.desktopMode
    val base = state.defaultUserAgent.ifBlank { webView.settings.userAgentString.orEmpty() }
    val target = if (state.desktopMode) {
        base.replace(" Mobile", "").replace("Android ", "")
    } else {
        base
    }
    runCatching { webView.settings.userAgentString = target }
    webView.reload()
    Toast.makeText(
        context,
        context.getString(
            if (state.desktopMode) R.string.toast_desktop_enabled else R.string.toast_desktop_disabled,
        ),
        Toast.LENGTH_SHORT,
    ).show()
}

private fun copyLink(context: Context, url: String, toast: (String) -> Unit) {
    if (url.isBlank()) {
        toast(context.getString(R.string.toast_no_url))
        return
    }
    runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("url", url))
        toast(context.getString(R.string.toast_link_copied))
    }.onFailure { toast(context.getString(R.string.toast_share_failed)) }
}

private fun sharePage(context: Context, url: String, title: String, toast: (String) -> Unit) {
    if (url.isBlank()) {
        toast(context.getString(R.string.toast_no_url))
        return
    }
    runCatching {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            if (title.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { toast(context.getString(R.string.toast_share_failed)) }
}

private fun enqueueDownload(
    context: Context,
    url: String,
    contentDisposition: String?,
    mimeType: String?,
): Boolean = runCatching {
    if (!httpsOrHttp(url)) return false
    val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
    val request = DownloadManager.Request(Uri.parse(url)).apply {
        setMimeType(mimeType)
        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        setTitle(fileName)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        } else {
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        }
    }
    val manager = context.getSystemService(DownloadManager::class.java) ?: return false
    manager.enqueue(request)
    true
}.getOrDefault(false)
