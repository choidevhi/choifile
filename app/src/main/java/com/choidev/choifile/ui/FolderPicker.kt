package com.choidev.choifile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.choidev.choifile.R
import com.choidev.choifile.security.APP_PRIVATE_FOLDERS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Picks a folder below [rootPath] by walking the tree. The app's own vault and trash folders are not shown. */
@Composable
fun FolderPickerDialog(rootPath: String, start: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var current by remember { mutableStateOf(start.takeIf { File(it).isDirectory } ?: rootPath) }
    val children by produceState(emptyList<File>(), current) {
        value = withContext(Dispatchers.IO) {
            File(current).listFiles().orEmpty().filter { it.isDirectory && it.name !in APP_PRIVATE_FOLDERS }.sortedBy { it.name.lowercase() }
        }
    }
    ChoiDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folder_pick_title)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ File(current).parentFile?.let { current = it.absolutePath } }, enabled = current != rootPath) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBackIos, stringResource(R.string.up))
                    }
                    Text(current.removePrefix(rootPath).ifEmpty { "/" }, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.StartEllipsis, style = MaterialTheme.typography.bodyMedium)
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    if (children.isEmpty()) item { Text(stringResource(R.string.folder_pick_empty), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(children, key = { it.path }) { f ->
                        ListItem(
                            modifier = Modifier.clickable { current = f.absolutePath },
                            leadingContent = { Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                            headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        },
        confirmButton = { PrimaryButton({ onPick(current) }) { Text(stringResource(R.string.folder_pick_here)) } },
        dismissButton = { OutlineButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
