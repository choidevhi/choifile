package com.choidev.choifile.ui

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.choidev.choifile.R
import com.choidev.choifile.browser.BrowserState
import com.choidev.choifile.browser.BrowserViewModel
import com.choidev.choifile.browser.OriginalAction
import com.choidev.choifile.browser.LocationKind
import com.choidev.choifile.browser.DeleteMode
import androidx.compose.material.icons.filled.Search
import com.choidev.choifile.browser.kindOfPath
import com.choidev.choifile.archive.ArchiveFormat
import com.choidev.choifile.remote.TransferProgress
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import com.choidev.choifile.crypto.CfEnc
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
import com.choidev.choifile.storage.FileItem
import com.choidev.choifile.storage.SortKey
import com.choidev.choifile.util.FileKind
import com.choidev.choifile.util.kindOf
import com.choidev.choifile.util.shareFiles
import java.io.File
import java.text.DateFormat
import java.util.Date

private enum class CryptoAction { ENCRYPT, DECRYPT, VIEW, SHARE, HIDE_ENCRYPT }

private sealed interface DialogState {
    data object NewFolder : DialogState
    data object NewFile : DialogState
    data object NewDummy : DialogState
    data object Delete : DialogState
    data object Trash : DialogState
    data class Crypto(val action: CryptoAction, val paths: List<String>) : DialogState
    data class Rename(val path: String, val name: String) : DialogState
    data class Compress(val paths: List<String>) : DialogState
    data object RemoteDelete : DialogState
    data class Properties(val path: String) : DialogState
}

