package com.choidev.choifile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.Role
import com.choidev.choifile.browser.DeleteMode
import com.choidev.choifile.browser.OriginalAction
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.choidev.choifile.R
import com.choidev.choifile.archive.ArchiveFormat
import com.choidev.choifile.storage.TrashEntry

const val MAX_DUMMY_MB = 2048

@Composable
fun NameDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.name_label)) }, singleLine = true) },
        confirmButton = { TextButton({ onConfirm(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun DummyDialog(onConfirm: (String, Int) -> Unit, onDismiss: () -> Unit) {
    val defaultName = stringResource(R.string.dummy_name_default)
    var name by remember { mutableStateOf(defaultName) }
    var size by remember { mutableStateOf("10") }
    val mb = size.toIntOrNull()?.takeIf { it in 1..MAX_DUMMY_MB }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dummy_title)) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name_label)) }, singleLine = true)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    size, { size = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.dummy_size_label)) },
                    singleLine = true,
                    isError = mb == null,
                    supportingText = { if (mb == null) Text(stringResource(R.string.msg_bad_size, MAX_DUMMY_MB)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = { TextButton({ onConfirm(name, mb!!) }, enabled = mb != null && name.isNotBlank()) { Text(stringResource(R.string.create)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}


@Composable
fun TrashDialog(entries: List<TrashEntry>, onRestore: (TrashEntry) -> Unit, onEmpty: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.trash)) },
        text = {
            if (entries.isEmpty()) {
                Text(stringResource(R.string.trash_empty_list))
            } else {
                LazyColumn {
                    items(entries, key = { it.id }) { e ->
                        ListItem(
                            headlineContent = { Text(e.name, maxLines = 1) },
                            supportingContent = { Text(e.origin, maxLines = 1) },
                            trailingContent = { TextButton({ onRestore(e) }) { Text(stringResource(R.string.trash_restore)) } },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = { if (entries.isNotEmpty()) TextButton(onEmpty) { Text(stringResource(R.string.trash_empty_action)) } },
    )
}

@Composable
private fun RadioRow(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, null)
        Text(label, Modifier.padding(start = 8.dp, top = 6.dp, bottom = 6.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun DeleteDialog(count: Int, onConfirm: (DeleteMode) -> Unit, onDismiss: () -> Unit) {
    var mode by remember { mutableStateOf(DeleteMode.TRASH) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.delete_message, count))
                Spacer(Modifier.height(8.dp))
                RadioRow(mode == DeleteMode.TRASH, stringResource(R.string.trash)) { mode = DeleteMode.TRASH }
                RadioRow(mode == DeleteMode.PERMANENT, stringResource(R.string.delete_permanent)) { mode = DeleteMode.PERMANENT }
                RadioRow(mode == DeleteMode.SECURE, stringResource(R.string.delete_secure)) { mode = DeleteMode.SECURE }
                if (mode == DeleteMode.SECURE) {
                    Text(
                        stringResource(R.string.delete_secure_warning),
                        Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = { TextButton({ onConfirm(mode) }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Password prompt. With [options] non-null the user also chooses what happens to the plaintext original.
 * [confirmTwice] is for creating a password (encrypt); decrypt just asks once.
 */
@Composable
fun PasswordDialog(
    title: String,
    confirmTwice: Boolean,
    options: List<OriginalAction>? = null,
    defaultOption: OriginalAction = OriginalAction.KEEP,
    onConfirm: (CharArray, OriginalAction) -> Unit,
    onDismiss: () -> Unit,
) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var option by remember { mutableStateOf(defaultOption) }
    val tooShort = confirmTwice && pw.isNotEmpty() && pw.length < 8
    val mismatch = confirmTwice && pw2.isNotEmpty() && pw != pw2
    val valid = pw.isNotEmpty() && (!confirmTwice || (pw.length >= 8 && pw == pw2))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    pw, { pw = it }, label = { Text(stringResource(R.string.pw_label)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = tooShort,
                    supportingText = { if (tooShort) Text(stringResource(R.string.pw_too_short)) },
                )
                if (confirmTwice) {
                    OutlinedTextField(
                        pw2, { pw2 = it }, label = { Text(stringResource(R.string.pw_confirm_label)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        isError = mismatch,
                        supportingText = { if (mismatch) Text(stringResource(R.string.pw_mismatch)) },
                    )
                    Text(stringResource(R.string.pw_warning), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                }
                if (options != null) {
                    Text(stringResource(R.string.original_label), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge)
                    options.forEach { o ->
                        val label = when (o) {
                            OriginalAction.KEEP -> R.string.original_keep
                            OriginalAction.TRASH -> R.string.original_trash
                            OriginalAction.PERMANENT -> R.string.original_permanent
                            OriginalAction.SECURE -> R.string.original_secure
                        }
                        RadioRow(option == o, stringResource(label)) { option = o }
                    }
                }
            }
        },
        confirmButton = { TextButton({ onConfirm(pw.toCharArray(), option) }, enabled = valid) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun ArchiveCreateDialog(suggested: String, onConfirm: (String, ArchiveFormat) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(com.choidev.choifile.archive.archiveBaseName(suggested)) }
    var format by remember { mutableStateOf(ArchiveFormat.ZIP) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.compress)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.name_label)) })
                listOf(ArchiveFormat.ZIP, ArchiveFormat.SEVEN_Z, ArchiveFormat.TAR_GZ).forEach {
                    RadioRow(format == it, ".${it.label}") { format = it }
                }
                Text(stringResource(R.string.archive_rar_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton({ onConfirm(name, format) }, enabled = name.isNotBlank() && '/' !in name) { Text(stringResource(R.string.create)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
