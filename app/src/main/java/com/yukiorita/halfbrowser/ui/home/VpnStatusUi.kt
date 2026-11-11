package com.yukiorita.halfbrowser.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShieldMoon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yukiorita.halfbrowser.AppContainer
import com.yukiorita.halfbrowser.R
import com.yukiorita.halfbrowser.core.VpnAge
import com.yukiorita.halfbrowser.core.VpnReachability
import com.yukiorita.halfbrowser.core.VpnStatusHeadline
import com.yukiorita.halfbrowser.core.VpnStatusRules
import com.yukiorita.halfbrowser.data.VpnState

/**
 * Status colour for the VPN badge. Green (primary) only when traffic is
 * actually flowing; amber while verifying or degraded, red on failure, grey
 * when off. Never green on tunnel-up alone — that was the misleading part.
 */
@Composable
internal fun vpnStatusColor(headline: VpnStatusHeadline): Color = when (headline) {
    VpnStatusHeadline.CONNECTED -> MaterialTheme.colorScheme.primary
    VpnStatusHeadline.VERIFYING, VpnStatusHeadline.CONNECTING, VpnStatusHeadline.DISCONNECTING,
    VpnStatusHeadline.RECONNECTING -> MaterialTheme.colorScheme.tertiary
    VpnStatusHeadline.DEGRADED -> MaterialTheme.colorScheme.secondary
    VpnStatusHeadline.FAILED -> MaterialTheme.colorScheme.error
    // Off is always neutral: a broken *previous* session is history, and the
    // detail sheet explains it. Colouring the off state by history would make
    // "off" look like an error.
    VpnStatusHeadline.OFF -> MaterialTheme.colorScheme.outline
}

/**
 * The badge headline for a state, including the "the app is working on it"
 * case: a tunnel that is up but not answering, with automatic recovery
 * enabled, reads as "reconnecting" rather than "not responding".
 */
internal fun headlineFor(
    state: VpnState,
    reachability: VpnReachability,
    autoReconnect: Boolean,
    consentGranted: Boolean,
): VpnStatusHeadline = VpnStatusRules.headline(
    tunnelUp = state is VpnState.Connected,
    reachability = reachability,
    autoReconnecting = state is VpnState.Connected &&
        VpnStatusRules.isAutoRecovering(reachability, autoReconnect, consentGranted),
    connecting = state is VpnState.Connecting,
    disconnecting = state is VpnState.Disconnecting,
    failed = state is VpnState.Failed,
)

@Composable
internal fun vpnStatusLabel(headline: VpnStatusHeadline): String = stringResource(
    when (headline) {
        VpnStatusHeadline.OFF -> R.string.vpn_badge_off
        VpnStatusHeadline.CONNECTING -> R.string.vpn_badge_connecting
        VpnStatusHeadline.DISCONNECTING -> R.string.vpn_badge_disconnecting
        VpnStatusHeadline.CONNECTED -> R.string.vpn_badge_connected
        VpnStatusHeadline.VERIFYING -> R.string.vpn_badge_verifying
        VpnStatusHeadline.DEGRADED -> R.string.vpn_badge_degraded
        VpnStatusHeadline.RECONNECTING -> R.string.vpn_badge_reconnecting
        VpnStatusHeadline.FAILED -> R.string.vpn_badge_failed
    },
)

@Composable
internal fun vpnReachabilityLabel(reachability: VpnReachability): String = stringResource(
    when (reachability) {
        VpnReachability.UNKNOWN -> R.string.vpn_reach_unknown
        VpnReachability.VERIFYING -> R.string.vpn_reach_verifying
        VpnReachability.HEALTHY -> R.string.vpn_reach_healthy
        VpnReachability.STALE -> R.string.vpn_reach_stale
        VpnReachability.UNREACHABLE -> R.string.vpn_reach_unreachable
        VpnReachability.TUNNEL_ONLY -> R.string.vpn_reach_tunnel_only
    },
)

/** "3 min ago" / "たった今" / null when unknown. */
@Composable
internal fun vpnAgeLabel(timestamp: Long, now: Long): String? {
    if (timestamp <= 0L) return null
    return when (val age = VpnStatusRules.ageText(now - timestamp)) {
        null -> null
        VpnAge.JUST_NOW -> stringResource(R.string.vpn_age_just_now)
        is VpnAge.MINUTES -> stringResource(R.string.vpn_age_minutes, age.count)
        is VpnAge.HOURS -> stringResource(R.string.vpn_age_hours, age.count)
        is VpnAge.DAYS -> stringResource(R.string.vpn_age_days, age.count)
    }
}