@Composable
fun BrowserScreen(
    vm: BrowserViewModel,
    onOpenFile: (FileItem) -> Unit,
    onOpenTemp: (String) -> Unit,
    onOpenVault: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenConnections: () -> Unit,
    onOpenTool: (ToolScreen) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val transfer by vm.transfer.collectAsStateWithLifecycle()
    val listError by vm.listError.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val trash by vm.trash.collectAsStateWithLifecycle()
    val lockedRoot by vm.lockedRoot.collectAsStateWithLifecycle()
    val protectedPaths by vm.protectedPaths.collectAsStateWithLifecycle()
    val requestAuth = rememberRequestAuth()
    val context = LocalContext.current
    val density = LocalDensity.current
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<DialogState?>(null) }

    val layer = rememberGraphicsLayer()
    val backdrop = remember(layer) { GlassBackdrop(layer) }
    var topPx by remember { mutableIntStateOf(0) }
    var bottomPx by remember { mutableIntStateOf(0) }

    val undoLabel = stringResource(R.string.undo)
    LaunchedEffect(Unit) {
        vm.uiEvents.collect { e ->
            e.shareFile?.let { shareFiles(context, listOf(File(it))) }
            e.viewTemp?.let(onOpenTemp)
            if (e.message.isEmpty()) return@collect
            val result = snackbar.showSnackbar(e.message, actionLabel = undoLabel.takeIf { e.undoRecord != null })
            if (result == SnackbarResult.ActionPerformed) e.undoRecord?.let(vm::undoRecord)
        }
    }

    val atRoot = state.tab.path == vm.rootPath
    val kind = kindOfPath(state.tab.path)
    BackHandler(enabled = state.selection.isNotEmpty() || !atRoot) {
        if (state.selection.isNotEmpty()) vm.clearSelection() else vm.up()
    }

    val bottomBarVisible = state.selection.isNotEmpty() || state.clipboard != null
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val padding = PaddingValues(
        top = with(density) { topPx.toDp() } + 4.dp,
        bottom = if (bottomBarVisible) with(density) { bottomPx.toDp() } + 4.dp else navInset + 8.dp,
    )

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                },
        ) {
            val locked = lockedRoot
            if (locked != null) {
                androidx.compose.runtime.key(state.tab.path) {
                    LockedView(R.string.auth_subtitle_folder, Modifier.padding(padding)) {
                        requestAuth(R.string.auth_subtitle_folder, false) { vm.unlock(locked) }
                    }
                }
            } else {
                FileList(entries, state, padding, protectedPaths, vm, onOpenFile, listError)
            }
        }

        GlassSurface(backdrop, Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { topPx = it.height }) {
            Column(Modifier.statusBarsPadding()) {
                if (state.selection.isEmpty()) NavRow(state, atRoot, vm, onOpenVault, onOpenSettings, onOpenSearch, onOpenConnections, onOpenTool) { dialog = it } else SelectionRow(state, vm)
                TabStrip(state, vm)
            }
        }

        if (bottomBarVisible) {
            GlassSurface(backdrop, Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { bottomPx = it.height }) {
                Row(Modifier.navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp)) {
                    val clip = state.clipboard
                    if (state.selection.isNotEmpty()) {
                        SelectionActions(state, entries, vm, Modifier.weight(1f), onDialog = { dialog = it }, protectedPaths = protectedPaths, onShare = { files ->
                            if (files.isEmpty()) {
                                snackbar.currentSnackbarData?.dismiss()
                                vm.notifyNoShareDirs()
                            } else shareFiles(context, files)
                        })
                    } else if (clip != null) {
                        Text(
                            stringResource(if (clip.move) R.string.clipboard_move else R.string.clipboard_copy, clip.paths.size),
                            Modifier.weight(1f).padding(start = 12.dp).align(Alignment.CenterVertically),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(vm::clearClipboard) { Text(stringResource(R.string.cancel)) }
                        TextButton(vm::paste) {
                            Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.paste_count, clip.paths.size))
                        }
                    }
                }
            }
        }

        transfer?.let { t ->
            TransferBar(t, vm::cancelTransfer, Modifier.align(Alignment.BottomCenter).padding(bottom = (if (bottomBarVisible) with(density) { bottomPx.toDp() } else navInset) + 4.dp))
        }

        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).padding(bottom = if (bottomBarVisible) with(density) { bottomPx.toDp() } else navInset),
        )
    }

    when (val d = dialog) {
        null -> Unit
        DialogState.NewFolder -> NameDialog(stringResource(R.string.new_folder), stringResource(R.string.folder_name_default), { vm.createFolder(it); dialog = null }, { dialog = null })
        DialogState.NewFile -> NameDialog(stringResource(R.string.new_text_file), stringResource(R.string.file_name_default), { vm.createTextFile(it); dialog = null }, { dialog = null })
        DialogState.NewDummy -> DummyDialog({ name, mb -> vm.createDummyFile(name, mb); dialog = null }, { dialog = null })
        DialogState.Delete -> DeleteDialog(state.selection.size, { vm.delete(it); dialog = null }, { dialog = null })
        DialogState.Trash -> {
            LaunchedEffect(Unit) { vm.loadTrash() }
            TrashDialog(trash, vm::restoreFromTrash, vm::emptyTrash) { dialog = null }
        }
        is DialogState.Crypto -> {
            val encrypting = d.action != CryptoAction.DECRYPT && d.action != CryptoAction.VIEW
            val title = stringResource(when (d.action) {
                CryptoAction.SHARE -> R.string.pw_title_share
                CryptoAction.DECRYPT, CryptoAction.VIEW -> R.string.pw_title_decrypt
                else -> R.string.pw_title_encrypt
            })
            val options = when (d.action) {
                CryptoAction.ENCRYPT -> listOf(OriginalAction.KEEP, OriginalAction.TRASH, OriginalAction.PERMANENT, OriginalAction.SECURE)
                CryptoAction.HIDE_ENCRYPT -> listOf(OriginalAction.PERMANENT, OriginalAction.SECURE, OriginalAction.TRASH)
                else -> null
            }
            PasswordDialog(
                title, encrypting, options,
                if (d.action == CryptoAction.HIDE_ENCRYPT) OriginalAction.PERMANENT else OriginalAction.KEEP,
                onConfirm = { pw, original ->
                    when (d.action) {
                        CryptoAction.ENCRYPT -> vm.encrypt(d.paths, pw, original)
                        CryptoAction.DECRYPT -> vm.decrypt(d.paths, pw)
                        CryptoAction.VIEW -> vm.decryptForView(d.paths[0], pw)
                        CryptoAction.SHARE -> vm.encryptAndShare(d.paths[0], pw)
                        CryptoAction.HIDE_ENCRYPT -> vm.hideEncrypted(d.paths, pw, original)
                    }
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        }
        is DialogState.Compress -> ArchiveCreateDialog(
            suggested = if (d.paths.size == 1) File(d.paths[0]).name else File(state.tab.path).name.ifEmpty { "archive" },
            onConfirm = { name, format -> vm.createArchive(d.paths, format, name); dialog = null },
            onDismiss = { dialog = null },
        )
        DialogState.RemoteDelete -> androidx.compose.material3.AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.remote_delete_message, state.selection.size)) },
            confirmButton = { TextButton({ vm.delete(DeleteMode.PERMANENT); dialog = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { TextButton({ dialog = null }) { Text(stringResource(R.string.cancel)) } },
        )
        is DialogState.Properties -> PropertiesDialog(d.path) { dialog = null }
        is DialogState.Rename -> NameDialog(stringResource(R.string.rename), d.name, { vm.rename(d.path, it); dialog = null }, { dialog = null })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileList(entries: List<FileItem>, state: BrowserState, padding: PaddingValues, protectedPaths: Set<String>, vm: BrowserViewModel, onOpenFile: (FileItem) -> Unit, listError: String?) {
    val context = LocalContext.current
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val bg = MaterialTheme.colorScheme.background

    fun click(item: FileItem) = when {
        state.selection.isNotEmpty() -> vm.toggleSelect(item.path)
        item.isDirectory -> vm.openDir(item.path)
        else -> onOpenFile(item)
    }

    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Text(listError ?: stringResource(R.string.empty_folder), Modifier.padding(24.dp), color = if (listError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    if (state.tab.grid) {
        LazyVerticalGrid(GridCells.Adaptive(104.dp), Modifier.fillMaxSize(), contentPadding = padding) {
            items(entries, key = { it.path }) { item ->
                val selected = item.path in state.selection
                Column(
                    Modifier
                        .padding(4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else bg)
                        .combinedClickable(onClick = { click(item) }, onLongClick = { vm.toggleSelect(item.path) })
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    FileThumb(item, Modifier.size(64.dp))
                    Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            items(entries, key = { it.path }) { item ->
                val selected = item.path in state.selection
                ListItem(
                    modifier = Modifier.combinedClickable(onClick = { click(item) }, onLongClick = { vm.toggleSelect(item.path) }),
                    colors = ListItemDefaults.colors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else bg),
                    leadingContent = { FileThumb(item, Modifier.size(40.dp)) },
                    headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        val date = dateFormat.format(Date(item.modified))
                        Text(if (item.isDirectory) date else Formatter.formatShortFileSize(context, item.size) + " · " + date)
                    },
                    trailingContent = {
                        if (selected) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                        else if (item.path in protectedPaths) Icon(Icons.Default.Lock, stringResource(R.string.protect_folder), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                )
            }
        }
    }
}

@Composable
private fun FileThumb(item: FileItem, modifier: Modifier) {
    val kind = if (item.isDirectory) null else kindOf(File(item.path))
    if (kind == FileKind.IMAGE && kindOfPath(item.path) == LocationKind.LOCAL) {
        AsyncImage(File(item.path), null, modifier.clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
    } else {
        val icon: ImageVector = when (kind) {
            null -> Icons.Default.Folder
            FileKind.VIDEO -> Icons.Default.Movie
            FileKind.TEXT, FileKind.MARKDOWN -> Icons.Default.Description
            FileKind.PDF -> Icons.Default.PictureAsPdf
            else -> if (ArchiveFormat.detect(item.name) != null) Icons.Default.FolderZip else Icons.AutoMirrored.Filled.InsertDriveFile
        }
        Icon(icon, null, modifier, tint = if (kind == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NavRow(state: BrowserState, atRoot: Boolean, vm: BrowserViewModel, onOpenVault: () -> Unit, onOpenSettings: () -> Unit, onOpenSearch: () -> Unit, onOpenConnections: () -> Unit, onOpenTool: (ToolScreen) -> Unit, onDialog: (DialogState) -> Unit) {
    val kind = kindOfPath(state.tab.path)
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(vm::up, enabled = !atRoot) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.up)) }
        Text(
            vm.displayPath(state.tab.path),
            Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.StartEllipsis,
            style = MaterialTheme.typography.titleMedium,
        )
        IconButton(onOpenSearch) { Icon(Icons.Default.Search, stringResource(R.string.search)) }
        IconButton(vm::toggleGrid) {
            Icon(if (state.tab.grid) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView, stringResource(if (state.tab.grid) R.string.view_list else R.string.view_grid))
        }
        Box {
            IconButton({ sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.sort)) }
            DropdownMenu(sortMenu, { sortMenu = false }) {
                listOf(SortKey.NAME to R.string.sort_name, SortKey.SIZE to R.string.sort_size, SortKey.DATE to R.string.sort_date).forEach { (key, label) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        leadingIcon = { Checkbox(state.sort == key, null) },
                        onClick = { vm.setSort(key); sortMenu = false },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sort_ascending)) },
                    leadingIcon = { Checkbox(state.ascending, null) },
                    onClick = { vm.toggleAscending(); sortMenu = false },
                )
            }
        }
        Box {
            IconButton({ moreMenu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
            DropdownMenu(moreMenu, { moreMenu = false }) {
                DropdownMenuItem({ Text(stringResource(R.string.new_folder)) }, { moreMenu = false; onDialog(DialogState.NewFolder) })
                DropdownMenuItem({ Text(stringResource(R.string.new_text_file)) }, { moreMenu = false; onDialog(DialogState.NewFile) })
                if (kind == LocationKind.LOCAL) DropdownMenuItem({ Text(stringResource(R.string.new_dummy_file)) }, { moreMenu = false; onDialog(DialogState.NewDummy) })
                if (kind == LocationKind.ARCHIVE) DropdownMenuItem({ Text(stringResource(R.string.extract_all)) }, { moreMenu = false; vm.extractFromCurrentArchive(null) })
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.show_hidden)) },
                    leadingIcon = { Checkbox(state.showHidden, null) },
                    onClick = { vm.toggleHidden(); moreMenu = false },
                )
                DropdownMenuItem({ Text(stringResource(R.string.trash)) }, { moreMenu = false; onDialog(DialogState.Trash) })
                DropdownMenuItem({ Text(stringResource(R.string.hidden_items)) }, { moreMenu = false; onOpenVault() })
                DropdownMenuItem({ Text(stringResource(R.string.connections)) }, { moreMenu = false; onOpenConnections() })
                HorizontalDivider()
                DropdownMenuItem({ Text(stringResource(R.string.ops_title)) }, { moreMenu = false; onOpenTool(ToolScreen.OPERATIONS) })
                DropdownMenuItem({ Text(stringResource(R.string.analysis_title)) }, { moreMenu = false; onOpenTool(ToolScreen.ANALYSIS) })
                DropdownMenuItem({ Text(stringResource(R.string.dup_title)) }, { moreMenu = false; onOpenTool(ToolScreen.DUPLICATES) })
                DropdownMenuItem({ Text(stringResource(R.string.rules_title)) }, { moreMenu = false; onOpenTool(ToolScreen.RULES) })
                DropdownMenuItem({ Text(stringResource(R.string.srv_title)) }, { moreMenu = false; onOpenTool(ToolScreen.SERVER) })
                HorizontalDivider()
                DropdownMenuItem({ Text(stringResource(R.string.settings)) }, { moreMenu = false; onOpenSettings() })
            }
        }
    }
}

@Composable
private fun SelectionRow(state: BrowserState, vm: BrowserViewModel) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(vm::clearSelection) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
        Text(stringResource(R.string.selected_count, state.selection.size), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        IconButton(vm::selectAll) { Icon(Icons.Default.SelectAll, stringResource(R.string.select_all)) }
    }
}

