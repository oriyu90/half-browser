package com.yukiorita.halfbrowser.ui.browser

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.yukiorita.halfbrowser.R
import com.yukiorita.halfbrowser.core.TrustPolicy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Certificate interstitial. Replaces the WebView's built-in (blocking) SSL error
 * page: the user sees the actual certificate, can trust it once, or pin it for
 * this host. "Always trust" is unavailable when the fingerprint cannot be read,
 * because there would be nothing to pin.
 */
@Composable
fun SslInterstitialDialog(
    challenge: SslChallenge,
    onTrustOnce: () -> Unit,
    onTrustAlways: () -> Unit,
    onCancel: () -> Unit,
) {
    var showDetails by rememberSaveable(challenge.host) { mutableStateOf(challenge.changed) }

    Dialog(onDismissRequest = onCancel) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Filled.GppBad,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(36.dp),
                )
                Text(
                    text = stringResource(R.string.ssl_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(
                        if (challenge.changed) R.string.ssl_subtitle_changed else R.string.ssl_subtitle_unknown,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                InfoRow(stringResource(R.string.ssl_host), challenge.host)
                InfoRow(stringResource(R.string.ssl_problem), stringResource(sslErrorRes(challenge.primaryError)))

                if (showDetails) {
                    if (challenge.subject.isNotBlank()) {
                        InfoRow(stringResource(R.string.ssl_subject), challenge.subject)
                    }
                    if (challenge.issuer.isNotBlank()) {
                        InfoRow(stringResource(R.string.ssl_issuer), challenge.issuer)
                    }
                    challenge.fingerprintSha256?.let { fingerprint ->
                        InfoRow(
                            stringResource(R.string.ssl_fingerprint),
                            TrustPolicy.formatFingerprint(fingerprint),
                            monospace = true,
                        )
                    }
                    if (challenge.notAfter > 0L) {
                        val formatted = runCatching {
                            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(challenge.notAfter))
                        }.getOrNull()
                        if (!formatted.isNullOrBlank()) {
                            InfoRow(stringResource(R.string.ssl_validity), formatted)
                        }
                    }
                }

                TextButton(
                    onClick = { showDetails = !showDetails },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(
                            if (showDetails) R.string.ssl_details_hide else R.string.ssl_details_show,
                        ),
                    )
                }

                Text(
                    text = stringResource(R.string.ssl_scope_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(4.dp))

                Button(
                    onClick = onTrustAlways,
                    enabled = challenge.canPin && challenge.fingerprintSha256 != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.ssl_trust_always))
                }
                if (!challenge.canPin) {
                    Text(
                        text = stringResource(R.string.ssl_pin_unavailable),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedButton(onClick = onTrustOnce, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.ssl_trust_once))
                }
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.ssl_go_back))
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, monospace: Boolean = false) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (monospace) FontFamily.Monospace else null,
        )
    }
}

/**
 * HTTP authentication prompt. Shown only when nothing is saved for host+realm,
 * or when the saved credentials have already been rejected twice in this screen.
 */
@Composable
fun AuthPromptDialog(
    challenge: AuthChallenge,
    persistentStorage: Boolean,
    allowSave: Boolean,
    onSignIn: (username: String, password: String, save: Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    var username by rememberSaveable(challenge.host, challenge.realm) {
        mutableStateOf(challenge.suggestedUsername)
    }
    var password by rememberSaveable(challenge.host, challenge.realm) { mutableStateOf("") }
    var save by rememberSaveable(challenge.host, challenge.realm) { mutableStateOf(false) }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.auth_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.auth_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                InfoRow(stringResource(R.string.auth_host), challenge.host)
                if (challenge.realm.isNotBlank()) {
                    InfoRow(stringResource(R.string.auth_realm), challenge.realm)
                }
                if (challenge.failedBefore) {
                    Text(
                        text = stringResource(R.string.auth_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.auth_username)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.auth_password)) },
                    singleLine = true,
                    visualTransformation = if (showPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = stringResource(
                                    if (showPassword) R.string.auth_hide_password else R.string.auth_show_password,
                                ),
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = save && allowSave,
                        onCheckedChange = { save = it },
                        enabled = persistentStorage && allowSave,
                    )
                    Spacer(Modifier.width(4.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.auth_save),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = stringResource(
                                when {
                                    !persistentStorage -> R.string.auth_not_persistent
                                    !allowSave -> R.string.auth_insecure_http
                                    else -> R.string.auth_save_hint
                                },
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSignIn(username.trim(), password, save && persistentStorage && allowSave) },
                enabled = username.isNotBlank(),
            ) {
                Text(stringResource(R.string.auth_sign_in))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
