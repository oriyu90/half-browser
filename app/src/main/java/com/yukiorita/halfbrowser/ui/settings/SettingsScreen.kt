package com.yukiorita.halfbrowser.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.webkit.WebViewCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yukiorita.halfbrowser.AppContainer
import com.yukiorita.halfbrowser.BuildConfig
import com.yukiorita.halfbrowser.R
import com.yukiorita.halfbrowser.core.AppSettings
import com.yukiorita.halfbrowser.core.CredentialMeta
import com.yukiorita.halfbrowser.core.LanguageMode
import com.yukiorita.halfbrowser.core.SearchEngine
import com.yukiorita.halfbrowser.core.ThemeMode
import com.yukiorita.halfbrowser.core.TrustPolicy
import com.yukiorita.halfbrowser.core.BookmarkIcons
import com.yukiorita.halfbrowser.core.FormLogin
import com.yukiorita.halfbrowser.core.SiteWatch
import com.yukiorita.halfbrowser.core.WatchRules
import com.yukiorita.halfbrowser.work.WatchScheduler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(SettingsTab.DISPLAY) }
    val trustedSites by container.trust.sites.collectAsStateWithLifecycle()
    var credentials by remember { mutableStateOf(container.credentials.all()) }
    var credentialToDelete by remember { mutableStateOf<CredentialMeta?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val webViewVersion = remember {
        runCatching { WebViewCompat.getCurrentWebViewPackage(context)?.versionName }
            .getOrNull()
            .orEmpty()
    }
    val webViewMajor = remember(webViewVersion) {
        webViewVersion.substringBefore('.').toIntOrNull() ?: 0
    }
    val bookmarks by container.bookmarks.items.collectAsStateWithLifecycle()
    val watches by container.siteWatch.watches.collectAsStateWithLifecycle()
    val watchSettings by container.siteWatch.settings.collectAsStateWithLifecycle()
    var notificationsAllowed by remember {
        mutableStateOf(
            runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }
                .getOrDefault(false),
        )
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        notificationsAllowed = runCatching {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }.getOrDefault(false)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                credentials = container.credentials.all()
                notificationsAllowed = runCatching {
                    NotificationManagerCompat.from(context).areNotificationsEnabled()
                }.getOrDefault(false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun activeWatchCount(): Boolean {
        val ids = container.bookmarks.snapshot().map { it.id }.toSet()
        return container.siteWatch.snapshot().any { it.enabled && it.bookmarkId in ids }
    }

    fun syncWatchSchedule() {
        WatchScheduler.sync(context, container.siteWatch.settingsSnapshot(), activeWatchCount())
    }

    fun toggleWatch(bookmarkId: String, enabled: Boolean) {
        container.siteWatch.setEnabled(bookmarkId, enabled)
        WatchScheduler.sync(context, container.siteWatch.settingsSnapshot(), activeWatchCount())
        if (enabled) {
            WatchScheduler.requestImmediate(context, container.siteWatch.settingsSnapshot())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsAllowed) {
                runCatching {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    fun formatCheckTime(timestamp: Long): String =
        runCatching { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp)) }
            .getOrDefault("")

    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                ScrollableTabRow(
                    selectedTabIndex = tab.ordinal,
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = MaterialTheme.colorScheme.background,
                    edgePadding = 12.dp,
                    divider = {},
                ) {
                    SettingsTab.entries.forEach { entry ->
                        Tab(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            text = { Text(stringResource(entry.labelRes)) },
                        )
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .widthIn(max = 760.dp)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (tab == SettingsTab.DISPLAY) {
                        item {
                            SettingsCard(title = stringResource(R.string.settings_section_appearance)) {
                                Text(
                                    text = stringResource(R.string.settings_theme),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ThemeMode.entries.forEach { mode ->
                                        FilterChip(
                                            selected = settings.themeMode == mode,
                                            onClick = {
                                                container.settings.update { it.copy(themeMode = mode) }
                                            },
                                            label = {
                                                Text(
                                                    stringResource(
                                                        when (mode) {
                                                            ThemeMode.SYSTEM -> R.string.settings_theme_system
                                                            ThemeMode.LIGHT -> R.string.settings_theme_light
                                                            ThemeMode.DARK -> R.string.settings_theme_dark
                                                        },
                                                    ),
                                                )
                                            },
                                        )
                                    }
                                }
                                SwitchRow(
                                    title = stringResource(R.string.settings_dynamic_color),
                                    description = stringResource(R.string.settings_dynamic_color_desc),
                                    checked = settings.dynamicColor,
                                    enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                                    onCheckedChange = {
                                        container.settings.update { current -> current.copy(dynamicColor = it) }
                                    },
                                )
                                AccentSettings(settings = settings) { transform ->
                                    container.settings.update(transform)
                                }
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                Text(
                                    text = stringResource(R.string.settings_language),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                LanguageMode.entries.forEach { mode ->
                                    RadioRow(
                                        selected = settings.language == mode,
                                        label = stringResource(
                                            when (mode) {
                                                LanguageMode.SYSTEM -> R.string.settings_language_system
                                                LanguageMode.JAPANESE -> R.string.settings_language_ja
                                                LanguageMode.ENGLISH -> R.string.settings_language_en
                                            },
                                        ),
                                        onClick = {
                                            if (settings.language != mode) {
                                                container.settings.update { it.copy(language = mode) }
                                                (context as? Activity)?.recreate()
                                            }
                                        },
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.settings_language_note),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                    }

                    item {
                        SettingsCard(title = stringResource(R.string.settings_section_display)) {
                            ScaleSlider(
                                label = stringResource(R.string.settings_ui_scale),
                                initial = settings.uiScale,
                                min = AppSettings.MIN_UI_SCALE,
                                max = AppSettings.MAX_UI_SCALE,
                                onCommit = { value ->
                                    container.settings.update { it.copy(uiScale = value) }
                                },
                            )
                            ScaleSlider(
                                label = stringResource(R.string.settings_font_scale),
                                initial = settings.fontScale,
                                min = AppSettings.MIN_FONT_SCALE,
                                max = AppSettings.MAX_FONT_SCALE,
                                onCommit = { value ->
                                    container.settings.update { it.copy(fontScale = value) }
                                },
                            )
                            Text(
                                text = stringResource(R.string.settings_page_zoom_note),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = stringResource(R.string.settings_web_zoom_note),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(
                                onClick = {
                                    container.settings.update {
                                        it.copy(uiScale = 1f, fontScale = 1f)
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.settings_scale_reset))
                            }
                            SwitchRow(
                                title = stringResource(R.string.settings_fetch_icons),
                                description = stringResource(R.string.settings_fetch_icons_desc),
                                checked = settings.fetchBookmarkIcons,
                                onCheckedChange = { value ->
                                    container.settings.update { it.copy(fetchBookmarkIcons = value) }
                                },
                            )
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            Text(
                                text = stringResource(R.string.settings_grid_columns),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilterChip(
                                    selected = settings.gridColumns == 0,
                                    onClick = {
                                        container.settings.update { it.copy(gridColumns = 0) }
                                    },
                                    label = { Text(stringResource(R.string.settings_grid_auto)) },
                                )
                                listOf(3, 4, 5, 6, 8).forEach { count ->
                                    FilterChip(
                                        selected = settings.gridColumns == count,
                                        onClick = {
                                            container.settings.update { it.copy(gridColumns = count) }
                                        },
                                        label = { Text(stringResource(R.string.settings_grid_n, count)) },
                                    )
                                }
                            }
                        }
                    }

                    }
                    if (tab == SettingsTab.BROWSING) {
                        item {
                            SettingsCard(title = stringResource(R.string.settings_section_search)) {
                                Text(
                                    text = stringResource(R.string.settings_search_engine),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                SearchEngine.entries.forEach { engine ->
                                    RadioRow(
                                        selected = settings.searchEngine == engine,
                                        label = engine.label,
                                        onClick = {
                                            container.settings.update { it.copy(searchEngine = engine) }
                                        },
                                    )
                                }
                            }
                    }

                    }
                    if (tab == SettingsTab.PERMISSIONS) {
                            item { SitePermissionsCard(container) }
                    }
                    if (tab == SettingsTab.NOTIFICATIONS) {
                        item {
                            SettingsCard(title = stringResource(R.string.settings_section_notifications)) {
                                Text(
                                    text = stringResource(R.string.settings_notifications_desc),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                SwitchRow(
                                    title = stringResource(R.string.settings_notifications_master),
                                    description = "",
                                    checked = watchSettings.enabled,
                                    onCheckedChange = { value ->
                                        container.siteWatch.updateSettings { it.copy(enabled = value) }
                                        syncWatchSchedule()
                                    },
                                )
                                Text(
                                    text = stringResource(R.string.settings_notifications_interval),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    WatchRules.INTERVALS.forEach { minutes ->
                                        FilterChip(
                                            selected = watchSettings.intervalMinutes == minutes,
                                            onClick = {
                                                container.siteWatch.updateSettings {
                                                    it.copy(intervalMinutes = minutes)
                                                }
                                                syncWatchSchedule()
                                            },
                                            label = {
                                                Text(
                                                    stringResource(
                                                        R.string.settings_notifications_interval_value,
                                                        minutes,
                                                    ),
                                                )
                                            },
                                        )
                                    }
                                }
                                SwitchRow(
                                    title = stringResource(R.string.settings_notifications_wifi),
                                    description = "",
                                    checked = watchSettings.wifiOnly,
                                    onCheckedChange = { value ->
                                        container.siteWatch.updateSettings { it.copy(wifiOnly = value) }
                                        syncWatchSchedule()
                                    },
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.settings_notifications_permission),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            text = stringResource(
                                                if (notificationsAllowed) {
                                                    R.string.settings_notifications_permission_ok
                                                } else {
                                                    R.string.settings_notifications_permission_denied
                                                },
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (notificationsAllowed) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.error
                                            },
                                        )
                                    }
                                    if (!notificationsAllowed) {
                                        TextButton(
                                            onClick = {
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                    runCatching {
                                                        notificationPermissionLauncher.launch(
                                                            Manifest.permission.POST_NOTIFICATIONS,
                                                        )
                                                    }
                                                } else {
                                                    runCatching {
                                                        context.startActivity(
                                                            Intent(
                                                                android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS,
                                                            ).putExtra(
                                                                android.provider.Settings.EXTRA_APP_PACKAGE,
                                                                context.packageName,
                                                            )
                                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                                        )
                                                    }
                                                }
                                            },
                                        ) {
                                            Text(stringResource(R.string.settings_notifications_request_permission))
                                        }
                                    }
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                Text(
                                    text = stringResource(R.string.settings_notifications_sites),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                if (bookmarks.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.settings_notifications_empty),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    val anyEnabled = watches.any { it.enabled }
                                    if (!anyEnabled) {
                                        Text(
                                            text = stringResource(R.string.settings_notifications_no_sites),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    bookmarks.forEach { bookmark ->
                                        val watch: SiteWatch? = watches.firstOrNull {
                                            it.bookmarkId == bookmark.id
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = BookmarkIcons.normalizedTitleOrFallback(
                                                        bookmark.title,
                                                        bookmark.url,
                                                    ),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                )
                                                Text(
                                                    text = if ((watch?.lastCheckedAt ?: 0L) > 0L) {
                                                        stringResource(
                                                            R.string.settings_notifications_last,
                                                            formatCheckTime(watch!!.lastCheckedAt),
                                                        )
                                                    } else {
                                                        stringResource(R.string.settings_notifications_never)
                                                    },
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                if (!watch?.lastError.isNullOrBlank()) {
                                                    Text(
                                                        text = stringResource(
                                                            R.string.settings_notifications_error,
                                                            watch!!.lastError,
                                                        ),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.error,
                                                    )
                                                }
                                                if (watch?.enabled == true) {
                                                    TextButton(
                                                        onClick = {
                                                            WatchScheduler.requestImmediate(
                                                                context,
                                                                container.siteWatch.settingsSnapshot(),
                                                            )
                                                        },
                                                    ) {
                                                        Text(stringResource(R.string.settings_notifications_check_now))
                                                    }
                                                }
                                            }
                                            Switch(
                                                checked = watch?.enabled == true,
                                                onCheckedChange = { enabled ->
                                                    toggleWatch(bookmark.id, enabled)
                                                },
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = stringResource(R.string.settings_notifications_cooldown),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (watchSettings.lastRunAt > 0L) {
                                    Text(
                                        text = stringResource(
                                            R.string.settings_notifications_last_run,
                                            formatCheckTime(watchSettings.lastRunAt),
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                    }

                        item { BackgroundNotificationsCard(container) }
                    }
                    if (tab == SettingsTab.SECURITY) {
                        item {
                            SettingsCard(title = stringResource(R.string.settings_js_guard)) {
                                SwitchRow(
                                    title = stringResource(R.string.settings_js_guard),
                                    description = stringResource(R.string.settings_js_guard_desc),
                                    checked = settings.jsGuardEnabled,
                                    onCheckedChange = { enabled ->
                                        container.settings.update { it.copy(jsGuardEnabled = enabled) }
                                    },
                                )
                                Text(
                                    text = stringResource(R.string.settings_js_guard_note),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        item {
                            SettingsCard(title = stringResource(R.string.settings_section_security)) {
                                Text(
                                    text = stringResource(R.string.settings_trusted_sites),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = stringResource(R.string.settings_trusted_sites_desc),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (trustedSites.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.settings_trusted_empty),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    trustedSites.forEach { site ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                Icons.Filled.Lock,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = if (site.port > 0 && site.port != 443) {
                                                        "${site.host}:${site.port}"
                                                    } else {
                                                        site.host
                                                    },
                                                    style = MaterialTheme.typography.bodyMedium,
                                                )
                                                Text(
                                                    text = TrustPolicy.formatFingerprint(site.fingerprintSha256)
                                                        .take(23) + "…",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                if (site.addedAt > 0L) {
                                                    val date = runCatching {
                                                        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                                                            .format(Date(site.addedAt))
                                                    }.getOrNull()
                                                    if (!date.isNullOrBlank()) {
                                                        Text(
                                                            text = stringResource(
                                                                R.string.settings_trusted_added,
                                                                date,
                                                            ),
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                }
                                            }
                                            TextButton(
                                                onClick = {
                                                    container.trust.revoke(site.host, site.port)
                                                    // The live WebView may still remember the old
                                                    // exception; rebuild it so the next load re-prompts.
                                                    container.notifyTrustChanged()
                                                },
                                            ) {
                                                Text(stringResource(R.string.action_revoke))
                                            }
                                        }
                                    }
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                Text(
                                    text = stringResource(R.string.settings_credentials),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = stringResource(R.string.settings_credentials_desc),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (!container.credentials.isPersistent) {
                                    Text(
                                        text = stringResource(R.string.settings_security_not_persistent),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                                if (credentials.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.settings_credentials_empty),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    credentials.forEach { credential ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                Icons.Filled.Language,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = credential.host,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                )
                                                val realmLabel = if (credential.realm == FormLogin.REALM) {
                                                    stringResource(R.string.form_login_realm)
                                                } else {
                                                    credential.realm
                                                }
                                                Text(
                                                    text = listOf(credential.username, realmLabel)
                                                        .filter { it.isNotBlank() }
                                                        .joinToString(" · "),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            IconButton(onClick = { credentialToDelete = credential }) {
                                                Icon(
                                                    Icons.Filled.Delete,
                                                    contentDescription = stringResource(R.string.action_remove),
                                                    tint = MaterialTheme.colorScheme.error,
                                                )
                                            }
                                        }
                                    }
                                }

                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                SwitchRow(
                                    title = stringResource(R.string.settings_form_autofill),
                                    description = stringResource(R.string.settings_form_autofill_desc),
                                    checked = settings.formAutofillEnabled,
                                    onCheckedChange = { value ->
                                        container.settings.update { it.copy(formAutofillEnabled = value) }
                                    },
                                )

                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                                Text(
                                    text = stringResource(R.string.settings_clear_data),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = stringResource(R.string.settings_clear_data_desc),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(onClick = { confirmClear = true }) {
                                    Text(
                                        text = stringResource(R.string.settings_clear_data),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                    }

                    }
                    if (tab == SettingsTab.VPN) {
                        item { VpnSettingsTab(container) }
                    }
                    if (tab == SettingsTab.BACKUP) {
                        item {
                            SettingsCard(title = stringResource(R.string.settings_section_backup)) {
                                BackupSettingsCard(
                                    container = container,
                                    onImported = { credentials = container.credentials.all() },
                                )
                            }
                    }

                    }
                    if (tab == SettingsTab.ABOUT) {
                        item {
                            SettingsCard(title = stringResource(R.string.settings_section_about)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                    ) {
                                        Icon(
                                            Icons.Filled.Public,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .padding(10.dp)
                                                .size(24.dp),
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = stringResource(R.string.app_name),
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        Text(
                                            text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        if (webViewVersion.isNotBlank()) {
                                            Text(
                                                text = stringResource(R.string.settings_webview_version, webViewVersion),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = stringResource(R.string.settings_about_body),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (webViewVersion.isNotBlank() && webViewMajor in 1..119) {
                                    Text(
                                        text = stringResource(R.string.settings_webview_old),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    TextButton(
                                        onClick = {
                                            val market = Intent(
                                                Intent.ACTION_VIEW,
                                                android.net.Uri.parse(
                                                    "market://details?id=com.google.android.webview",
                                                ),
                                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            val web = Intent(
                                                Intent.ACTION_VIEW,
                                                android.net.Uri.parse(
                                                    "https://play.google.com/store/apps/details?id=com.google.android.webview",
                                                ),
                                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            val opened = runCatching { context.startActivity(market) }
                                            if (opened.isFailure) {
                                                runCatching { context.startActivity(web) }
                                            }
                                        },
                                    ) {
                                        Text(stringResource(R.string.settings_webview_update))
                                    }
                                }
                                Text(
                                    text = stringResource(R.string.settings_privacy),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            runCatching {
                                                context.startActivity(
                                                    Intent(Intent.ACTION_VIEW)
                                                        .setData(android.net.Uri.parse(SOURCE_URL))
                                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                                )
                                            }
                                        }
                                        .padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Filled.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = stringResource(R.string.settings_source),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                Text(
                                    text = stringResource(R.string.settings_license),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }                }

                    }
                }
            }
        }

        credentialToDelete?.let { credential ->
            AlertDialog(
                onDismissRequest = { credentialToDelete = null },
                title = { Text(stringResource(R.string.settings_credentials_remove_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.settings_credentials_remove_message,
                            credential.host,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            container.credentials.delete(credential.id)
                            credentials = container.credentials.all()
                            credentialToDelete = null
                        },
                    ) {
                        Text(
                            text = stringResource(R.string.action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { credentialToDelete = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }

        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text(stringResource(R.string.settings_clear_data_title)) },
                text = { Text(stringResource(R.string.settings_clear_data_message)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmClear = false
                            clearBrowsingData(context)
                        },
                    ) {
                        Text(stringResource(R.string.action_clear))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmClear = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                },
            )
        }
    }

    private const val SOURCE_URL = "https://github.com/oriyu90/half-browser"

/**
 * One tab per concern. Settings grew past the point where a single scrolling
 * column stayed readable, and site permissions in particular need room to grow.
 */
enum class SettingsTab(val labelRes: Int) {
    DISPLAY(R.string.settings_tab_display),
    BROWSING(R.string.settings_tab_browsing),
    PERMISSIONS(R.string.settings_tab_permissions),
    NOTIFICATIONS(R.string.settings_tab_notifications),
    SECURITY(R.string.settings_tab_security),
    VPN(R.string.settings_tab_vpn),
    BACKUP(R.string.settings_tab_backup),
    ABOUT(R.string.settings_tab_about),
}

    @Composable
    internal fun SettingsCard(title: String, content: @Composable () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                content()
            }
        }
    }

    @Composable
    internal fun SwitchRow(
        title: String,
        description: String,
        checked: Boolean,
        enabled: Boolean = true,
        onCheckedChange: (Boolean) -> Unit,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }

    @Composable
    private fun RadioRow(selected: Boolean, label: String, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(Modifier.width(8.dp))
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
        }
    }

    @Composable
    private fun ScaleSlider(
        label: String,
        initial: Float,
        min: Float,
        max: Float,
        onCommit: (Float) -> Unit,
    ) {
        var value by remember(initial) { mutableFloatStateOf(initial) }
        val steps = ((max - min) / 0.1f).roundToInt() - 1
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
                    text = stringResource(
                        R.string.settings_scale_value,
                        (value * 100).roundToInt(),
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Slider(
                value = value,
                onValueChange = { value = it },
                onValueChangeFinished = { onCommit(value) },
                valueRange = min..max,
                steps = steps.coerceAtLeast(0),
            )
        }
    }

    private fun clearBrowsingData(context: Context) {
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
        runCatching { WebStorage.getInstance().deleteAllData() }
        runCatching {
            val webView = WebView(context)
            webView.clearCache(true)
            webView.clearHistory()
            webView.destroy()
        }
        android.widget.Toast.makeText(
            context,
            context.getString(R.string.toast_cleared),
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }
