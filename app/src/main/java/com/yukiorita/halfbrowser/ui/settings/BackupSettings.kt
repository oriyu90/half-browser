package com.yukiorita.halfbrowser.ui.settings

import android.app.Activity
import android.widget.Toast
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.yukiorita.halfbrowser.AppContainer
import com.yukiorita.halfbrowser.BuildConfig
import com.yukiorita.halfbrowser.R
import com.yukiorita.halfbrowser.core.BackupCrypto
import com.yukiorita.halfbrowser.core.BackupDecodeResult
import com.yukiorita.halfbrowser.core.BackupError
import com.yukiorita.halfbrowser.core.BackupFile
import com.yukiorita.halfbrowser.core.LanguageMode
import com.yukiorita.halfbrowser.data.BackupService
import com.yukiorita.halfbrowser.data.ImportMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class PassphraseTarget { EXPORT, IMPORT }

/**
 * Export/import card. All file access goes through SAF: the user picks the
 * destination or the source document, so the app needs no storage permission.
 */
@Composable
fun BackupSettingsCard(
    container: AppContainer,
    onImported: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember(container) { BackupService(context.applicationContext, container) }

    var busy by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var includePasswords by remember { mutableStateOf(false) }
    var pendingJson by remember { mutableStateOf<String?>(null) }
    var passphraseTarget by remember { mutableStateOf<PassphraseTarget?>(null) }
    var pendingImport by remember { mutableStateOf<BackupFile?>(null) }

    fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
    }

    fun toast(resId: Int) = toast(context.getString(resId))

    fun errorText(error: BackupError): Int = when (error) {
        BackupError.EMPTY -> R.string.settings_backup_error_empty
        BackupError.TOO_LARGE -> R.string.settings_backup_error_large
        BackupError.INVALID_JSON -> R.string.settings_backup_error_invalid
        BackupError.UNSUPPORTED_FORMAT -> R.string.settings_backup_error_format
        BackupError.UNSUPPORTED_VERSION -> R.string.settings_backup_error_version
    }

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val json = pendingJson
        pendingJson = null
        if (uri == null || json == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val ok = withContext(Dispatchers.IO) { service.write(uri, json) }
            busy = false
            toast(if (ok) R.string.settings_backup_export_ok else R.string.settings_backup_export_failed)
        }
    }

    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) { service.read(uri) }
            busy = false
            when (result) {
                is BackupDecodeResult.Success -> pendingImport = result.file
                is BackupDecodeResult.Failure -> toast(errorText(result.error))
            }
        }
    }

    fun startExport(passphrase: CharArray?) {
        val include = passphrase != null
        scope.launch {
            busy = true
            val json = withContext(Dispatchers.IO) {
                service.buildBackup(include, passphrase, BuildConfig.VERSION_NAME)
            }
            busy = false
            if (json == null) {
                toast(R.string.settings_backup_export_failed)
            } else {
                pendingJson = json
                createDocument.launch(service.suggestedFileName())
            }
        }
    }

    Text(
        text = stringResource(R.string.settings_backup_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            enabled = !busy,
            onClick = {
                includePasswords = false
                showExportDialog = true
            },
        ) {
            Text(stringResource(R.string.settings_backup_export))
        }
        TextButton(
            enabled = !busy,
            onClick = { openDocument.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
        ) {
            Text(stringResource(R.string.settings_backup_import))
        }
        if (busy) {
            Spacer(Modifier.width(8.dp))
            LinearProgressIndicator(modifier = Modifier.weight(1f))
        }
    }
    Text(
        text = stringResource(R.string.settings_backup_note),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (showExportDialog) {
        val credentialCount = remember { container.credentials.all().size }
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text(stringResource(R.string.settings_backup_export_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (credentialCount > 0) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { includePasswords = !includePasswords },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = includePasswords,
                                onCheckedChange = { includePasswords = it },
                            )
                            Text(
                                text = stringResource(
                                    R.string.settings_backup_include_passwords,
                                    credentialCount,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text(
                            text = stringResource(R.string.settings_backup_include_passwords_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.settings_backup_no_passwords),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExportDialog = false
                        if (includePasswords && credentialCount > 0) {
                            passphraseTarget = PassphraseTarget.EXPORT
                        } else {
                            startExport(null)
                        }
                    },
                ) {
                    Text(stringResource(R.string.settings_backup_export_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (passphraseTarget == PassphraseTarget.EXPORT) {
        PassphraseDialog(
            title = stringResource(R.string.settings_backup_passphrase_set_title),
            confirmLabel = stringResource(R.string.settings_backup_export_continue),
            requireConfirmation = true,
            onDismiss = { passphraseTarget = null },
            onConfirm = { secret ->
                passphraseTarget = null
                startExport(secret)
            },
        )
    }

    pendingImport?.let { file ->
        ImportDialog(
            file = file,
            onDismiss = { pendingImport = null },
            onConfirm = { mode, passphrase ->
                pendingImport = null
                val previousLanguage = container.settings.current().language
                scope.launch {
                    busy = true
                    val outcome = withContext(Dispatchers.IO) {
                        service.apply(file, mode, passphrase)
                    }
                    busy = false
                    if (outcome.error != null) {
                        toast(errorText(outcome.error))
                    } else {
                        val message = context.getString(
                            R.string.settings_backup_import_ok,
                            outcome.bookmarks,
                            outcome.trustedSites,
                            outcome.watches,
                            outcome.credentials,
                        )
                        toast(
                            if (outcome.credentialsSkipped) {
                                message + " " + context.getString(R.string.settings_backup_credentials_skipped)
                            } else {
                                message
                            },
                        )
                        onImported()
                        val newLanguage: LanguageMode = container.settings.current().language
                        if (newLanguage != previousLanguage) {
                            (context as? Activity)?.recreate()
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun ImportDialog(
    file: BackupFile,
    onDismiss: () -> Unit,
    onConfirm: (ImportMode, CharArray?) -> Unit,
) {
    var mode by remember { mutableStateOf(ImportMode.MERGE) }
    var replaceConfirmed by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var showPassphrase by remember { mutableStateOf(false) }

    val exportedAt = remember(file.exportedAt) {
        if (file.exportedAt <= 0L) {
            ""
        } else {
            runCatching {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(file.exportedAt))
            }.getOrDefault("")
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_backup_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (exportedAt.isNotBlank()) {
                    Text(
                        text = stringResource(
                            R.string.settings_backup_import_summary,
                            exportedAt,
                            file.appVersion.ifBlank { "?" },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(
                        R.string.settings_backup_import_counts,
                        file.bookmarks.size,
                        file.trustedSites.size,
                        file.siteWatches.count { it.enabled },
                        file.credentialCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    text = stringResource(R.string.settings_backup_mode),
                    style = MaterialTheme.typography.bodyMedium,
                )
                ModeRow(
                    selected = mode == ImportMode.MERGE,
                    label = stringResource(R.string.settings_backup_mode_merge),
                    onClick = { mode = ImportMode.MERGE },
                )
                ModeRow(
                    selected = mode == ImportMode.REPLACE,
                    label = stringResource(R.string.settings_backup_mode_replace),
                    onClick = { mode = ImportMode.REPLACE },
                )
                if (mode == ImportMode.REPLACE) {
                    Text(
                        text = stringResource(R.string.settings_backup_mode_replace_warning),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { replaceConfirmed = !replaceConfirmed },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = replaceConfirmed,
                            onCheckedChange = { replaceConfirmed = it },
                        )
                        Text(
                            text = stringResource(R.string.settings_backup_mode_replace_confirm),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }

                if (file.credentials != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showPassphrase = !showPassphrase },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = showPassphrase,
                            onCheckedChange = { showPassphrase = it },
                        )
                        Text(
                            text = stringResource(
                                R.string.settings_backup_credentials_locked,
                                file.credentialCount,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (showPassphrase) {
                        OutlinedTextField(
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.settings_backup_passphrase)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                        Text(
                            text = stringResource(R.string.settings_backup_credentials_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = mode != ImportMode.REPLACE || replaceConfirmed,
                onClick = {
                    val secret = passphrase.takeIf { it.isNotBlank() }?.toCharArray()
                    onConfirm(mode, secret)
                },
            ) {
                Text(stringResource(R.string.settings_backup_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ModeRow(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PassphraseDialog(
    title: String,
    confirmLabel: String,
    requireConfirmation: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    var secret by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = secret,
                    onValueChange = {
                        secret = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_backup_passphrase)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                if (requireConfirmation) {
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = {
                            confirmation = it
                            error = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.settings_backup_passphrase_confirm)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
                Text(
                    text = stringResource(R.string.settings_backup_passphrase_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let {
                    Text(
                        text = stringResource(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when {
                        secret.length < BackupCrypto.MIN_PASSPHRASE_LENGTH -> {
                            error = R.string.settings_backup_passphrase_too_short
                        }
                        requireConfirmation && secret != confirmation -> {
                            error = R.string.settings_backup_passphrase_mismatch
                        }
                        else -> {
                            onConfirm(secret.toCharArray())
                        }
                    }
                },
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
