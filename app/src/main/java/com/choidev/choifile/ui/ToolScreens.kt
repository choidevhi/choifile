package com.choidev.choifile.ui

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.choidev.choifile.R
import com.choidev.choifile.analysis.DirNode
import com.choidev.choifile.analysis.FileEntry
import com.choidev.choifile.analysis.TypeCategory
import com.choidev.choifile.browser.BrowserViewModel
import com.choidev.choifile.dupes.DupStage
import com.choidev.choifile.dupes.DuplicateFinder
import com.choidev.choifile.oplog.NoUndo
import com.choidev.choifile.oplog.OpRecord
import com.choidev.choifile.oplog.OpType
import com.choidev.choifile.oplog.UndoState
import java.io.File
import java.text.DateFormat
import java.util.Date

enum class ToolScreen { OPERATIONS, ANALYSIS, DUPLICATES, RULES, SERVER }

@Composable
fun opTypeLabel(t: OpType): String = stringResource(
    when (t) {
        OpType.COPY -> R.string.op_copy
        OpType.MOVE -> R.string.op_move
        OpType.RENAME -> R.string.op_rename
        OpType.TRASH -> R.string.op_trash
        OpType.DELETE -> R.string.op_delete
        OpType.EXTRACT -> R.string.op_extract
        OpType.ARCHIVE -> R.string.op_archive
    },
)