@Composable
private fun TabStrip(state: BrowserState, vm: BrowserViewModel) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.tabs.forEachIndexed { index, tab ->
            FilterChip(
                selected = index == state.current,
                onClick = { vm.selectTab(index) },
                label = { Text(vm.tabLabel(tab.path), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 120.dp)) },
                trailingIcon = if (state.tabs.size > 1) {
                    { Icon(Icons.Default.Close, stringResource(R.string.tab_close), Modifier.size(16.dp).clickable { vm.closeTab(index) }) }
                } else null,
            )
        }
        IconButton(vm::addTab) { Icon(Icons.Default.Add, stringResource(R.string.tab_new)) }
    }
}

@Composable
private fun SelectionActions(
    state: BrowserState,
    entries: List<FileItem>,
    vm: BrowserViewModel,
    modifier: Modifier,
    onDialog: (DialogState) -> Unit,
    onShare: (List<File>) -> Unit,
    protectedPaths: Set<String>,
) {
    val selected = entries.filter { it.path in state.selection }
    val requestAuth = rememberRequestAuth()
    var securityMenu by remember { mutableStateOf(false) }
    val plainFiles = selected.filter { !it.isDirectory && !CfEnc.isEncryptedName(it.name) }.map { it.path }
    val encFiles = selected.filter { !it.isDirectory && CfEnc.isEncryptedName(it.name) }.map { it.path }
    val single = selected.singleOrNull()
    val kind = kindOfPath(state.tab.path)
    val archives = selected.filter { !it.isDirectory && ArchiveFormat.detect(it.name) != null }.map { it.path }
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ActionButton(Icons.Default.ContentCopy, R.string.copy, onClick = vm::copySelection)
        if (kind == LocationKind.ARCHIVE) {
            ActionButton(Icons.Default.FolderZip, R.string.extract) { vm.extractFromCurrentArchive(selected.map { it.path }) }
            return@Row
        }
        ActionButton(Icons.AutoMirrored.Filled.DriveFileMove, R.string.move, onClick = vm::cutSelection)
        ActionButton(Icons.Default.DeleteOutline, R.string.delete) { onDialog(if (kind == LocationKind.REMOTE) DialogState.RemoteDelete else DialogState.Delete) }
        if (selected.size == 1) ActionButton(Icons.Default.Edit, R.string.rename) { onDialog(DialogState.Rename(selected[0].path, selected[0].name)) }
        if (kind == LocationKind.REMOTE) return@Row
        ActionButton(Icons.Default.Share, R.string.share) { onShare(selected.filter { !it.isDirectory }.map { File(it.path) }) }
        if (single != null) ActionButton(Icons.Default.Info, R.string.properties) { onDialog(DialogState.Properties(single.path)) }
        ActionButton(Icons.Default.FolderZip, R.string.compress) { onDialog(DialogState.Compress(selected.map { it.path })) }
        if (archives.isNotEmpty()) ActionButton(Icons.Default.FolderZip, R.string.extract_here) { vm.extractArchives(archives) }
        Box {
            ActionButton(Icons.Default.MoreHoriz, R.string.security_menu) { securityMenu = true }
            DropdownMenu(securityMenu, { securityMenu = false }) {
                @androidx.compose.runtime.Composable fun item(label: Int, action: () -> Unit) =
                    DropdownMenuItem({ Text(stringResource(label)) }, { securityMenu = false; action() })
                if (single != null && single.isDirectory) {
                    if (single.path in protectedPaths) {
                        item(R.string.unprotect_folder) { requestAuth(R.string.auth_subtitle_change, false) { vm.setProtected(single.path, false) } }
                    } else {
                        item(R.string.protect_folder) { requestAuth(R.string.auth_subtitle_change, true) { vm.setProtected(single.path, true) } }
                    }
                }
                item(R.string.hide_items) { vm.hide(selected.map { it.path }) }
                if (plainFiles.isNotEmpty()) {
                    item(R.string.encrypt_items) { onDialog(DialogState.Crypto(CryptoAction.ENCRYPT, plainFiles)) }
                    item(R.string.hide_encrypt_items) { onDialog(DialogState.Crypto(CryptoAction.HIDE_ENCRYPT, plainFiles)) }
                }
                if (plainFiles.size == 1 && selected.size == 1) {
                    item(R.string.encrypt_share) { onDialog(DialogState.Crypto(CryptoAction.SHARE, plainFiles)) }
                }
                if (encFiles.isNotEmpty()) {
                    item(R.string.decrypt_items) { onDialog(DialogState.Crypto(CryptoAction.DECRYPT, encFiles)) }
                }
                if (encFiles.size == 1 && selected.size == 1 && kindOf(File(CfEnc.plainName(single!!.name))) != FileKind.OTHER) {
                    item(R.string.decrypt_view) { onDialog(DialogState.Crypto(CryptoAction.VIEW, encFiles)) }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: Int, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null)
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TransferBar(t: TransferProgress, onCancel: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    Surface(modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp, shadowElevation = 4.dp) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.currentName.ifEmpty { stringResource(R.string.transfer_working) }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                if (t.bytesTotal > 0) {
                    LinearProgressIndicator(progress = { t.fraction }, Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    Text(
                        Formatter.formatShortFileSize(context, t.bytesDone) + " / " + Formatter.formatShortFileSize(context, t.bytesTotal) + "  ·  " + t.filesDone + "/" + t.filesTotal,
                        style = MaterialTheme.typography.labelSmall,
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    if (t.bytesDone > 0) Text(Formatter.formatShortFileSize(context, t.bytesDone), style = MaterialTheme.typography.labelSmall)
                }
            }
            TextButton(onCancel) { Text(stringResource(R.string.cancel)) }
        }
    }
}
