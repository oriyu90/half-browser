package com.yukiorita.halfbrowser.ui.home

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yukiorita.halfbrowser.AppContainer
import com.yukiorita.halfbrowser.R
import com.yukiorita.halfbrowser.core.Bookmark
import com.yukiorita.halfbrowser.core.BookmarkIcons
import com.yukiorita.halfbrowser.core.GridColumns
import com.yukiorita.halfbrowser.core.UrlNormalizer
import com.yukiorita.halfbrowser.shortcuts.BookmarkShortcuts
import com.yukiorita.halfbrowser.work.WatchScheduler
import com.yukiorita.halfbrowser.ui.components.BookmarkEditorDialog
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class EditingTarget(val bookmark: Bookmark?, val url: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    onOpenRequest: (url: String, bookmarkId: String?) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    val bookmarks by container.bookmarks.items.collectAsStateWithLifecycle()
    val watches by container.siteWatch.watches.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var query by rememberSaveable { mutableStateOf("") }
    var editingTarget by remember { mutableStateOf<EditingTarget?>(null) }
    var menuTarget by remember { mutableStateOf<Bookmark?>(null) }
    var deleteTarget by remember { mutableStateOf<Bookmark?>(null) }

    LaunchedEffect(bookmarks) {
        BookmarkShortcuts.syncDynamic(context, bookmarks)
    }

    fun message(text: String) {
        scope.launch { snackbarHostState.showSnackbar(text) }
    }

    fun message(resId: Int) = message(context.getString(resId))

    fun submitQuery() {
        val target = UrlNormalizer.toNavigable(query, settings.searchEngine.template)
        if (target == null) {
            message(R.string.toast_invalid_url)
        } else {
            query = ""
            onOpenRequest(target, null)
        }
    }

    fun syncWatchSchedule() {
        val ids = container.bookmarks.snapshot().map { it.id }.toSet()
        val active = container.siteWatch.snapshot().any { it.enabled && it.bookmarkId in ids }
        WatchScheduler.sync(context, container.siteWatch.settingsSnapshot(), active)
    }

    fun toggleWatch(bookmark: Bookmark, enabled: Boolean) {
        container.siteWatch.setEnabled(bookmark.id, enabled)
        syncWatchSchedule()
        if (enabled) {
            WatchScheduler.requestImmediate(context, container.siteWatch.settingsSnapshot())
        }
        message(
            context.getString(
                if (enabled) R.string.bookmark_notify_enabled else R.string.bookmark_notify_disabled,
                BookmarkIcons.normalizedTitleOrFallback(bookmark.title, bookmark.url),
            ),
        )
    }

    fun requestShortcut(bookmark: Bookmark) {
        message(
            when (BookmarkShortcuts.requestPin(context, bookmark)) {
                BookmarkShortcuts.PinResult.REQUESTED -> R.string.shortcut_added
                BookmarkShortcuts.PinResult.UNSUPPORTED -> R.string.shortcut_unavailable
                BookmarkShortcuts.PinResult.FAILED -> R.string.shortcut_failed
            },
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = stringResource(R.string.app_tagline),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.cd_settings),
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { editingTarget = EditingTarget(null, "") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(
                    Icons.Filled.BookmarkAdd,
                    contentDescription = stringResource(R.string.home_add_bookmark),
                )
            }
        },
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val maxContent = 1100.dp
            val contentWidth = if (maxWidth < maxContent) maxWidth else maxContent
            val columns = remember(contentWidth, settings.gridColumns) {
                GridColumns.resolve(contentWidth.value, settings.gridColumns)
            }
            val gridMaxWidth = if (settings.gridColumns == 0) {
                maxContent
            } else {
                minOf(maxContent, (columns * 168 + 32).dp)
            }

            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier
                        .widthIn(max = gridMaxWidth)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        AddressBar(
                            query = query,
                            onQueryChange = { query = it },
                            onSubmit = { submitQuery() },
                        )
                    }

                    if (bookmarks.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            EmptyState(onAdd = { editingTarget = EditingTarget(null, "") })
                        }
                    } else {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(R.string.home_bookmarks),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = bookmarks.size.toString(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(bookmarks, key = { it.id }) { bookmark ->
                            BookmarkTile(
                                bookmark = bookmark,
                                onClick = { onOpenRequest(bookmark.url, bookmark.id) },
                                onLongClick = { menuTarget = bookmark },
                            )
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                text = stringResource(R.string.home_tip),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    menuTarget?.let { bookmark ->
        AlertDialog(
            onDismissRequest = { menuTarget = null },
            title = {
                Text(
                    text = BookmarkIcons.normalizedTitleOrFallback(bookmark.title, bookmark.url),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            text = {
                Column {
                    MenuRow(
                        icon = { Icon(Icons.Filled.OpenInNew, contentDescription = null) },
                        label = stringResource(R.string.home_menu_open),
                        onClick = {
                            menuTarget = null
                            onOpenRequest(bookmark.url, bookmark.id)
                        },
                    )
                    MenuRow(
                        icon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        label = stringResource(R.string.home_menu_edit),
                        onClick = {
                            menuTarget = null
                            editingTarget = EditingTarget(bookmark, bookmark.url)
                        },
                    )
                    MenuRow(
                        icon = { Icon(Icons.Filled.Star, contentDescription = null) },
                        label = stringResource(R.string.home_menu_shortcut),
                        onClick = {
                            menuTarget = null
                            requestShortcut(bookmark)
                        },
                    )
                    val watchEnabled = watches.firstOrNull { it.bookmarkId == bookmark.id }?.enabled == true
                    MenuRow(
                        icon = {
                            Icon(
                                if (watchEnabled) Icons.Filled.NotificationsOff else Icons.Filled.Notifications,
                                contentDescription = null,
                            )
                        },
                        label = stringResource(
                            if (watchEnabled) R.string.bookmark_notify_off else R.string.bookmark_notify_on,
                        ),
                        onClick = {
                            menuTarget = null
                            toggleWatch(bookmark, !watchEnabled)
                        },
                    )
                    MenuRow(
                        icon = { Icon(Icons.Filled.Share, contentDescription = null) },
                        label = stringResource(R.string.home_menu_share),
                        onClick = {
                            menuTarget = null
                            shareBookmark(context, bookmark)
                        },
                    )
                    MenuRow(
                        icon = {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        label = stringResource(R.string.home_menu_delete),
                        labelColor = MaterialTheme.colorScheme.error,
                        onClick = {
                            menuTarget = null
                            deleteTarget = bookmark
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { menuTarget = null }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }

    deleteTarget?.let { bookmark ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.bookmark_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.bookmark_delete_message,
                        BookmarkIcons.normalizedTitleOrFallback(bookmark.title, bookmark.url),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        container.bookmarks.delete(bookmark.id)
                        container.siteWatch.prune(
                            container.bookmarks.snapshot().map { it.id }.toSet(),
                        )
                        syncWatchSchedule()
                        deleteTarget = null
                        message(R.string.bookmark_deleted)
                    },
                ) {
                    Text(
                        text = stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    editingTarget?.let { target ->
        BookmarkEditorDialog(
            initial = target.bookmark,
            initialUrl = target.url,
            onDismiss = { editingTarget = null },
            onSave = { title, url, glyph ->
                val duplicate = container.bookmarks.findByUrl(url)
                if (duplicate != null && duplicate.id != target.bookmark?.id) {
                    message(R.string.bookmark_duplicate)
                } else if (target.bookmark == null) {
                    container.bookmarks.add(title, url, glyph)
                    message(R.string.bookmark_saved)
                } else {
                    container.bookmarks.update(
                        target.bookmark.copy(title = title, url = url, glyph = glyph),
                    )
                    message(R.string.bookmark_saved)
                }
                editingTarget = null
            },
        )
    }
}

@Composable
private fun AddressBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(stringResource(R.string.home_search_hint)) },
        leadingIcon = {
            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.action_search))
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Filled.Clear,
                        contentDescription = stringResource(R.string.cd_clear_search),
                    )
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onSubmit() }),
    )
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Box(modifier = Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.BookmarkAdd,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.home_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.home_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onAdd) {
            Text(stringResource(R.string.home_add_bookmark))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarkTile(
    bookmark: Bookmark,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val color = Color(BookmarkIcons.colorFor(bookmark.url, bookmark.colorSeed))
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(22.dp))
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = BookmarkIcons.glyphFor(bookmark.title, bookmark.url, bookmark.glyph),
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = BookmarkIcons.normalizedTitleOrFallback(bookmark.title, bookmark.url),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MenuRow(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
    labelColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickableCompat(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(16.dp))
        Text(text = label, color = labelColor, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick)

private fun shareBookmark(context: Context, bookmark: Bookmark) {
    runCatching {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, bookmark.url)
            putExtra(
                Intent.EXTRA_SUBJECT,
                BookmarkIcons.normalizedTitleOrFallback(bookmark.title, bookmark.url),
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
