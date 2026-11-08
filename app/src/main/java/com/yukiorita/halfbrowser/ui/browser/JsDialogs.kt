package com.yukiorita.halfbrowser.ui.browser

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.yukiorita.halfbrowser.R

/**
 * A JavaScript `alert` / `confirm` / `prompt` rendered by the app.
 *
 * Why not the WebView's built-in dialog: the page controls its text, and the
 * built-in dialog shows no origin. Here the origin is always visible, so a page
 * cannot pose as the browser or another site while asking for confirmation.
 */
@Composable
fun JsDialogHost(
    request: JsDialogRequest,
    onResult: (JsDialogResult) -> Unit,
) {
    var promptText by rememberSaveable(request) { mutableStateOf(request.defaultText) }

    val title = when (request.kind) {
        JsDialogKind.ALERT -> R.string.js_dialog_alert
        JsDialogKind.CONFIRM -> R.string.js_dialog_confirm
        JsDialogKind.PROMPT -> R.string.js_dialog_prompt
    }

    AlertDialog(
        onDismissRequest = {
            onResult(
                if (request.kind == JsDialogKind.ALERT) JsDialogResult.Dismiss else JsDialogResult.Cancel,
            )
        },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = request.message.ifBlank {
                        stringResource(R.string.js_dialog_blocked_short)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (request.kind == JsDialogKind.PROMPT) {
                    OutlinedTextField(
                        value = promptText,
                        onValueChange = { promptText = it },
                        singleLine = false,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (request.origin.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.js_dialog_from, request.origin),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onResult(
                        when (request.kind) {
                            JsDialogKind.ALERT -> JsDialogResult.Dismiss
                            JsDialogKind.CONFIRM -> JsDialogResult.Confirm
                            JsDialogKind.PROMPT -> JsDialogResult.Text(promptText)
                        },
                    )
                },
            ) {
                Text(
                    stringResource(
                        if (request.kind == JsDialogKind.ALERT) R.string.action_close else R.string.action_ok,
                    ),
                )
            }
        },
        dismissButton = if (request.kind == JsDialogKind.ALERT) {
            null
        } else {
            {
                TextButton(onClick = { onResult(JsDialogResult.Cancel) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
    )
}