/**
 * The home-screen status badge: a shield, the state word, and (when connected)
 * the profile name. Tapping it opens the detail sheet.
 */
@Composable
internal fun HomeVpnBadge(
    state: VpnState,
    reachability: VpnReachability,
    profileName: String?,
    modifier: Modifier = Modifier,
    autoReconnect: Boolean = false,
    consentGranted: Boolean = true,
) {
    val headline = headlineFor(state, reachability, autoReconnect, consentGranted)
    val color = vpnStatusColor(headline)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (state.isActive) Icons.Filled.Shield else Icons.Filled.ShieldMoon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = color,
        )
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                text = vpnStatusLabel(headline),
                style = MaterialTheme.typography.labelMedium,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (profileName != null) {
                Text(
                    text = profileName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Detail sheet: tunnel state, reachability, handshake age, last check, the
 * automatic-reconnect switch, and the two actions that fix a broken tunnel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VpnDetailSheet(
    container: AppContainer,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vpnState by container.vpn.state.collectAsStateWithLifecycle()
    val reachability by container.vpn.reachability.collectAsStateWithLifecycle()
    val health by container.vpn.health.collectAsStateWithLifecycle()
    val profiles by container.vpnStore.profiles.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle()
    // Read once when the sheet opens: `VpnService.prepare()` queries the system
    // and must not run on every recomposition.
    val consentGranted = remember { !container.vpn.needsSystemConsent() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Ages are computed against the moment the sheet opened; every state
    // emission (the 5 s health poll) recomposes and refreshes it.
    val now = remember(health.checkedAt, vpnState) { System.currentTimeMillis() }

    val headline = headlineFor(vpnState, reachability, settings.vpnAutoReconnect, consentGranted)
    val color = vpnStatusColor(headline)
    val activeProfile = profiles.firstOrNull { it.id == vpnState.profileId }
    val selectedProfile = profiles.firstOrNull { it.id == settings.vpnSelectedProfileId }
        ?: profiles.firstOrNull()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(color),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = vpnStatusLabel(headline),
                    style = MaterialTheme.typography.titleMedium,
                    color = color,
                )
            }
            Text(
                text = stringResource(R.string.vpn_detail_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            DetailRow(
                label = stringResource(R.string.vpn_detail_profile),
                value = activeProfile?.name ?: selectedProfile?.name
                    ?: stringResource(R.string.vpn_empty_title),
            )
            DetailRow(
                label = stringResource(R.string.vpn_detail_reachability),
                value = vpnReachabilityLabel(reachability),
            )
            DetailRow(
                label = stringResource(R.string.vpn_detail_handshake),
                value = vpnAgeLabel(health.lastHandshakeAt, now)
                    ?: stringResource(R.string.vpn_detail_never),
            )
            DetailRow(
                label = stringResource(R.string.vpn_detail_checked),
                value = vpnAgeLabel(health.checkedAt, now)
                    ?: stringResource(R.string.vpn_detail_never),
            )

            if (reachability == VpnReachability.UNREACHABLE || reachability == VpnReachability.STALE) {
                Text(
                    text = stringResource(
                        if (reachability == VpnReachability.UNREACHABLE) {
                            R.string.vpn_detail_hint_unreachable
                        } else {
                            R.string.vpn_detail_hint_degraded
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.vpn_auto_reconnect),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.vpn_auto_reconnect_desc),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.vpnAutoReconnect,
                    onCheckedChange = { enabled ->
                        container.settings.update { it.copy(vpnAutoReconnect = enabled) }
                    },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vpnState is VpnState.Connected) {
                    OutlinedButton(onClick = { container.vpn.reconnect() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.vpn_detail_reconnect_now))
                    }
                } else if (selectedProfile != null) {
                    OutlinedButton(
                        onClick = {
                            val consent = container.vpn.consentIntent()
                            if (consent != null) {
                                // The sheet cannot host an activity result; send the
                                // user to Settings → VPN, which owns the consent flow.
                                onOpenSettings()
                            } else {
                                container.vpn.connect(selectedProfile)
                            }
                        },
                    ) {
                        Text(stringResource(R.string.vpn_action_connect))
                    }
                }
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.vpn_home_manage))
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
