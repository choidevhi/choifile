package com.choidev.choifile.ui

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.choidev.choifile.R
import com.choidev.choifile.browser.BrowserViewModel
import com.choidev.choifile.search.KindFilter
import com.choidev.choifile.search.SearchQuery
import com.choidev.choifile.search.parseExtensions
import com.choidev.choifile.storage.FileItem
import java.text.DateFormat
import java.util.Date

private data class SizeOption(val label: Int, val min: Long?, val max: Long?)

private val sizeOptions = listOf(
    SizeOption(R.string.size_small, null, 1L shl 20),
    SizeOption(R.string.size_1mb, 1L shl 20, null),
    SizeOption(R.string.size_10mb, 10L shl 20, null),
    SizeOption(R.string.size_100mb, 100L shl 20, null),
    SizeOption(R.string.size_1gb, 1L shl 30, null),
)

private val dateOptions = listOf(R.string.date_today to 1, R.string.date_7d to 7, R.string.date_30d to 30, R.string.date_1y to 365)

private val kindLabels = listOf(
    KindFilter.FOLDER to R.string.kind_folder, KindFilter.IMAGE to R.string.kind_image, KindFilter.VIDEO to R.string.kind_video,
    KindFilter.AUDIO to R.string.kind_audio, KindFilter.DOCUMENT to R.string.kind_document, KindFilter.ARCHIVE to R.string.kind_archive,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(vm: BrowserViewModel, rootPath: String, onOpenFolder: (String) -> Unit, onOpenFile: (FileItem) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val state by vm.search.state.collectAsStateWithLifecycle()
    val saved by vm.search.saved.collectAsStateWithLifecycle()
    val q = state.query
    val context = LocalContext.current
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    var extDialog by remember { mutableStateOf(false) }
    var saveDialog by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(Unit) { vm.search.onOpen() }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBackIos, stringResource(R.string.back)) }
                OutlinedTextField(
                    q.text, { vm.search.setQuery(q.copy(text = it)) },
                    Modifier.weight(1f).padding(end = 8.dp),
                    singleLine = true,
                    placeholder = { Text(stringResource(if (q.content) R.string.search_hint_content else R.string.search_hint)) },
                    trailingIcon = {
                        if (q.text.isNotEmpty()) IconButton({ vm.search.setQuery(q.copy(text = "")) }) { Icon(Icons.Outlined.Close, stringResource(R.string.close)) }
                    },
                )
            }

            // filter chips
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ChipRow {
                    kindLabels.forEach { (k, label) ->
                        FilterChip(k in q.kinds, { vm.search.setQuery(q.copy(kinds = if (k in q.kinds) q.kinds - k else q.kinds + k)) }, { Text(stringResource(label)) })
                    }
                }
                ChipRow {
                    sizeOptions.forEach { o ->
                        val on = q.minSize == o.min && q.maxSize == o.max
                        FilterChip(on, { vm.search.setQuery(if (on) q.copy(minSize = null, maxSize = null) else q.copy(minSize = o.min, maxSize = o.max)) }, { Text(stringResource(o.label)) })
                    }
                }
                ChipRow {
                    dateOptions.forEach { (label, days) ->
                        val on = q.withinDays == days
                        FilterChip(on, { vm.search.setQuery(q.copy(withinDays = if (on) null else days)) }, { Text(stringResource(label)) })
                    }
                    FilterChip(
                        q.extensions.isNotEmpty(), { extDialog = true },
                        { Text(if (q.extensions.isEmpty()) stringResource(R.string.filter_extension) else q.extensions.joinToString(",") { ".$it" }) },
                    )
                    FilterChip(q.content, { vm.search.setQuery(q.copy(content = !q.content)) }, { Text(stringResource(R.string.filter_content)) })
                }
            }

            // saved searches
            if (saved.isNotEmpty() || !q.isEmpty) {
                ChipRow(Modifier.padding(horizontal = 12.dp)) {
                    if (!q.isEmpty) androidx.compose.material3.AssistChip({ saveDialog = true }, { Text(stringResource(R.string.save_search)) })
                    saved.forEach { s ->
                        InputChip(
                            false, { vm.search.setQuery(s.query) }, { Text(s.name) },
                            trailingIcon = { Icon(Icons.Outlined.Close, stringResource(R.string.delete), Modifier.size(16.dp).clickable { vm.search.deleteSaved(s.name) }) },
                        )
                    }
                }
            }

            // index status
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.indexedAt == 0L && !state.indexing) stringResource(R.string.index_none)
                    else stringResource(R.string.index_status, state.indexedCount) + if (state.indexedAt > 0) " · " + dateFormat.format(Date(state.indexedAt)) else "",
                    Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.indexing || state.searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else IconButton(vm.search::reindex) { Icon(Icons.Outlined.Refresh, stringResource(R.string.index_refresh)) }
            }
            HorizontalDivider()

            when {
                state.error != null -> Text(state.error!!, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                q.isEmpty -> Hint(stringResource(R.string.search_prompt))
                state.results.isEmpty() && !state.searching -> Hint(stringResource(R.string.search_none))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.results, key = { it.item.path }) { hit ->
                        val item = hit.item
                        ListItem(
                            modifier = Modifier.clickable { if (item.isDirectory) onOpenFolder(item.path) else onOpenFile(item) },
                            leadingContent = { Icon(if (item.isDirectory) Icons.Filled.Folder else Icons.AutoMirrored.Outlined.InsertDriveFile, null) },
                            headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Column {
                                    hit.snippet?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
                                    Text(
                                        item.path.removePrefix(rootPath).substringBeforeLast('/').ifEmpty { "/" } + " · " +
                                            (if (item.isDirectory) "" else Formatter.formatShortFileSize(context, item.size) + " · ") + dateFormat.format(Date(item.modified)),
                                        maxLines = 1, overflow = TextOverflow.StartEllipsis, style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (extDialog) {
        NameDialog(stringResource(R.string.filter_extension), q.extensions.joinToString(", "), { raw ->
            vm.search.setQuery(q.copy(extensions = parseExtensions(raw)))
            extDialog = false
        }, { extDialog = false })
    }
    if (saveDialog) {
        NameDialog(stringResource(R.string.save_search), q.text.ifBlank { stringResource(R.string.search_default_name) }, { name ->
            vm.search.saveCurrent(name)
            saveDialog = false
        }, { saveDialog = false })
    }
}

@Composable
private fun ChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}
