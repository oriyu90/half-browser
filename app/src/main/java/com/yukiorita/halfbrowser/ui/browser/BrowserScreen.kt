package com.yukiorita.halfbrowser.ui.browser

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.os.Build
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.yukiorita.halfbrowser.AppContainer
import com.yukiorita.halfbrowser.BuildConfig
import com.yukiorita.halfbrowser.R
import com.yukiorita.halfbrowser.core.Bookmark
import com.yukiorita.halfbrowser.core.NavigationRequest
import com.yukiorita.halfbrowser.core.SitePermissionKind
import com.yukiorita.halfbrowser.core.SitePermissions
import com.yukiorita.halfbrowser.notifications.WebNotificationBridge
import com.yukiorita.halfbrowser.service.KeepAliveService
import com.yukiorita.halfbrowser.core.UrlNormalizer
import com.yukiorita.halfbrowser.ui.components.BookmarkEditorDialog
import com.yukiorita.halfbrowser.ui.settings.SettingsScreen
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/**
 * Browser screen: a Chromium (Android WebView) surface with a compact chrome bar.
 *
 * The bar always offers "back to home" on the left, the bookmark name (or page
 * title) in the middle and a folded overflow menu on the right, as specified for
 * pages opened from a bookmark.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    request: NavigationRequest.Open,
    container: AppContainer,
    onHome: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val toast: (String) -> Unit = remember(context) {
        { message -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val bookmarks by container.bookmarks.items.collectAsStateWithLifecycle()
    val state = remember { BrowserState() }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    // Do not burn CPU/GPU on a page nobody is looking at, and let
                    // the WebView drop caches while the app is in the background.
                    // With background notification delivery on, the page has to
                    // keep running: freezing it would silence the site.
                    if (!container.settings.current().backgroundWebNotifications) {
                        state.webView?.pauseTimers()
                    }
                    @Suppress("DEPRECATION")
                    state.webView?.freeMemory()
                }
                Lifecycle.Event.ON_START -> state.webView?.resumeTimers()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var menuOpen by remember { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var editorTarget by remember { mutableStateOf<EditorTarget?>(null) }
    var confirmChangedCertificate by remember { mutableStateOf(false) }
    var pendingFileRequest by remember { mutableStateOf<FileChooserRequest?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var pendingWebPermission by remember { mutableStateOf<WebPermissionRequest?>(null) }
    var pendingGeoRequest by remember { mutableStateOf<GeoPermissionRequest?>(null) }
    var pendingCameraRequest by remember { mutableStateOf<FileChooserRequest?>(null) }
    var pendingFormLogin by remember { mutableStateOf<FormLoginTarget?>(null) }
    var bookmarksOpen by remember { mutableStateOf(false) }
    var pendingNotificationPrompt by remember { mutableStateOf<NotifyPrompt?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val request = pendingFileRequest ?: return@rememberLauncherForActivityResult
        val cameraUri = pendingCameraUri
        pendingFileRequest = null
        pendingCameraUri = null
        val uris: Array<Uri>? = if (result.resultCode == Activity.RESULT_OK) {
            if (cameraUri != null) {
                arrayOf(cameraUri)
            } else {
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            }
        } else {
            null
        }
        request.deliver(uris)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* The site keeps its grant; the OS answer only gates delivery. */ }

    val webPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val request = pendingWebPermission ?: return@rememberLauncherForActivityResult
        pendingWebPermission = null
        if (result.values.all { it }) {
            request.grant()
        } else {
            request.deny()
            toast(context.getString(R.string.web_permission_android_denied))
        }
    }

    fun newCameraUri(): Uri? = runCatching {
        val directory = File(context.cacheDir, "camera").apply { mkdirs() }
        val file = File.createTempFile("capture_", ".jpg", directory)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.onFailure { Log.w("HalfBrowser", "camera uri failed", it) }.getOrNull()

    fun startFilePicker(request: FileChooserRequest) {
        state.fileChooser = null
        pendingFileRequest = request
        runCatching { filePickerLauncher.launch(request.params.createIntent()) }
            .onFailure {
                pendingFileRequest = null
                request.deliver(null)
                toast(context.getString(R.string.file_chooser_unavailable))
            }
    }

    fun launchCameraIntent(request: FileChooserRequest) {
        val uri = newCameraUri()
        if (uri == null) {
            state.fileChooser = null
            request.deliver(null)
            toast(context.getString(R.string.file_chooser_unavailable))
            return
        }
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        state.fileChooser = null
        pendingCameraUri = uri
        pendingFileRequest = request
        runCatching { filePickerLauncher.launch(intent) }
            .onFailure {
                Log.w("HalfBrowser", "camera intent failed", it)
                pendingCameraUri = null
                pendingFileRequest = null
                request.deliver(null)
                toast(context.getString(R.string.file_chooser_unavailable))
            }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val request = pendingCameraRequest
        pendingCameraRequest = null
        if (granted && request != null) {
            launchCameraIntent(request)
        } else {
            request?.deliver(null)
            state.fileChooser = null
            toast(context.getString(R.string.file_chooser_unavailable))
        }
    }

    fun startCamera(request: FileChooserRequest) {
        // Declaring CAMERA makes Android require the runtime grant before an
        // ACTION_IMAGE_CAPTURE intent may be launched.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            launchCameraIntent(request)
        } else {
            pendingCameraRequest = request
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val geoPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val request = pendingGeoRequest ?: return@rememberLauncherForActivityResult
        pendingGeoRequest = null
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        request.resolve(granted)
    }

    fun requestAndroidWebPermissions(request: WebPermissionRequest) {
        val needed = buildList {
            if (request.wantsCamera) add(Manifest.permission.CAMERA)
            if (request.wantsMicrophone) add(Manifest.permission.RECORD_AUDIO)
        }.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            request.grant()
        } else {
            pendingWebPermission = request
            webPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    /** Stores the user's answer so the site is not asked again until revoked. */
    fun rememberWebPermission(request: WebPermissionRequest, allowed: Boolean) {
        request.kinds.forEach { kind ->
            container.sitePermissions.record(request.origin, kind, allowed)
        }
    }

    fun requestLocationPermission(request: GeoPermissionRequest) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            request.resolve(true)
        } else {
            pendingGeoRequest = request
            geoPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
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
    // A page opened from a bookmark gets the compact, single-line chrome bar.
    val compactChrome = activeBookmark != null
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

    // The keep-alive service only exists while a browser screen is on screen and
    // the user asked for immediate web notifications.
    LaunchedEffect(settings.backgroundWebNotifications) {
        if (settings.backgroundWebNotifications) {
            KeepAliveService.start(context)
        } else {
            KeepAliveService.stop(context)
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
                .navigationBarsPadding()
                .imePadding(),
        ) {
            BrowserChromeBar(
                title = title,
                subtitle = host,
                secure = secure,
                bookmarked = matchedBookmark != null,
                compact = compactChrome,
                loading = state.loading,
                progress = state.progress,
                menuOpen = menuOpen,
                onMenuOpenChange = { menuOpen = it },
                onHome = onHome,
                onReload = {
                    state.error = null
                    if (state.webView == null) state.generation += 1 else state.webView?.reload()
                },
                onBookmarks = { bookmarksOpen = true },
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
                    if ((state.currentUrl.ifBlank { request.url }).startsWith("https://")) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.browser_menu_save_login)) },
                            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                captureFormLogin(
                                    webView = state.webView,
                                    pageUrl = state.currentUrl.ifBlank { request.url },
                                    onFound = { pendingFormLogin = it },
                                    onMissing = {
                                        toast(context.getString(R.string.form_login_not_found))
                                    },
                                )
                            },
                        )
                    }
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
                    textZoomPercent = webTextZoomPercent(settings),
                    darkTheme = darkTheme,
                    toast = toast,
                    onSettingsRequested = { settingsOpen = true },
                    onNotificationPrompt = { origin, decide ->
                        pendingNotificationPrompt = NotifyPrompt(origin, decide)
                    },
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

    if (bookmarksOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { bookmarksOpen = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.bookmarks_sheet_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                if (bookmarks.isEmpty()) {
                    Text(
                        text = stringResource(R.string.bookmarks_sheet_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(bookmarks, key = { it.id }) { bookmark ->
                            val icon = remember(bookmark.icon) {
                                container.favicons.load(bookmark)?.asImageBitmap()
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        bookmarksOpen = false
                                        container.requestOpen(bookmark.url, bookmark.id)
                                    }
                                    .padding(vertical = 8.dp, horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (icon != null) {
                                                Color.White
                                            } else {
                                                Color(
                                                    com.yukiorita.halfbrowser.core.BookmarkIcons
                                                        .colorFor(bookmark.url, bookmark.colorSeed),
                                                )
                                            },
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (icon != null) {
                                        Image(
                                            bitmap = icon,
                                            contentDescription = null,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(4.dp),
                                        )
                                    } else {
                                        Text(
                                            text = com.yukiorita.halfbrowser.core.BookmarkIcons
                                                .glyphFor(bookmark.title, bookmark.url, bookmark.glyph),
                                            color = Color.White,
                                            fontSize = 15.sp,
                                        )
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = com.yukiorita.halfbrowser.core.BookmarkIcons
                                            .normalizedTitleOrFallback(bookmark.title, bookmark.url),
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = SitePermissions.hostOf(
                                            SitePermissions.originOf(bookmark.url).orEmpty(),
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingNotificationPrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = {
                prompt.decide(false)
                pendingNotificationPrompt = null
            },
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.notify_prompt_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(
                            R.string.notify_prompt_message,
                            SitePermissions.hostOf(prompt.origin),
                        ),
                    )
                    Text(
                        text = stringResource(R.string.notify_prompt_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        prompt.decide(true)
                        pendingNotificationPrompt = null
                        WebNotificationBridge.ensureChannel(context)
                        // Android still decides whether a notification may be
                        // shown at all; ask once, without blocking the page.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            runCatching {
                                notificationPermissionLauncher.launch(
                                    Manifest.permission.POST_NOTIFICATIONS,
                                )
                            }
                        }
                    },
                ) { Text(stringResource(R.string.web_permission_allow)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        prompt.decide(false)
                        pendingNotificationPrompt = null
                    },
                ) { Text(stringResource(R.string.web_permission_deny)) }
            },
        )
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

    pendingFormLogin?.let { target ->
        val host = runCatching { Uri.parse(target.url).host }.getOrNull().orEmpty()
        AlertDialog(
            onDismissRequest = { pendingFormLogin = null },
            title = { Text(stringResource(R.string.form_login_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.form_login_site, host),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(
                            R.string.form_login_username,
                            target.credentials.username.ifBlank { "—" },
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = stringResource(
                            R.string.form_login_password_length,
                            target.credentials.password.length,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = stringResource(R.string.form_login_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val uri = runCatching { Uri.parse(target.url) }.getOrNull()
                        val formHost = uri?.host
                        val saved = if (formHost != null) {
                            val port = uri.port.takeIf { it > 0 } ?: 443
                            container.credentials.saveFormLogin(
                                host = formHost,
                                port = port,
                                username = target.credentials.username,
                                password = target.credentials.password,
                            )
                        } else {
                            false
                        }
                        pendingFormLogin = null
                        toast(
                            context.getString(
                                if (saved) R.string.form_login_saved else R.string.form_login_save_failed,
                            ),
                        )
                    },
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingFormLogin = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    state.fileChooser?.let { request ->
        val acceptsImage = request.params.acceptTypes.isEmpty() ||
            request.params.acceptTypes.any { it == "*/*" || it.startsWith("image/") }
        val canUseCamera = request.params.mode != WebChromeClient.FileChooserParams.MODE_SAVE &&
            acceptsImage
        Dialog(
            onDismissRequest = {
                request.deliver(null)
                state.fileChooser = null
            },
            properties = DialogProperties(dismissOnClickOutside = false),
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.file_chooser_title),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    TextButton(
                        onClick = { startFilePicker(request) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.file_chooser_file)) }
                    if (canUseCamera) {
                        TextButton(
                            onClick = { startCamera(request) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.file_chooser_camera)) }
                    }
                    TextButton(
                        onClick = {
                            request.deliver(null)
                            state.fileChooser = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.action_cancel)) }
                }
            }
        }
    }

    state.webPermission?.let { request ->
        val messageRes = when {
            request.wantsCamera && request.wantsMicrophone -> R.string.web_permission_both
            request.wantsCamera -> R.string.web_permission_camera
            else -> R.string.web_permission_microphone
        }
        AlertDialog(
            onDismissRequest = {
                rememberWebPermission(request, false)
                request.deny()
                state.webPermission = null
            },
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.web_permission_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(messageRes))
                    if (request.origin.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.web_permission_origin, request.origin),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.webPermission = null
                        rememberWebPermission(request, true)
                        requestAndroidWebPermissions(request)
                    },
                ) { Text(stringResource(R.string.web_permission_allow)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        state.webPermission = null
                        rememberWebPermission(request, false)
                        request.deny()
                    },
                ) { Text(stringResource(R.string.web_permission_deny)) }
            },
        )
    }

    state.geoPermission?.let { request ->
        AlertDialog(
            onDismissRequest = {
                container.sitePermissions.record(
                    request.origin,
                    SitePermissionKind.LOCATION,
                    false,
                )
                request.resolve(false)
                state.geoPermission = null
            },
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.geo_permission_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.geo_permission_message,
                        request.origin.ifBlank { "this site" },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.geoPermission = null
                        container.sitePermissions.record(
                            request.origin,
                            SitePermissionKind.LOCATION,
                            true,
                        )
                        requestLocationPermission(request)
                    },
                ) { Text(stringResource(R.string.geo_permission_allow)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        state.geoPermission = null
                        container.sitePermissions.record(
                            request.origin,
                            SitePermissionKind.LOCATION,
                            false,
                        )
                        request.resolve(false)
                    },
                ) { Text(stringResource(R.string.geo_permission_deny)) }
            },
        )
    }

    state.fullscreenView?.let { view ->
        FullscreenVideoDialog(
            view = view,
            onClose = { exitFullscreen(state) },
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
                    val added = container.bookmarks.add(newTitle, newUrl, glyph)
                    if (glyph.isBlank() && container.settings.current().fetchBookmarkIcons) {
                        scope.launch { container.favicons.refresh(added.id) }
                    }
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

/** A pending "this site wants to send notifications" question. */
private class NotifyPrompt(
    val origin: String,
    private val onDecide: (Boolean) -> Unit,
) {
    private var settled = false

    fun decide(allowed: Boolean) {
        if (settled) return
        settled = true
        onDecide(allowed)
    }
}

@Composable
private fun BrowserChromeBar(
    title: String,
    subtitle: String,
    secure: Boolean,
    bookmarked: Boolean,
    compact: Boolean,
    loading: Boolean,
    progress: Int,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onHome: () -> Unit,
    onReload: () -> Unit,
    onBookmarks: () -> Unit,
    menuContent: @Composable () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column(modifier = Modifier.statusBarsPadding()) {
            if (compact) {
                CompactChromeRow(
                    title = title,
                    subtitle = subtitle,
                    bookmarked = bookmarked,
                    menuOpen = menuOpen,
                    onMenuOpenChange = onMenuOpenChange,
                    onHome = onHome,
                    onReload = onReload,
                    onBookmarks = onBookmarks,
                    menuContent = menuContent,
                )
            } else {
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
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { onMenuOpenChange(false) },
                        ) {
                            menuContent()
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
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

/**
 * The bar used for a page opened from a bookmark: one 44dp line with the home
 * and reload actions on the left, "title · host" in the middle and the bookmark
 * switcher plus the overflow menu on the right. The two-line layout was taller
 * than the content it described on a phone.
 */
@Composable
private fun CompactChromeRow(
    title: String,
    subtitle: String,
    bookmarked: Boolean,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onHome: () -> Unit,
    onReload: () -> Unit,
    onBookmarks: () -> Unit,
    menuContent: @Composable () -> Unit,
) {
    val hostColor = MaterialTheme.colorScheme.onSurfaceVariant
    val label = remember(title, subtitle, bookmarked, hostColor) {
        buildAnnotatedString {
            append(title)
            if (subtitle.isNotBlank() && !title.contains(subtitle)) {
                withStyle(SpanStyle(color = hostColor, fontSize = 11.sp)) {
                    append("  ·  ")
                    append(subtitle)
                }
            }
            if (bookmarked) append("  ★")
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onHome, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Filled.Home,
                contentDescription = stringResource(R.string.browser_open_home),
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onReload, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.browser_reload),
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp),
        )
        IconButton(onClick = onBookmarks, modifier = Modifier.size(40.dp)) {
            Icon(
                Icons.Filled.Menu,
                contentDescription = stringResource(R.string.browser_bookmarks),
                modifier = Modifier.size(20.dp),
            )
        }
        Box {
            IconButton(
                onClick = { onMenuOpenChange(true) },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.browser_more),
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuOpenChange(false) }) {
                menuContent()
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
    onNotificationPrompt: (String, (Boolean) -> Unit) -> Unit,
) {
    // Page surface stays white, like desktop browsers. Painting it with the app's
    // dark surface made ordinary pages (black text, no background) unreadable.
    val backgroundColor = android.graphics.Color.WHITE
    key(state.generation) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            ensureWebViewDebugging()
            val frame = android.widget.FrameLayout(ctx)
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
                webChromeClient = HalfBrowserChromeClient(state, container)
                setDownloadListener(DownloadListener { url, _, contentDisposition, mimeType, _ ->
                    val started = enqueueDownload(context, url, contentDisposition, mimeType)
                    toast(
                        context.getString(
                            if (started) R.string.toast_download_started else R.string.toast_download_failed,
                        ),
                    )
                })
                installDocumentScripts(this)
                WebNotificationBridge(
                    context = context,
                    permissions = container.sitePermissions,
                    currentUrl = { state.currentUrl },
                    askUser = onNotificationPrompt,
                ).register(this)
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                state.webView = this
                val initialUrl = state.lastRequestedUrl.ifBlank { request.url }
                state.lastRequestedUrl = initialUrl
                // Load only after the view has a real size. Loading from the
                // factory (before the first layout) leaves Chromium's layout
                // viewport at 0 high, which makes every `100vh`/`100dvh` unit
                // resolve to 0 and collapses modern SPAs.
                loadWhenSized(initialUrl)
            }
            frame.addView(
                webView,
                android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            frame
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

/**
 * Text zoom handed to the WebView.
 *
 * The UI scale multiplies the text scale so raising it also enlarges the page's
 * text, and Chromium re-lays the text out (it reflows instead of overflowing).
 *
 * Why not a whole-page zoom: `setInitialScale()` is ignored as soon as the page
 * declares `<meta name="viewport" content="width=device-width, initial-scale=1">`
 * (DSH does), and CSS `zoom` on the root element multiplies the height of
 * `100dvh` layouts — on DSH the document stopped scrolling (`scrollY` stayed 0)
 * and the composer became unreachable. Both were measured on WebView 113, so the
 * only zoom that keeps a fixed-height app usable is the text zoom.
 */
internal fun webTextZoomPercent(settings: com.yukiorita.halfbrowser.core.AppSettings): Int =
    (settings.fontScale * settings.uiScale * 100).roundToInt().coerceIn(50, 200)

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

private fun exitFullscreen(state: BrowserState) {
    val callback = state.fullscreenCallback
    state.fullscreenView = null
    state.fullscreenCallback = null
    runCatching { callback?.onCustomViewHidden() }
}

/**
 * Hosts a WebChromeClient custom view (HTML5 video fullscreen). The View belongs
 * to WebView, so it is detached from any old parent, attached here, and removed
 * again when the dialog goes away.
 */
@Composable
private fun FullscreenVideoDialog(view: View, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(Unit) {
            val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose {
                controller?.show(WindowInsetsCompat.Type.systemBars())
                window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                (view.parent as? ViewGroup)?.removeView(view)
            }
        }
        BackHandler(onBack = onClose)
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                (view.parent as? ViewGroup)?.removeView(view)
                FrameLayout(ctx).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    addView(
                        view,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
            },
        )
    }
}

private data class FormLoginTarget(
    val url: String,
    val credentials: com.yukiorita.halfbrowser.core.FormCredentials,
)

/**
 * Injects the compatibility shims before any page script runs. Without
 * DOCUMENT_START_SCRIPT support the page simply stays unsupported rather than
 * receiving a late, half-working polyfill.
 */
private fun installDocumentScripts(webView: WebView) {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
    // Order matters only in that each script must run before page code; they are
    // independent of one another (compatibility polyfills, keyboard modifier
    // guard, notification bridge).
    listOf(
        com.yukiorita.halfbrowser.core.WebCompat.POLYFILL_JS,
        com.yukiorita.halfbrowser.core.KeyboardGuard.JS,
        com.yukiorita.halfbrowser.core.WebNotify.JS,
    ).forEach { script ->
        runCatching { WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*")) }
    }
}

/** Reads the visible login form and hands it to the confirmation dialog. */
private fun captureFormLogin(
    webView: WebView?,
    pageUrl: String,
    onFound: (FormLoginTarget) -> Unit,
    onMissing: () -> Unit,
) {
    val view = webView ?: return
    runCatching {
        view.evaluateJavascript(com.yukiorita.halfbrowser.core.FormLogin.EXTRACT_JS) { raw ->
            val credentials = com.yukiorita.halfbrowser.core.FormLogin.parseExtraction(raw)
            if (credentials == null) {
                onMissing()
            } else {
                onFound(FormLoginTarget(pageUrl, credentials))
            }
        }
    }.onFailure { onMissing() }
}

/**
 * Defers the first load until the WebView is measured. Chromium captures the
 * viewport size when the first document is committed; a 0-height view at that
 * moment poisons viewport units for the whole session.
 */
private fun WebView.loadWhenSized(url: String) {
    if (width > 0 && height > 0) {
        loadUrl(url)
        return
    }
    val listener = object : android.view.View.OnLayoutChangeListener {
        override fun onLayoutChange(
            v: android.view.View?,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
            oldLeft: Int,
            oldTop: Int,
            oldRight: Int,
            oldBottom: Int,
        ) {
            if (v != null && v.width > 0 && v.height > 0) {
                v.removeOnLayoutChangeListener(this)
                loadUrl(url)
            }
        }
    }
    addOnLayoutChangeListener(listener)
}
