package com.yukiorita.halfbrowser.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShieldMoon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yukiorita.halfbrowser.AppContainer
import com.yukiorita.halfbrowser.R
import com.yukiorita.halfbrowser.core.VpnProfile
import com.yukiorita.halfbrowser.data.VpnState

/**
 * Compact VPN panel pinned to the bottom of the home screen.
 *
 * It is the place the user actually connects from, so it shows:
 *  - the live status (dot + text) of the tunnel;
 *  - connect / disconnect, and reconnect while connected;
 *  - the registered profiles, expandable, with the active one marked.
 *
 * The panel is collapsed to a single row by default so it never crowds the
 * bookmark grid; the choice is remembered for the session.
 */
@Composable
internal fun HomeVpnPanel(
    container: AppContainer,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val profiles by container.vpnStore.profiles.collectAsStateWithLifecycle()
    val vpnState by container.vpn.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()

    var expanded by rememberSaveable { mutableStateOf(false) }
    var pendingConsent by remember { mutableStateOf<VpnProfile?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val profile = pendingConsent
        pendingConsent = null
        if (profile == null) return@rememberLauncherForActivityResult
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            container.vpn.connect(profile)
        } else {
            notice = context.getString(R.string.vpn_consent_denied)
        }
    }

    fun connect(profile: VpnProfile) {
        container.settings.update { it.copy(vpnSelectedProfileId = profile.id) }
        val consent = container.vpn.consentIntent()
        if (consent != null) {
            pendingConsent = profile
            consentLauncher.launch(consent)
        } else {
            container.vpn.connect(profile)
        }
    }

    val selected = profiles.firstOrNull { it.id == settings.vpnSelectedProfileId }
        ?: profiles.firstOrNull()
    val connectedProfile = profiles.firstOrNull { it.id == vpnState.profileId }
    val busy = vpnState is VpnState.Connecting || vpnState is VpnState.Disconnecting

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        tonalElevation = 3.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (vpnState.isActive) Icons.Filled.Shield else Icons.Filled.ShieldMoon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = statusColor(vpnState),
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor(vpnState)),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = statusLabel(vpnState, connectedProfile?.name),
                            style = MaterialTheme.typography.labelLarge,
                            color = statusColor(vpnState),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = when {
                            profiles.isEmpty() -> stringResource(R.string.vpn_home_not_configured)
                            selected != null -> selected.name
                            else -> stringResource(R.string.vpn_home_no_selection)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                }

                // Connect / disconnect: the primary action.
                if (vpnState.isActive) {
                    OutlinedButton(
                        onClick = { container.vpn.disconnect() },
                        enabled = !busy,
                    ) {
                        Text(stringResource(R.string.vpn_action_disconnect))
                    }
                } else {
                    FilledTonalButton(
                        onClick = {
                            if (selected != null) {
                                connect(selected)
                            } else {
                                onOpenSettings()
                            }
                        },
                        enabled = !busy,
                    ) {
                        Text(
                            stringResource(
                                if (profiles.isEmpty()) R.string.vpn_action_add else R.string.vpn_action_connect,
                            ),
                        )
                    }
                }

                // Reconnect: only while connected.
                if (vpnState is VpnState.Connected) {
                    IconButton(onClick = { container.vpn.reconnect() }, enabled = !busy) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.vpn_action_reconnect),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                IconButton(onClick = { expanded = !expanded }, enabled = profiles.isNotEmpty()) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                        contentDescription = stringResource(
                            if (expanded) R.string.vpn_home_collapse else R.string.vpn_home_expand,
                        ),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            notice?.let { text ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { notice = null }) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            }

            AnimatedVisibility(
                visible = expanded && profiles.isNotEmpty(),
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    profiles.forEach { profile ->
                        val isSelected = profile.id == (settings.vpnSelectedProfileId.ifBlank { selected?.id })
                        val isConnected = vpnState.profileId == profile.id && vpnState.isActive
                        VpnMiniRow(
                            profile = profile,
                            selected = isSelected,
                            connected = isConnected,
                            onClick = {
                                container.settings.update { it.copy(vpnSelectedProfileId = profile.id) }
                                if (vpnState.isActive && !isConnected) connect(profile)
                            },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.vpn_app_only),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onOpenSettings) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.vpn_home_manage))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VpnMiniRow(
    profile: VpnProfile,
    selected: Boolean,
    connected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (connected) Icons.Filled.PowerSettingsNew else Icons.Filled.Shield,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (profile.endpoint.isNotBlank()) {
                Text(
                    text = profile.endpoint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = stringResource(R.string.vpn_selected),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun statusLabel(state: VpnState, name: String?): String = when (state) {
    is VpnState.Connected ->
        if (name != null) stringResource(R.string.vpn_status_connected_to, name) else stringResource(R.string.vpn_status_connected)
    is VpnState.Connecting -> stringResource(R.string.vpn_status_connecting)
    is VpnState.Disconnecting -> stringResource(R.string.vpn_status_disconnecting)
    is VpnState.Failed -> stringResource(R.string.vpn_status_failed)
    VpnState.Disconnected -> stringResource(R.string.vpn_status_disconnected)
}

@Composable
private fun statusColor(state: VpnState): Color = when (state) {
    is VpnState.Connected -> MaterialTheme.colorScheme.primary
    is VpnState.Connecting, is VpnState.Disconnecting -> MaterialTheme.colorScheme.tertiary
    is VpnState.Failed -> MaterialTheme.colorScheme.error
    VpnState.Disconnected -> MaterialTheme.colorScheme.outline
}