// ---------------------------------------------------------------- operation log

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OperationsScreen(vm: BrowserViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val records by vm.opRecords.collectAsStateWithLifecycle()
    val transfer by vm.transfer.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var onlyRules by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val shown = if (onlyRules) records.filter { it.isRule } else records
    val undoLabel = stringResource(R.string.undo)

    LaunchedEffect(Unit) {
        vm.uiEvents.collect { e ->
            if (e.message.isEmpty()) return@collect
            val r = snackbar.showSnackbar(e.message, actionLabel = undoLabel.takeIf { e.undoRecord != null })
            if (r == SnackbarResult.ActionPerformed) e.undoRecord?.let(vm::undoRecord)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ops_title)) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = { IconButton({ confirmClear = true }, enabled = records.isNotEmpty()) { Icon(Icons.Default.DeleteSweep, stringResource(R.string.ops_clear)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                transfer?.let { t ->
                    Column(Modifier.padding(16.dp, 8.dp)) {
                        Text(stringResource(R.string.ops_running), style = MaterialTheme.typography.titleSmall)
                        Text(t.currentName.ifEmpty { stringResource(R.string.transfer_working) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (t.bytesTotal > 0) LinearProgressIndicator(progress = { t.fraction }, Modifier.fillMaxWidth().padding(vertical = 4.dp))
                        else LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                        TextButton(vm::cancelTransfer) { Text(stringResource(R.string.cancel)) }
                    }
                    HorizontalDivider()
                }
                Text(stringResource(R.string.ops_honesty), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!onlyRules, { onlyRules = false }, { Text(stringResource(R.string.ops_filter_all)) })
                    FilterChip(onlyRules, { onlyRules = true }, { Text(stringResource(R.string.ops_filter_rules)) })
                }
                HorizontalDivider(Modifier.padding(top = 8.dp))
                if (shown.isEmpty()) Text(stringResource(R.string.ops_empty), Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(shown, key = { it.id }) { r -> OperationRow(r, df.format(Date(r.time)), onUndo = { vm.undoRecord(r.id) }) }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.ops_clear)) },
            text = { Text(stringResource(R.string.ops_clear_message)) },
            confirmButton = { TextButton({ vm.clearOperationLog(); confirmClear = false }) { Text(stringResource(R.string.ops_clear_action)) } },
            dismissButton = { TextButton({ confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun OperationRow(r: OpRecord, time: String, onUndo: () -> Unit) {
    val label = opTypeLabel(r.type)
    val title = if (r.count > 1) stringResource(R.string.op_line_many, label, r.firstName, r.count - 1) else stringResource(R.string.op_line_one, label, r.firstName)
    val origin = if (r.isRule) stringResource(R.string.op_origin_rule, r.ruleName) else null
    ListItem(
        headlineContent = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(listOfNotNull(time, origin, if (r.failed > 0) stringResource(R.string.op_failed, r.failed) else null).joinToString(" · "))
                val status = when (r.undoState) {
                    UndoState.AVAILABLE -> null
                    UndoState.DONE -> stringResource(R.string.op_undone)
                    UndoState.PARTIAL -> stringResource(R.string.op_undone_partial)
                    UndoState.UNAVAILABLE -> stringResource(R.string.op_cannot_undo, stringResource(noUndoText(r.reason)))
                }
                if (status != null) Text(status, color = if (r.undoState == UndoState.UNAVAILABLE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        },
        trailingContent = { if (r.undoState == UndoState.AVAILABLE) TextButton(onUndo) { Text(stringResource(R.string.op_undo)) } },
    )
}

private fun noUndoText(reason: String?): Int = when (reason) {
    NoUndo.PERMANENT -> R.string.op_why_permanent
    NoUndo.SECURE -> R.string.op_why_secure
    NoUndo.REMOTE -> R.string.op_why_remote
    else -> R.string.op_why_other
}

// ---------------------------------------------------------------- storage analysis

private enum class AnalysisTab { FOLDERS, TYPES, FILES }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisScreen(vm: BrowserViewModel, onOpenFolder: (String) -> Unit, onBack: () -> Unit) {
    val state by vm.analysis.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tab by remember { mutableStateOf(AnalysisTab.FOLDERS) }
    var stack by remember { mutableStateOf<List<DirNode>>(emptyList()) }

    LaunchedEffect(Unit) { if (state.result == null && !state.scanning) vm.analysis.start() }
    LaunchedEffect(state.result) { stack = state.result?.let { listOf(it.root) } ?: emptyList() }
    BackHandler { if (tab == AnalysisTab.FOLDERS && stack.size > 1) stack = stack.dropLast(1) else onBack() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.analysis_title)) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            actions = { IconButton({ vm.analysis.start() }, enabled = !state.scanning) { Icon(Icons.Default.Refresh, stringResource(R.string.analysis_rescan)) } },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.scanning) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.analysis_scanning, state.filesSeen))
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    TextButton({ vm.analysis.cancel() }) { Text(stringResource(R.string.cancel)) }
                }
            }
            state.error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            val result = state.result
            if (result != null) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(Formatter.formatFileSize(context, result.root.totalSize), style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.analysis_summary, result.totalFiles) + if (!result.complete) " · " + stringResource(R.string.analysis_incomplete) else "", style = MaterialTheme.typography.bodySmall)
                    if (result.excludedFolders > 0) Text(stringResource(R.string.analysis_excluded, result.excludedFolders), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(tab == AnalysisTab.FOLDERS, { tab = AnalysisTab.FOLDERS }, { Text(stringResource(R.string.analysis_tab_folders)) })
                    FilterChip(tab == AnalysisTab.TYPES, { tab = AnalysisTab.TYPES }, { Text(stringResource(R.string.analysis_tab_types)) })
                    FilterChip(tab == AnalysisTab.FILES, { tab = AnalysisTab.FILES }, { Text(stringResource(R.string.analysis_tab_files)) })
                }
                HorizontalDivider(Modifier.padding(top = 8.dp))
                when (tab) {
                    AnalysisTab.FOLDERS -> {
                        val node = stack.lastOrNull()
                        if (node != null) FolderTab(node, stack.size > 1, onUp = { stack = stack.dropLast(1) }, onEnter = { stack = stack + it }, onOpen = { onOpenFolder(node.path) })
                    }
                    AnalysisTab.TYPES -> TypesTab(result.byType.filter { it.value.count > 0 }.entries.sortedByDescending { it.value.bytes }.map { Triple(it.key, it.value.bytes, it.value.count) }, result.root.totalSize)
                    AnalysisTab.FILES -> LazyColumn(Modifier.fillMaxSize()) {
                        items(result.largestFiles, key = { it.path }) { f -> FileSizeRow(f, result.largestFiles.firstOrNull()?.size ?: 1L, rootRelative = f.path) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SizeBar(fraction: Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier.fillMaxWidth().height(6.dp))
}

@Composable
private fun FolderTab(node: DirNode, canGoUp: Boolean, onUp: () -> Unit, onEnter: (DirNode) -> Unit, onOpen: () -> Unit) {
    val context = LocalContext.current
    val dirs = remember(node) { node.childrenBySize() }
    val files = node.topFiles
    // One list sorted by size, folders and the biggest loose files together, bars relative to this folder's total.
    val rows = remember(node) { (dirs.map { it.totalSize to it } + files.map { it.size to it }).sortedByDescending { it.first } }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp, end = 8.dp)) {
                IconButton(onUp, enabled = canGoUp) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.up)) }
                Text(node.path, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.StartEllipsis, style = MaterialTheme.typography.bodySmall)
                IconButton(onOpen) { Icon(Icons.Default.FolderOpen, stringResource(R.string.analysis_open_in_browser)) }
            }
        }
        if (rows.isEmpty()) item { Text(stringResource(R.string.analysis_empty_folder), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(rows, key = { (_, any) -> if (any is DirNode) any.path else (any as FileEntry).path }) { (size, any) ->
            val total = node.totalSize.coerceAtLeast(1L)
            if (any is DirNode) {
                ListItem(
                    modifier = Modifier.clickable { onEnter(any) },
                    leadingContent = { Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                    headlineContent = { Text(any.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Column { SizeBar(size.toFloat() / total); Text(stringResource(R.string.analysis_folder_meta, any.fileCount), style = MaterialTheme.typography.bodySmall) } },
                    trailingContent = { Text(Formatter.formatShortFileSize(context, size) + "  " + percent(size, total)) },
                )
            } else {
                val f = any as FileEntry
                ListItem(
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    headlineContent = { Text(File(f.path).name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { SizeBar(size.toFloat() / total) },
                    trailingContent = { Text(Formatter.formatShortFileSize(context, size) + "  " + percent(size, total)) },
                )
            }
        }
    }
}

private fun percent(part: Long, total: Long) = "%.1f%%".format(part * 100.0 / total.coerceAtLeast(1))

@Composable
private fun TypesTab(rows: List<Triple<TypeCategory, Long, Int>>, total: Long) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = { it.first.name }) { (cat, bytes, count) ->
            ListItem(
                headlineContent = { Text(stringResource(typeLabel(cat))) },
                supportingContent = { Column { SizeBar(bytes.toFloat() / total.coerceAtLeast(1)); Text(stringResource(R.string.analysis_type_meta, count), style = MaterialTheme.typography.bodySmall) } },
                trailingContent = { Text(Formatter.formatShortFileSize(context, bytes) + "  " + percent(bytes, total)) },
            )
        }
    }
}

private fun typeLabel(c: TypeCategory) = when (c) {
    TypeCategory.IMAGE -> R.string.kind_image
    TypeCategory.VIDEO -> R.string.kind_video
    TypeCategory.AUDIO -> R.string.kind_audio
    TypeCategory.DOCUMENT -> R.string.kind_document
    TypeCategory.ARCHIVE -> R.string.kind_archive
    TypeCategory.APK -> R.string.type_apk
    TypeCategory.OTHER -> R.string.type_other
}

@Composable
private fun FileSizeRow(f: FileEntry, max: Long, rootRelative: String) {
    val context = LocalContext.current
    ListItem(
        leadingContent = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        headlineContent = { Text(File(f.path).name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Column { SizeBar(f.size.toFloat() / max.coerceAtLeast(1)); Text(File(rootRelative).parent.orEmpty(), maxLines = 1, overflow = TextOverflow.StartEllipsis, style = MaterialTheme.typography.bodySmall) } },
        trailingContent = { Text(Formatter.formatShortFileSize(context, f.size)) },
    )
}

// ---------------------------------------------------------------- duplicates

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(vm: BrowserViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val state by vm.duplicates.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var confirm by remember { mutableStateOf(false) }
    val undoLabel = stringResource(R.string.undo)
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }

    LaunchedEffect(Unit) {
        vm.uiEvents.collect { e ->
            if (e.message.isEmpty()) return@collect
            val r = snackbar.showSnackbar(e.message, actionLabel = undoLabel.takeIf { e.undoRecord != null })
            if (r == SnackbarResult.ActionPerformed) e.undoRecord?.let(vm::undoRecord)
        }
    }

    val groups = state.groups
    val pending = if (groups != null) groups.flatMap { DuplicateFinder.safeSelection(it, state.selected) } else emptyList()
    val pendingBytes = groups?.sumOf { g -> DuplicateFinder.safeSelection(g, state.selected).size * g.size } ?: 0L

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dup_title)) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (groups != null && groups.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.dup_pending, pending.size, Formatter.formatShortFileSize(context, pendingBytes)), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Button({ confirm = true }, enabled = pending.isNotEmpty()) { Text(stringResource(R.string.dup_trash_selected)) }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.scanning -> Column(Modifier.padding(16.dp)) {
                    Text(stringResource(when (state.stage) { DupStage.LISTING -> R.string.dup_stage_list; DupStage.PARTIAL -> R.string.dup_stage_partial; DupStage.FULL -> R.string.dup_stage_full }))
                    if (state.total > 0) LinearProgressIndicator(progress = { state.done.toFloat() / state.total }, Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    else LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    if (state.total > 0) Text("${state.done} / ${state.total}", style = MaterialTheme.typography.bodySmall)
                    TextButton({ vm.duplicates.cancel() }) { Text(stringResource(R.string.cancel)) }
                }
                groups == null -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.dup_intro))
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button({ vm.duplicates.start() }) { Text(stringResource(R.string.dup_scan)) }
                }
                groups.isEmpty() -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.dup_none))
                    OutlinedButton({ vm.duplicates.start() }) { Text(stringResource(R.string.dup_scan_again)) }
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        Text(stringResource(R.string.dup_review_hint, groups.size, Formatter.formatShortFileSize(context, groups.sumOf { it.wasted })), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    groups.forEach { g ->
                        item(key = "h" + g.hash) {
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(File(g.files[0].path).name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(stringResource(R.string.dup_group_meta, g.files.size, Formatter.formatShortFileSize(context, g.size), Formatter.formatShortFileSize(context, g.wasted)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton({ vm.duplicates.resetGroup(g) }) { Text(stringResource(R.string.dup_reset)) }
                            }
                        }
                        items(g.files, key = { g.hash + it.path }) { f ->
                            val marked = f.path in state.selected
                            ListItem(
                                modifier = Modifier.clickable { vm.duplicates.toggle(f.path) },
                                leadingContent = { Checkbox(marked, { vm.duplicates.toggle(f.path) }) },
                                headlineContent = { Text(f.path.removePrefix(vm.rootPath), maxLines = 2, overflow = TextOverflow.StartEllipsis, style = MaterialTheme.typography.bodyMedium) },
                                supportingContent = { Text(df.format(Date(f.modified)) + if (!marked) " · " + stringResource(R.string.dup_keep) else "") },
                            )
                        }
                        item(key = "d" + g.hash) { HorizontalDivider() }
                    }
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.dup_confirm_title)) },
            text = { Text(stringResource(R.string.dup_confirm_message, pending.size, Formatter.formatShortFileSize(context, pendingBytes))) },
            confirmButton = { TextButton({ vm.deleteDuplicates(); confirm = false }) { Text(stringResource(R.string.dup_trash_selected)) } },
            dismissButton = { TextButton({ confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
