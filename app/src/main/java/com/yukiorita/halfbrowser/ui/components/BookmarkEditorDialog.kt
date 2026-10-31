package com.yukiorita.halfbrowser.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.yukiorita.halfbrowser.R
import com.yukiorita.halfbrowser.core.Bookmark
import com.yukiorita.halfbrowser.core.UrlNormalizer

/**
 * Shared editor used by the home grid and the browser menu.
 *
 * Validation happens on save so the user can finish typing; an invalid URL keeps
 * the dialog open with an inline error instead of silently discarding input.
 */
@Composable
fun BookmarkEditorDialog(
    initial: Bookmark?,
    initialUrl: String,
    onDismiss: () -> Unit,
    onSave: (title: String, url: String, glyph: String) -> Unit,
) {
    var title by rememberSaveable(initial?.id) { mutableStateOf(initial?.title.orEmpty()) }
    var url by rememberSaveable(initial?.id) { mutableStateOf(initial?.url ?: initialUrl) }
    var glyph by rememberSaveable(initial?.id) { mutableStateOf(initial?.glyph.orEmpty()) }
    var error by remember { mutableStateOf(false) }

    val save = {
        val normalized = UrlNormalizer.normalizeForBookmark(url)
        if (normalized == null) {
            error = true
        } else {
            error = false
            onSave(title.trim(), normalized, glyph.trim().take(2))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = {
            Text(
                stringResource(
                    if (initial == null) R.string.bookmark_new_title else R.string.bookmark_edit_title,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.bookmark_field_title)) },
                    supportingText = { Text(stringResource(R.string.bookmark_title_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        error = false
                    },
                    label = { Text(stringResource(R.string.bookmark_field_url)) },
                    isError = error,
                    supportingText = if (error) {
                        { Text(stringResource(R.string.bookmark_invalid_url)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = glyph,
                    onValueChange = { input -> glyph = input.take(2) },
                    label = { Text(stringResource(R.string.bookmark_field_icon)) },
                    placeholder = { Text(stringResource(R.string.bookmark_field_icon_hint)) },
                    supportingText = { Text(stringResource(R.string.bookmark_field_icon_helper)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = save) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}
