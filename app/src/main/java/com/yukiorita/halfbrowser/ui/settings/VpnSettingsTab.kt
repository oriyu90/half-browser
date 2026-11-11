package com.yukiorita.halfbrowser.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.yukiorita.halfbrowser.AppContainer
import com.yukiorita.halfbrowser.R
import com.yukiorita.halfbrowser.core.VpnConfigError
import com.yukiorita.halfbrowser.core.VpnHealthSnapshot
import com.yukiorita.halfbrowser.core.VpnReachability
import com.yukiorita.halfbrowser.core.VpnStatusRules
import com.yukiorita.halfbrowser.core.VpnConfigParser
import com.yukiorita.halfbrowser.core.VpnConfigResult
import com.yukiorita.halfbrowser.core.VpnNaming
import com.yukiorita.halfbrowser.core.VpnProfile
import com.yukiorita.halfbrowser.data.VpnFailure
import com.yukiorita.halfbrowser.data.VpnState
import com.yukiorita.halfbrowser.data.VpnStore
import com.yukiorita.halfbrowser.ui.home.headlineFor
import com.yukiorita.halfbrowser.ui.home.vpnAgeLabel
import com.yukiorita.halfbrowser.ui.home.vpnReachabilityLabel
import com.yukiorita.halfbrowser.ui.home.vpnStatusColor
import com.yukiorita.halfbrowser.ui.home.vpnStatusLabel
import java.util.UUID

