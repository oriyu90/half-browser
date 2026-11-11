package com.yukiorita.halfbrowser.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
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
import com.yukiorita.halfbrowser.ui.settings.failureText

/**
 * Compact VPN panel pinned to the bottom of the home screen.
 *
 * It is the place the user actually connects from, so it shows:
 *  - a status badge that distinguishes "tunnel up" from "traffic flowing"
 *    (green only after a handshake, amber while verifying, "not responding"
 *    when the server went silent); tapping it opens the detail sheet;
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
    val reachability by container.vpn.reachability.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()

    // Whether the system consent was already granted; a system query, so it is
    // read once per composition rather than on every recomposition.
    val consentGranted = remember { !container.vpn.needsSystemConsent() }

    var expanded by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
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

    // Show the failure reason, not just the red dot (same mapping as the
    // settings tab, via the shared internal failureText).
    LaunchedEffect(vpnState) {
        val failed = vpnState as? VpnState.Failed ?: return@LaunchedEffect
        notice = context.getString(failureText(failed.reason))
    }

    // A stored selection id that no longer exists means "no selection", in
    // the header and in the rows alike (the settings tab shows the same).
    val selected = profiles.firstOrNull { it.id == settings.vpnSelectedProfileId }
    // Connecting still falls back to the first profile, as in the settings
    // tab — only the *display* is unified on "unselected".
    val connectTarget = selected ?: profiles.firstOrNull()
    val connectedProfile = profiles.firstOrNull { it.id == vpnState.profileId }
    // While the system consent dialog is up, block further taps: a second
    // connect would overwrite the single pendingConsent slot.
    val busy = vpnState is VpnState.Connecting || vpnState is VpnState.Disconnecting ||
        pendingConsent != null

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        tonalElevation = 3.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The badge is the answer to "is my traffic going through the
                // VPN right now?": it turns green only when a handshake has
                // completed, amber while verifying, and shows "not responding"
                // when the tunnel is up but the server went silent.
                Column(modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier.clickable { showDetails = true },
                    ) {
                        HomeVpnBadge(
                            state = vpnState,
                            reachability = reachability,
                            autoReconnect = settings.vpnAutoReconnect,
                            consentGranted = consentGranted,
                            profileName = when {
                                connectedProfile != null -> connectedProfile.name
                                profiles.isEmpty() -> null
                                selected != null -> selected.name
                                else -> null
                            },
                        )
                    }
                    if (profiles.isEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.vpn_home_not_configured),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
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
                            if (connectTarget != null) {
                                connect(connectTarget)
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
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    profiles.forEach { profile ->
                        val isSelected = profile.id == settings.vpnSelectedProfileId
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

    if (showDetails) {
        VpnDetailSheet(
            container = container,
            onDismiss = { showDetails = false },
            onOpenSettings = {
                showDetails = false
                onOpenSettings()
            },
        )
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
