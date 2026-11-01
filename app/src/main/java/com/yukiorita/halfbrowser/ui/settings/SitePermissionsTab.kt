package com.yukiorita.halfbrowser.ui.settings

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yukiorita.halfbrowser.AppContainer
import com.yukiorita.halfbrowser.R
import com.yukiorita.halfbrowser.core.SitePermissionKind
import com.yukiorita.halfbrowser.core.SitePermissions
import com.yukiorita.halfbrowser.notifications.WebNotificationBridge

/**
 * Every site that has been allowed or blocked, with one row per permission.
 *
 * Tapping a permission flips it; "ask again" removes the row so the site goes
 * back to prompting. This is the only place a grant can be inspected, and it is
 * deliberately per origin — not per host — so http and https differ.
 */
@Composable
internal fun SitePermissionsCard(container: AppContainer) {
    val grants by container.sitePermissions.grants.collectAsStateWithLifecycle()
    val sites = remember(grants) { container.sitePermissions.sites() }

    SettingsCard(title = stringResource(R.string.settings_tab_permissions)) {
        Text(
            text = stringResource(R.string.settings_permissions_desc),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.settings_permissions_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (sites.isEmpty()) {
            Text(
                text = stringResource(R.string.settings_permissions_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        sites.forEach { site ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = SitePermissions.hostOf(site.origin),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = site.origin,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                site.decisions.forEach { (kind, allowed) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                container.sitePermissions.record(site.origin, kind, !allowed)
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = permissionLabel(kind),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = stringResource(
                                if (allowed) {
                                    R.string.settings_permissions_allow
                                } else {
                                    R.string.settings_permissions_block
                                },
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (allowed) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
                TextButton(onClick = { container.sitePermissions.forget(site.origin) }) {
                    Text(stringResource(R.string.settings_permissions_reset))
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        }
        if (sites.isNotEmpty()) {
            TextButton(onClick = { container.sitePermissions.clear() }) {
                Text(
                    text = stringResource(R.string.settings_permissions_clear),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun permissionLabel(kind: SitePermissionKind): String = stringResource(
    when (kind) {
        SitePermissionKind.NOTIFICATIONS -> R.string.settings_permissions_perm_notifications
        SitePermissionKind.CAMERA -> R.string.settings_permissions_perm_camera
        SitePermissionKind.MICROPHONE -> R.string.settings_permissions_perm_microphone
        SitePermissionKind.LOCATION -> R.string.settings_permissions_perm_location
    },
)

/**
 * Opt-in for immediate web notifications. Turning it on starts the keep-alive
 * foreground service (the browser screen also starts/stops it on its own), and
 * asks for the Android notification permission if it is still missing.
 */
@Composable
internal fun BackgroundNotificationsCard(container: AppContainer) {
    val context = LocalContext.current
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    var androidAllowed by remember {
        mutableStateOf(
            runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }
                .getOrDefault(false),
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        androidAllowed = runCatching {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }.getOrDefault(false)
    }

    SettingsCard(title = stringResource(R.string.settings_notifications_web_title)) {
        Text(
            text = stringResource(R.string.settings_notifications_web_desc),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow(
            title = stringResource(R.string.settings_background_notifications),
            description = stringResource(R.string.settings_background_notifications_desc),
            checked = settings.backgroundWebNotifications,
            onCheckedChange = { enabled ->
                container.settings.update { it.copy(backgroundWebNotifications = enabled) }
                if (enabled) {
                    WebNotificationBridge.ensureChannel(context)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !androidAllowed) {
                        runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                    }
                }
            },
        )
        Text(
            text = stringResource(R.string.settings_background_notifications_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!androidAllowed) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_notifications_permission_denied),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
                        } else {
                            runCatching {
                                context.startActivity(
                                    Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(
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
    }
}