/**
 * Settings → VPN.
 *
 * Everything that changes the tunnel lives here: add / edit / import / export /
 * delete a profile, choose the active one, and connect. The home screen shows
 * the compact version of the same state.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun VpnSettingsTab(container: AppContainer) {
    val context = LocalContext.current
    val profiles by container.vpnStore.profiles.collectAsStateWithLifecycle()
    val vpnState by container.vpn.state.collectAsStateWithLifecycle()
    val reachability by container.vpn.reachability.collectAsStateWithLifecycle()
    val health by container.vpn.health.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<VpnEditorTarget?>(null) }
    var deleting by remember { mutableStateOf<VpnProfile?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingConsent by remember { mutableStateOf<VpnProfile?>(null) }
    // A system query (`VpnService.prepare()`), so it is read once per
    // composition rather than on every recomposition.
    val consentGranted = remember { !container.vpn.needsSystemConsent() }
    var exportTarget by remember { mutableStateOf<VpnProfile?>(null) }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val profile = pendingConsent
        pendingConsent = null
        if (profile == null) return@rememberLauncherForActivityResult
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            container.vpn.connect(profile)
        } else {
            message = context.getString(R.string.vpn_consent_denied)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = readTextFile(context, uri)
        if (text == null) {
            message = context.getString(R.string.vpn_import_failed)
            return@rememberLauncherForActivityResult
        }
        val parsed = VpnConfigParser.parse(text)
        if (parsed !is VpnConfigResult.Ok) {
            message = configErrorText(context, parsed as VpnConfigResult.Error)
            return@rememberLauncherForActivityResult
        }
        val fallbackName = displayNameOf(context, uri) ?: context.getString(R.string.vpn_title)
        editing = VpnEditorTarget(
            existing = null,
            name = VpnNaming.sanitizeDisplayName(fallbackName, "VPN"),
            config = parsed.config.source,
        )
    }

    val qrLauncher = rememberLauncherForActivityResult(
        ScanContract(),
    ) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        if (text.length > VpnConfigParser.MAX_BYTES) {
            message = context.getString(R.string.vpn_qr_failed)
            return@rememberLauncherForActivityResult
        }
        // Same path as the file import: decode, then let the user review the
        // whole config in the editor before anything is stored. A scan never
        // connects or saves on its own.
        val parsed = VpnConfigParser.parse(text)
        if (parsed !is VpnConfigResult.Ok) {
            message = configErrorText(context, parsed as VpnConfigResult.Error)
            return@rememberLauncherForActivityResult
        }
        editing = VpnEditorTarget(
            existing = null,
            name = VpnNaming.sanitizeDisplayName(context.getString(R.string.vpn_qr_name), "VPN"),
            config = parsed.config.source,
        )
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val profile = exportTarget
        exportTarget = null
        if (uri == null || profile == null) return@rememberLauncherForActivityResult
        val ok = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(profile.configText.toByteArray(Charsets.UTF_8))
            } != null
        }.getOrDefault(false)
        message = context.getString(if (ok) R.string.vpn_export_done else R.string.vpn_export_failed)
    }

    fun connect(profile: VpnProfile) {
        container.settings.update { it.copy(vpnSelectedProfileId = profile.id) }
        if (container.vpn.needsSystemConsent()) {
            pendingConsent = profile
            val intent = container.vpn.consentIntent()
            if (intent == null) {
                // No consent dialog to wait for: clear the pending marker so
                // the Connect button is not disabled forever.
                pendingConsent = null
                container.vpn.connect(profile)
            } else {
                consentLauncher.launch(intent)
            }
        } else {
            container.vpn.connect(profile)
        }
    }

    LaunchedEffect(vpnState) {
        val failed = vpnState as? VpnState.Failed ?: return@LaunchedEffect
        message = context.getString(failureText(failed.reason))
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsCard(title = stringResource(R.string.vpn_title)) {
            Text(
                text = stringResource(R.string.vpn_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VpnStatusRow(
                state = vpnState,
                reachability = reachability,
                health = health,
                profiles = profiles,
                selectedId = settings.vpnSelectedProfileId,
                autoReconnect = settings.vpnAutoReconnect,
                consentGranted = consentGranted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val active = profiles.firstOrNull { it.id == vpnState.profileId }
                        if (vpnState.isActive && active != null) {
                            container.vpn.disconnect()
                        } else {
                            val target = profiles.firstOrNull { it.id == settings.vpnSelectedProfileId }
                                ?: profiles.firstOrNull()
                            if (target != null) connect(target) else message = context.getString(R.string.vpn_select_hint)
                        }
                    },
                    enabled = profiles.isNotEmpty() &&
                        pendingConsent == null &&
                        vpnState !is VpnState.Connecting &&
                        vpnState !is VpnState.Disconnecting,
                ) {
                    if (vpnState is VpnState.Connecting || vpnState is VpnState.Disconnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        stringResource(
                            if (vpnState.isActive) R.string.vpn_action_disconnect else R.string.vpn_action_connect,
                        ),
                    )
                }
                if (vpnState is VpnState.Connected) {
                    OutlinedButton(onClick = { container.vpn.reconnect() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.vpn_action_reconnect))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(stringResource(R.string.vpn_app_only), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.vpn_app_only_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SettingsCard(title = stringResource(R.string.vpn_profiles)) {
            if (profiles.isEmpty()) {
                Text(
                    text = stringResource(R.string.vpn_empty_title),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.vpn_empty_body),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                profiles.forEach { profile ->
                    VpnProfileRow(
                        profile = profile,
                        selected = profile.id == settings.vpnSelectedProfileId,
                        active = vpnState.profileId == profile.id && vpnState.isActive,
                        onSelect = {
                            container.settings.update { it.copy(vpnSelectedProfileId = profile.id) }
                        },
                        onEdit = { editing = VpnEditorTarget(profile, profile.name, profile.configText) },
                        onExport = {
                            exportTarget = profile
                            exportLauncher.launch(exportFileName(profile))
                        },
                        onDelete = { deleting = profile },
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        if (profiles.size >= VpnStore.MAX_PROFILES) {
                            message = context.getString(R.string.vpn_profile_limit, VpnStore.MAX_PROFILES)
                        } else {
                            editing = VpnEditorTarget(null, "", "")
                        }
                    },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.vpn_action_add))
                }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Filled.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.vpn_action_import))
                }
                OutlinedButton(
                    onClick = {
                        if (profiles.size >= VpnStore.MAX_PROFILES) {
                            message = context.getString(R.string.vpn_profile_limit, VpnStore.MAX_PROFILES)
                        } else {
                            val options = ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt(context.getString(R.string.vpn_scan_prompt))
                                .setBeepEnabled(false)
                                .setOrientationLocked(false)
                            qrLauncher.launch(options)
                        }
                    },
                ) {
                    Icon(Icons.Filled.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.vpn_action_scan_qr))
                }
            }
        }

        SettingsCard(title = stringResource(R.string.vpn_auto_connect)) {
            SettingsSwitchRow(
                title = stringResource(R.string.vpn_auto_connect),
                description = stringResource(R.string.vpn_auto_connect_desc),
                checked = settings.vpnAutoConnect,
                onCheckedChange = { enabled ->
                    container.settings.update { it.copy(vpnAutoConnect = enabled) }
                },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.vpn_auto_reconnect),
                description = stringResource(R.string.vpn_auto_reconnect_desc),
                checked = settings.vpnAutoReconnect,
                onCheckedChange = { enabled ->
                    container.settings.update { it.copy(vpnAutoReconnect = enabled) }
                },
            )
        }
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = {
                TextButton(onClick = { message = null }) {
                    Text(stringResource(R.string.action_close))
                }
            },
            text = { Text(text) },
        )
    }

    editing?.let { target ->
        VpnEditorDialog(
            target = target,
            existingNames = profiles.filter { it.id != target.existing?.id }.map { it.name },
            onDismiss = { editing = null },
            onSave = { name, configText ->
                // Returns null on success, otherwise the message the editor
                // shows inline. Failures must NOT go through `message`: that
                // dialog would stack on top of the editor the user is in.
                val parsed = VpnConfigParser.parse(configText) as? VpnConfigResult.Ok
                if (parsed == null) {
                    return@VpnEditorDialog configErrorText(
                        context,
                        VpnConfigParser.parse(configText) as VpnConfigResult.Error,
                    )
                }
                val now = System.currentTimeMillis()
                val existing = target.existing
                val profile = if (existing == null) {
                    val added = VpnProfile(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        configText = parsed.config.source,
                        endpoint = parsed.config.endpoint,
                        addresses = parsed.config.addresses,
                        dns = parsed.config.dns,
                        createdAt = now,
                        updatedAt = now,
                    )
                    // add() returns null at MAX_PROFILES and on a write
                    // failure; neither may be reported as "saved".
                    container.vpnStore.add(added) ?: return@VpnEditorDialog (
                        if (profiles.size >= VpnStore.MAX_PROFILES) {
                            context.getString(R.string.vpn_profile_limit, VpnStore.MAX_PROFILES)
                        } else {
                            context.getString(R.string.vpn_profile_save_failed)
                        }
                    )
                } else {
                    val updated = existing.copy(
                        name = name,
                        configText = parsed.config.source,
                        endpoint = parsed.config.endpoint,
                        addresses = parsed.config.addresses,
                        dns = parsed.config.dns,
                        updatedAt = now,
                    )
                    if (!container.vpnStore.update(updated)) {
                        return@VpnEditorDialog (
                            if (container.vpnStore.find(updated.id) == null) {
                                context.getString(R.string.vpn_failure_profile_missing)
                            } else {
                                context.getString(R.string.vpn_profile_save_failed)
                            }
                        )
                    }
                    container.vpn.onProfileUpdated(updated)
                    updated
                }
                if (settings.vpnSelectedProfileId.isBlank()) {
                    container.settings.update { it.copy(vpnSelectedProfileId = profile.id) }
                }
                editing = null
                message = context.getString(R.string.vpn_profile_saved)
                null
            },
        )
    }

    deleting?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.vpn_profile_delete_title)) },
            text = { Text(stringResource(R.string.vpn_profile_delete_message, profile.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        container.vpn.onProfileRemoved(profile.id)
                        container.vpnStore.delete(profile.id)
                        if (settings.vpnSelectedProfileId == profile.id) {
                            container.settings.update {
                                it.copy(
                                    vpnSelectedProfileId = profiles.firstOrNull { p -> p.id != profile.id }?.id
                                        ?: "",
                                )
                            }
                        }
                        deleting = null
                        message = context.getString(R.string.vpn_profile_deleted)
                    },
                ) {
                    Text(stringResource(R.string.vpn_action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

internal data class VpnEditorTarget(
    val existing: VpnProfile?,
    val name: String,
    val config: String,
)

@Composable
private fun VpnStatusRow(
    state: VpnState,
    reachability: VpnReachability,
    health: VpnHealthSnapshot,
    profiles: List<VpnProfile>,
    selectedId: String,
    autoReconnect: Boolean,
    consentGranted: Boolean,
) {
    val headline = headlineFor(state, reachability, autoReconnect, consentGranted)
    val color = vpnStatusColor(headline)
    val profileName = profiles.firstOrNull { it.id == state.profileId }?.name
    val label = if (state is VpnState.Connected && profileName != null) {
        stringResource(R.string.vpn_status_connected_to, profileName)
    } else {
        vpnStatusLabel(headline)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(8.dp))
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = color)
            if (state is VpnState.Disconnected && selectedId.isNotBlank() &&
                profiles.none { it.id == selectedId }
            ) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.vpn_home_no_selection),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state is VpnState.Connected) {
            // Reachability is the honest part: "connected" alone does not mean
            // the server is answering.
            Text(
                text = vpnReachabilityLabel(reachability),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val lastCheck = vpnAgeLabel(health.checkedAt, System.currentTimeMillis())
            if (lastCheck != null) {
                Text(
                    text = stringResource(R.string.vpn_detail_checked_value, lastCheck),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun VpnProfileRow(
    profile: VpnProfile,
    selected: Boolean,
    active: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (active) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Filled.PowerSettingsNew,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (selected) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = stringResource(R.string.vpn_selected),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = profile.endpoint.ifBlank { profile.addresses.joinToString(", ") },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.vpn_action_edit),
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onExport, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.FileDownload,
                    contentDescription = stringResource(R.string.vpn_action_export),
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.vpn_action_delete),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun VpnEditorDialog(
    target: VpnEditorTarget,
    existingNames: List<String>,
    onDismiss: () -> Unit,
    /**
     * Attempts the save; returns null on success or the message to show
     * inline in the editor. Failures never open a second dialog on top of
     * this one.
     */
    onSave: (name: String, config: String) -> String?,
) {
    var name by remember { mutableStateOf(target.name) }
    var config by remember { mutableStateOf(target.config) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val dirty = name != target.name || config != target.config
    // Resolved here (composable scope) because onClick is not composable.
    val blankNameError = stringResource(R.string.vpn_error_name_blank)
    val duplicateNameError = stringResource(R.string.vpn_error_name_duplicate)

    fun requestDismiss() {
        if (dirty) confirmDiscard = true else onDismiss()
    }

    AlertDialog(
        onDismissRequest = { requestDismiss() },
        title = {
            Text(
                stringResource(
                    if (target.existing == null) R.string.vpn_action_add else R.string.vpn_action_edit,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(VpnConfigParser.MAX_NAME_LENGTH); error = null },
                    label = { Text(stringResource(R.string.vpn_profile_name)) },
                    placeholder = { Text(stringResource(R.string.vpn_profile_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = config,
                    onValueChange = { config = it; error = null },
                    label = { Text(stringResource(R.string.vpn_profile_config)) },
                    placeholder = {
                        Text(
                            stringResource(R.string.vpn_profile_config_hint),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    },
                    minLines = 6,
                    maxLines = 12,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val trimmedName = name.trim()
                    if (trimmedName.isBlank()) {
                        error = blankNameError
                        return@TextButton
                    }
                    if (existingNames.any { it.equals(trimmedName, ignoreCase = true) }) {
                        error = duplicateNameError
                        return@TextButton
                    }
                    error = onSave(trimmedName, config)
                },
            ) {
                Text(stringResource(R.string.vpn_action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = { requestDismiss() }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.vpn_unsaved_changes)) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onDismiss() }) {
                    Text(stringResource(R.string.vpn_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) {
                    Text(stringResource(R.string.vpn_keep_editing))
                }
            },
        )
    }
}

/** Maps a parser error to a localized message (with the offending text). */
internal fun configErrorText(context: Context, error: VpnConfigResult.Error): String {
    val resId = when (error.reason) {
        VpnConfigError.EMPTY -> R.string.vpn_error_empty
        VpnConfigError.TOO_LARGE -> R.string.vpn_error_too_large
        VpnConfigError.MISSING_INTERFACE -> R.string.vpn_error_missing_interface
        VpnConfigError.MISSING_PEER -> R.string.vpn_error_missing_peer
        VpnConfigError.MISSING_PRIVATE_KEY -> R.string.vpn_error_missing_private_key
        VpnConfigError.INVALID_PRIVATE_KEY -> R.string.vpn_error_invalid_private_key
        VpnConfigError.MISSING_PUBLIC_KEY -> R.string.vpn_error_missing_public_key
        VpnConfigError.INVALID_PUBLIC_KEY -> R.string.vpn_error_invalid_public_key
        VpnConfigError.INVALID_PRESHARED_KEY -> R.string.vpn_error_invalid_preshared_key
        VpnConfigError.MISSING_ENDPOINT -> R.string.vpn_error_missing_endpoint
        VpnConfigError.INVALID_ENDPOINT -> R.string.vpn_error_invalid_endpoint
        VpnConfigError.MISSING_ALLOWED_IPS -> R.string.vpn_error_missing_allowed_ips
        VpnConfigError.INVALID_ALLOWED_IPS -> R.string.vpn_error_invalid_allowed_ips
        VpnConfigError.INVALID_ADDRESS -> R.string.vpn_error_invalid_address
        VpnConfigError.INVALID_DNS -> R.string.vpn_error_invalid_dns
        VpnConfigError.INVALID_PORT -> R.string.vpn_error_invalid_port
        VpnConfigError.INVALID_MTU -> R.string.vpn_error_invalid_mtu
        VpnConfigError.INVALID_KEEPALIVE -> R.string.vpn_error_invalid_keepalive
        VpnConfigError.UNKNOWN_KEY -> R.string.vpn_error_unknown_key
        VpnConfigError.DUPLICATE_SECTION -> R.string.vpn_error_duplicate_section
        VpnConfigError.MALFORMED_LINE -> R.string.vpn_error_malformed_line
    }
    val detail = error.detail.take(80)
    return if (resId == R.string.vpn_error_unknown_key ||
        resId == R.string.vpn_error_duplicate_section ||
        resId == R.string.vpn_error_malformed_line
    ) {
        context.getString(resId, detail)
    } else {
        context.getString(resId)
    }
}

/** Maps a runtime failure to a localized message. */
internal fun failureText(reason: VpnFailure): Int = when (reason) {
    VpnFailure.CONSENT_REQUIRED -> R.string.vpn_failure_consent
    VpnFailure.DNS -> R.string.vpn_failure_dns
    VpnFailure.TUN -> R.string.vpn_failure_tun
    VpnFailure.SERVICE -> R.string.vpn_failure_service
    VpnFailure.ACTIVATION -> R.string.vpn_failure_activation
    VpnFailure.BAD_CONFIG -> R.string.vpn_failure_bad_config
    VpnFailure.PROFILE_MISSING -> R.string.vpn_failure_profile_missing
    VpnFailure.UNKNOWN -> R.string.vpn_failure_unknown
}

private fun exportFileName(profile: VpnProfile): String {
    val safe = profile.name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "vpn" }
    return "$safe.conf"
}

/**
 * Filename of a picked document without its extension.
 *
 * `Uri.lastPathSegment` is a document id such as `msf:1000000063` for the
 * system picker, not a name, so the display name is resolved through the
 * content resolver and only then falls back to the path segment.
 */
private fun displayNameOf(context: Context, uri: Uri): String? {
    val fromResolver = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()
    val raw = fromResolver ?: uri.lastPathSegment
    val base = raw?.substringAfterLast('/')?.substringBeforeLast('.')?.trim()
    return base?.takeIf { it.isNotBlank() && !it.contains(':') }
}

private fun readTextFile(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        val bytes = stream.readBytes()
        if (bytes.size > VpnConfigParser.MAX_BYTES * 2) return null
        String(bytes, Charsets.UTF_8)
    }
}.getOrNull()

/** Thin alias so the settings screen keeps one switch style. */
@Composable
private fun SettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
