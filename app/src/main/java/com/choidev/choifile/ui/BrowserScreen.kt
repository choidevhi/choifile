package com.choidev.choifile.ui

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.video.videoFramePercent
import com.choidev.choifile.browser.CellBounds
import com.choidev.choifile.browser.DragSelection
import com.choidev.choifile.browser.ExtractMode
import com.choidev.choifile.security.APP_PRIVATE_FOLDERS
import com.choidev.choifile.security.isSameOrInside
import com.choidev.choifile.storage.Favorite
import com.choidev.choifile.storage.FavoritesStore
import com.choidev.choifile.util.VideoDurations
import com.choidev.choifile.util.formatDuration
import kotlinx.coroutines.launch
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.choidev.choifile.browser.TapAction
import com.choidev.choifile.browser.TapRouting
import com.choidev.choifile.browser.TapTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.choidev.choifile.R
import com.choidev.choifile.archive.ArchiveFormat
import com.choidev.choifile.archive.ArchiveLocation
import com.choidev.choifile.browser.BrowserState
import com.choidev.choifile.browser.BrowserViewModel
import com.choidev.choifile.browser.DeleteMode
import com.choidev.choifile.browser.Listing
import com.choidev.choifile.browser.LocationKind
import com.choidev.choifile.browser.OriginalAction
import com.choidev.choifile.browser.Tab
import com.choidev.choifile.browser.kindOfPath
import com.choidev.choifile.crypto.CfEnc
import com.choidev.choifile.remote.TransferProgress
import com.choidev.choifile.storage.FileItem
import com.choidev.choifile.storage.ScrollPos
import com.choidev.choifile.storage.SortKey
import com.choidev.choifile.util.FileKind
import com.choidev.choifile.util.kindOf
import com.choidev.choifile.util.shareFiles
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
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
    /** "다른 위치에 풀기": pick the folder first. [entries] non-null = entries of the open archive tab. */
    data class ExtractTo(val archives: List<String>, val entries: List<String>?, val inArchiveTab: Boolean, val start: String) : DialogState
}

/** A location the comic viewer can open: a local folder, a local zip/cbz, or a folder inside a zip tab. */
fun comicCapable(path: String, isDirectory: Boolean): Boolean = when (kindOfPath(path)) {
    LocationKind.LOCAL -> isDirectory || ArchiveFormat.detect(path) == ArchiveFormat.ZIP
    LocationKind.ARCHIVE -> isDirectory && ArchiveFormat.detect(ArchiveLocation.archiveOf(path)) == ArchiveFormat.ZIP
    LocationKind.REMOTE -> false
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
    onOpenComic: (String) -> Unit,
    onOpenWith: (String) -> Unit,
    onOpenTool: (ToolScreen) -> Unit,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val transfer by vm.transfer.collectAsStateWithLifecycle()
    val listError by vm.listError.collectAsStateWithLifecycle()
    val listing by vm.listing.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val trash by vm.trash.collectAsStateWithLifecycle()
    val lockedRoot by vm.lockedRoot.collectAsStateWithLifecycle()
    val protectedPaths by vm.protectedPaths.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val missingFavorites by vm.missingFavorites.collectAsStateWithLifecycle()
    val requestAuth = rememberRequestAuth()
    val context = LocalContext.current
    val density = LocalDensity.current
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<DialogState?>(null) }
    val c = ChoiTheme.colors
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var dragSelecting by remember { mutableStateOf(false) }
    val drawerBlocked by rememberUpdatedState(dragSelecting || drawerState.isOpen)
    LaunchedEffect(drawerState.isOpen) { if (drawerState.isOpen) vm.refreshFavorites() }

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
    BackHandler(enabled = state.selection.isNotEmpty() || !atRoot) {
        if (state.selection.isNotEmpty()) vm.clearSelection() else vm.up()
    }
    // Registered after the one above, so with the drawer open "back" closes the drawer first.
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    val onTool: (DrawerTool) -> Unit = { tool ->
        scope.launch { drawerState.close() }
        when (tool) {
            DrawerTool.HIDDEN -> onOpenVault()
            DrawerTool.TRASH -> dialog = DialogState.Trash
            DrawerTool.CONNECTIONS -> onOpenConnections()
            DrawerTool.SEARCH -> onOpenSearch()
            DrawerTool.ANALYSIS -> onOpenTool(ToolScreen.ANALYSIS)
            DrawerTool.DUPLICATES -> onOpenTool(ToolScreen.DUPLICATES)
            DrawerTool.RULES -> onOpenTool(ToolScreen.RULES)
            DrawerTool.OPERATIONS -> onOpenTool(ToolScreen.OPERATIONS)
            DrawerTool.SERVER -> onOpenTool(ToolScreen.SERVER)
            DrawerTool.SETTINGS -> onOpenSettings()
        }
    }

    val bottomBarVisible = state.selection.isNotEmpty() || state.clipboard != null
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val padding = PaddingValues(
        top = with(density) { topPx.toDp() } + Space.xs,
        bottom = if (bottomBarVisible) with(density) { bottomPx.toDp() } + Space.xs else navInset + Space.sm,
    )

    // Swiping the list sideways moves between tabs; the tab strip and the view model follow the settled page.
    val pager = rememberPagerState(state.current) { state.tabs.size }
    val currentIndex by rememberUpdatedState(state.current)
    LaunchedEffect(state.current, state.tabs.size) {
        if (state.current in 0 until pager.pageCount && pager.currentPage != state.current) pager.animateScrollToPage(state.current)
    }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page -> if (page != currentIndex && page in 0 until vm.state.value.tabs.size) vm.selectTab(page) }
    }

    ModalNavigationDrawer(
        drawerContent = {
            ModalDrawerSheet(
                Modifier.width(300.dp),
                drawerShape = RoundedCornerShape(topEnd = Radius.xl, bottomEnd = Radius.xl),
                drawerContainerColor = c.background,
                drawerContentColor = c.foreground,
                drawerTonalElevation = 0.dp,
                windowInsets = WindowInsets(0),
            ) {
                BrowserDrawerContent(
                    favorites = favorites,
                    missing = missingFavorites,
                    storage = vm.storageShortcuts,
                    currentPath = state.tab.path,
                    onOpenFolder = { path -> scope.launch { drawerState.close() }; vm.openDir(path) },
                    onRemoveFavorite = vm::removeFavorite,
                    onRenameFavorite = vm::renameFavorite,
                    onMoveFavorite = vm::moveFavorite,
                    onTool = onTool,
                )
            }
        },
        drawerState = drawerState,
        // Closed, the drawer opens only from the menu button or a swipe from the very edge (below), so swiping the
        // list sideways keeps switching tabs. Open, it can be swiped shut.
        gesturesEnabled = drawerState.isOpen,
        scrimColor = c.scrim,
    ) {
    Box(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .edgeSwipeToOpen(20.dp, blocked = { drawerBlocked }) { scope.launch { drawerState.open() } },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
                .drawWithContent {
                    layer.record { this@drawWithContent.drawContent() }
                    drawLayer(layer)
                },
        ) {
            HorizontalPager(
                pager,
                Modifier.fillMaxSize(),
                key = { state.tabs.getOrNull(it)?.id ?: -it },
                userScrollEnabled = state.selection.isEmpty() && state.tabs.size > 1 && !dragSelecting,
            ) { page ->
                val tab = state.tabs.getOrNull(page) ?: return@HorizontalPager
                val isCurrent = page == state.current
                TabPage(
                    tab = tab,
                    isCurrent = isCurrent,
                    state = state,
                    listing = listing,
                    lockedRoot = if (isCurrent) lockedRoot else null,
                    listError = if (isCurrent) listError else null,
                    padding = padding,
                    protectedPaths = protectedPaths,
                    vm = vm,
                    onOpenFile = onOpenFile,
                    onUnlock = { root -> requestAuth(R.string.auth_subtitle_folder, false) { vm.unlock(root) } },
                    onDragSelecting = { dragSelecting = it },
                )
            }
        }

        GlassSurface(backdrop, Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { topPx = it.height }, GlassEdge.TOP) {
            Column(Modifier.statusBarsPadding()) {
                if (state.selection.isEmpty()) {
                    NavRow(state, atRoot, vm, favorites, onMenu = { scope.launch { drawerState.open() } }, onOpenComic = onOpenComic) { dialog = it }
                } else {
                    SelectionRow(state, vm)
                }
                TabStrip(state, vm)
            }
        }

        if (bottomBarVisible) {
            GlassSurface(backdrop, Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { bottomPx = it.height }, GlassEdge.BOTTOM) {
                Row(Modifier.navigationBarsPadding().padding(horizontal = Space.sm, vertical = Space.sm), verticalAlignment = Alignment.CenterVertically) {
                    val clip = state.clipboard
                    if (state.selection.isNotEmpty()) {
                        SelectionActions(
                            state, entries, vm, Modifier.weight(1f),
                            onDialog = { dialog = it }, protectedPaths = protectedPaths,
                            favorites = favorites,
                            noMedia = listing?.takeIf { it.path == state.tab.path }?.noMedia.orEmpty(),
                            onShare = { files ->
                                if (files.isEmpty()) {
                                    snackbar.currentSnackbarData?.dismiss()
                                    vm.notifyNoShareDirs()
                                } else shareFiles(context, files)
                            },
                            onOpenComic = { vm.clearSelection(); onOpenComic(it) },
                            onOpenWith = { vm.clearSelection(); onOpenWith(it) },
                        )
                    } else if (clip != null) {
                        Text(
                            stringResource(if (clip.move) R.string.clipboard_move else R.string.clipboard_copy, clip.paths.size),
                            Modifier.weight(1f).padding(start = Space.md),
                            style = MaterialTheme.typography.bodyMedium,
                            color = c.mutedForeground,
                        )
                        GhostButton(vm::clearClipboard) { Text(stringResource(R.string.cancel)) }
                        Spacer(Modifier.width(Space.xs))
                        PrimaryButton(vm::paste) {
                            Icon(Icons.Default.ContentPaste, null, Modifier.size(IconSize.sm))
                            Spacer(Modifier.width(Space.sm))
                            Text(stringResource(R.string.paste_count, clip.paths.size))
                        }
                    }
                }
            }
        }

        transfer?.let { t ->
            TransferBar(t, vm::cancelTransfer, Modifier.align(Alignment.BottomCenter).padding(bottom = (if (bottomBarVisible) with(density) { bottomPx.toDp() } else navInset) + Space.xs))
        }

        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).padding(bottom = if (bottomBarVisible) with(density) { bottomPx.toDp() } else navInset),
        )
    }
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
        DialogState.RemoteDelete -> ChoiDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.remote_delete_message, state.selection.size)) },
            confirmButton = { DestructiveButton({ vm.delete(DeleteMode.PERMANENT); dialog = null }) { Text(stringResource(R.string.delete)) } },
            dismissButton = { OutlineButton({ dialog = null }) { Text(stringResource(R.string.cancel)) } },
        )
        is DialogState.Properties -> PropertiesDialog(d.path) { dialog = null }
        is DialogState.Rename -> NameDialog(stringResource(R.string.rename), d.name, { vm.rename(d.path, it); dialog = null }, { dialog = null })
        is DialogState.ExtractTo -> FolderPickerDialog(
            rootPath = vm.rootPath,
            start = d.start,
            onPick = { target ->
                dialog = null
                if (d.inArchiveTab) vm.extractFromCurrentArchive(d.entries, ExtractMode.ELSEWHERE, target)
                else vm.extractArchives(d.archives, ExtractMode.ELSEWHERE, target)
            },
            onDismiss = { dialog = null },
        )
    }
}

/**
 * One tab inside the pager. The open tab shows the view model's listing; neighbours (visible while swiping)
 * list their own folder on demand. Locked protected folders never show their items.
 */
@Composable
private fun TabPage(
    tab: Tab,
    isCurrent: Boolean,
    state: BrowserState,
    listing: Listing?,
    lockedRoot: String?,
    listError: String?,
    padding: PaddingValues,
    protectedPaths: Set<String>,
    vm: BrowserViewModel,
    onOpenFile: (FileItem) -> Unit,
    onUnlock: (String) -> Unit,
    onDragSelecting: (Boolean) -> Unit,
) {
    var peeked by remember(tab.path) { mutableStateOf<Listing?>(null) }
    LaunchedEffect(tab.path, isCurrent, state.sort, state.ascending, state.showHidden) {
        if (!isCurrent) peeked = vm.peek(tab.path)
    }
    if (isCurrent && lockedRoot != null) {
        key(tab.path) { LockedView(R.string.auth_subtitle_folder, Modifier.padding(padding)) { onUnlock(lockedRoot) } }
        return
    }
    val data = listing?.takeIf { isCurrent && it.path == tab.path } ?: peeked?.takeIf { it.path == tab.path }
    when {
        data == null -> Box(Modifier.fillMaxSize())
        data.locked -> LockedView(R.string.auth_subtitle_folder, Modifier.padding(padding), autoPrompt = false) {}
        data.items.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            val c = ChoiTheme.colors
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Folder, null, Modifier.size(32.dp), tint = c.border)
                Spacer(Modifier.height(Space.md))
                Text(
                    (if (data.error) listError else null) ?: stringResource(R.string.empty_folder),
                    Modifier.padding(horizontal = Space.xl), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (data.error) c.destructive else c.mutedForeground,
                )
            }
        }
        else -> key(tab.path, tab.grid) { FileList(data.items, data.noMedia, tab, state, padding, protectedPaths, vm, onOpenFile, interactive = isCurrent, onDragSelecting) }
    }
}

@OptIn(ExperimentalFoundationApi::class, FlowPreview::class)
@Composable
private fun FileList(
    entries: List<FileItem>,
    noMedia: Set<String>,
    tab: Tab,
    state: BrowserState,
    padding: PaddingValues,
    protectedPaths: Set<String>,
    vm: BrowserViewModel,
    onOpenFile: (FileItem) -> Unit,
    interactive: Boolean,
    onDragSelecting: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val c = ChoiTheme.colors
    val selection = if (interactive) state.selection else emptySet()
    // Thumbnails of a protected folder or the app's private folders are never written to a disk cache.
    val privateFolder = remember(tab.path, protectedPaths) {
        protectedPaths.any { isSameOrInside(tab.path, it) } || tab.path.replace('\\', '/').split('/').any { it in APP_PRIVATE_FOLDERS }
    }

    // Restore where this folder was left (also across restarts); re-anchor by name if items moved.
    val start = remember {
        val saved = vm.scrollPosition(tab.path)
        if (saved == null) ScrollPos(0, 0) else {
            val atIndex = entries.getOrNull(saved.index)?.name
            if (saved.anchor == null || atIndex == saved.anchor) saved
            else entries.indexOfFirst { it.name == saved.anchor }.takeIf { it >= 0 }?.let { ScrollPos(it, saved.offset) }
                ?: ScrollPos(saved.index.coerceIn(0, entries.size - 1), 0)
        }
    }
    val listState = remember { LazyListState(start.index, start.offset) }
    val gridState = remember { LazyGridState(start.index, start.offset) }
    val currentEntries by rememberUpdatedState(entries)
    fun position(): ScrollPos {
        val i = if (tab.grid) gridState.firstVisibleItemIndex else listState.firstVisibleItemIndex
        val o = if (tab.grid) gridState.firstVisibleItemScrollOffset else listState.firstVisibleItemScrollOffset
        return ScrollPos(i, o, currentEntries.getOrNull(i)?.name)
    }
    LaunchedEffect(tab.path, tab.grid) {
        snapshotFlow { position() }.debounce(400).collect { vm.saveScrollPosition(tab.path, it) }
    }
    DisposableEffect(tab.path, tab.grid) { onDispose { vm.saveScrollPosition(tab.path, position()) } }

    // List: the icon starts (or extends) multi-select, the row opens. Grid: the cell opens, the corner check selects.
    fun tap(item: FileItem, target: TapTarget) {
        if (!interactive) return
        when (TapRouting.route(target, tab.grid, selecting = selection.isNotEmpty())) {
            TapAction.TOGGLE -> vm.toggleSelect(item.path)
            TapAction.OPEN -> if (item.isDirectory) vm.openDir(item.path) else onOpenFile(item)
        }
    }

    // ---- long-press and drag to select a range (list and grid) ----
    val haptic = LocalHapticFeedback.current
    val currentKeys by rememberUpdatedState(entries.map { it.path })
    val currentSelection by rememberUpdatedState(selection)
    var drag by remember { mutableStateOf<DragSelection?>(null) }
    var pointer by remember { mutableStateOf<Offset?>(null) }
    var viewportPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val topInsetPx = with(density) { padding.calculateTopPadding().toPx() }
    val bottomInsetPx = with(density) { padding.calculateBottomPadding().toPx() }

    fun cells(): List<CellBounds> = if (tab.grid) {
        val info = gridState.layoutInfo
        info.visibleItemsInfo.map {
            val top = (it.offset.y - info.viewportStartOffset).toFloat()
            CellBounds(it.index, it.offset.x.toFloat(), top, (it.offset.x + it.size.width).toFloat(), top + it.size.height)
        }
    } else {
        val info = listState.layoutInfo
        info.visibleItemsInfo.map {
            val top = (it.offset - info.viewportStartOffset).toFloat()
            CellBounds(it.index, 0f, top, Float.MAX_VALUE, top + it.size)
        }
    }

    fun dragTo(p: Offset) {
        val d = drag ?: return
        val index = DragSelection.indexAt(cells(), p.x, p.y) ?: return
        vm.setSelection(d.selectionAt(index))
    }

    // Auto-scroll while the finger rests near the top or bottom edge of the visible list.
    LaunchedEffect(drag != null) {
        if (drag == null) return@LaunchedEffect
        val zone = with(density) { 72.dp.toPx() }
        val maxStep = with(density) { 18.dp.toPx() }
        while (true) {
            withFrameNanos { }
            val p = pointer ?: continue
            val step = DragSelection.autoScrollStep(p.y, topInsetPx, viewportPx - bottomInsetPx, zone, maxStep)
            if (step != 0f) {
                if (tab.grid) gridState.scrollBy(step) else listState.scrollBy(step)
                dragTo(p)
            }
        }
    }

    val dragSelect = if (!interactive) Modifier else Modifier
        .onSizeChanged { viewportPx = it.height }
        .pointerInput(tab.grid) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // Wait for a long press; a release, a move (scroll, tab swipe) or a second finger cancels it.
                val notLong = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    var done = false
                    while (!done) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = e.changes.firstOrNull { it.id == down.id }
                        if (ch == null || !ch.pressed || ch.isConsumed || e.changes.size > 1 ||
                            (ch.position - down.position).getDistance() > viewConfiguration.touchSlop
                        ) done = true
                    }
                    true
                }
                if (notLong != null) return@awaitEachGesture
                val keys = currentKeys
                val anchor = DragSelection.indexAt(cells(), down.position.x, down.position.y)
                    ?.takeIf { it in keys.indices } ?: return@awaitEachGesture
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                val d = DragSelection(keys, currentSelection, anchor)
                drag = d
                pointer = down.position
                onDragSelecting(true)
                vm.setSelection(d.selectionAt(anchor))
                try {
                    // From here on the gesture is ours: the row does not see a click, the list does not scroll
                    // and the pager does not switch tabs.
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = e.changes.firstOrNull { it.id == down.id }
                        e.changes.forEach { it.consume() }
                        if (ch == null || !ch.pressed) break
                        pointer = ch.position
                        dragTo(ch.position)
                    }
                } finally {
                    drag = null
                    pointer = null
                    onDragSelecting(false)
                }
            }
        }

    if (tab.grid) {
        LazyVerticalGrid(
            GridCells.Adaptive(108.dp), Modifier.fillMaxSize().then(dragSelect), state = gridState,
            contentPadding = PaddingValues(start = Space.sm, end = Space.sm, top = padding.calculateTopPadding() + Space.xs, bottom = padding.calculateBottomPadding()),
        ) {
            items(entries, key = { it.path }) { item ->
                val selected = item.path in selection
                Box(
                    Modifier
                        .padding(Space.xs)
                        .clip(RoundedCornerShape(Radius.lg))
                        .background(if (selected) c.selection else c.background)
                        .border(Hairline, if (selected) c.ring else Color.Transparent, RoundedCornerShape(Radius.lg))
                        .clickable { tap(item, TapTarget.BODY) },
                ) {
                    Column(Modifier.padding(Space.sm), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(Radius.lg))) {
                            FileThumb(item, Modifier.fillMaxSize(), large = true, privateFolder = privateFolder)
                            if (item.path in noMedia) NoMediaBadge(Modifier.align(Alignment.TopEnd).padding(Space.xs))
                        }
                        Spacer(Modifier.height(Space.sm))
                        Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
                    }
                    GridCheck(selected, selecting = selection.isNotEmpty(), Modifier.align(Alignment.TopStart)) { tap(item, TapTarget.CHECK) }
                }
            }
        }
    } else {
        LazyColumn(Modifier.fillMaxSize().then(dragSelect), state = listState, contentPadding = padding) {
            items(entries, key = { it.path }) { item ->
                val selected = item.path in selection
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (selected) c.selection else c.background)
                        .clickable { tap(item, TapTarget.BODY) }
                        .padding(horizontal = Space.lg, vertical = Space.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(Radius.lg)).clickable { tap(item, TapTarget.ICON) }) {
                        FileThumb(item, Modifier.fillMaxSize(), large = false, privateFolder = privateFolder)
                        if (selected) {
                            Box(Modifier.fillMaxSize().background(c.primary.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Check, null, Modifier.size(IconSize.md), tint = c.primaryForeground)
                            }
                        }
                    }
                    Spacer(Modifier.width(Space.md))
                    Column(Modifier.weight(1f)) {
                        Text(item.name, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, style = MaterialTheme.typography.bodyLarge)
                        val date = dateFormat.format(Date(item.modified))
                        val duration = rememberVideoDuration(item)
                        Text(
                            when {
                                item.isDirectory -> date
                                duration != null -> Formatter.formatShortFileSize(context, item.size) + "  ·  " + formatDuration(duration) + "  ·  " + date
                                else -> Formatter.formatShortFileSize(context, item.size) + "  ·  " + date
                            },
                            style = MaterialTheme.typography.bodySmall, color = c.mutedForeground, maxLines = 1,
                        )
                    }
                    if (item.path in noMedia) {
                        Icon(Icons.Default.VisibilityOff, stringResource(R.string.nomedia_badge), Modifier.padding(start = Space.sm).size(IconSize.sm), tint = c.mutedForeground)
                    }
                    if (item.path in protectedPaths) {
                        Icon(Icons.Default.Lock, stringResource(R.string.protect_folder), Modifier.padding(start = Space.sm).size(IconSize.sm), tint = c.mutedForeground)
                    }
                }
            }
        }
    }
}

/**
 * Round check in a grid cell's top-left corner: a 40dp touch target around a 22dp circle. Filled when selected,
 * a clear ring on every cell while selecting, and a faint ring otherwise (tapping it starts multi-select).
 */
@Composable
private fun GridCheck(selected: Boolean, selecting: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = ChoiTheme.colors
    val label = stringResource(R.string.grid_select)
    Box(
        modifier.size(40.dp).clip(CircleShape).clickable(onClickLabel = label, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        when {
            selected -> Box(Modifier.size(22.dp).background(c.primary, CircleShape).border(1.5.dp, c.background, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, null, Modifier.size(IconSize.xs), tint = c.primaryForeground)
            }
            selecting -> Box(Modifier.size(22.dp).background(Color.Black.copy(alpha = 0.25f), CircleShape).border(1.5.dp, Color.White, CircleShape))
            else -> Box(Modifier.size(20.dp).alpha(0.55f).background(Color.Black.copy(alpha = 0.15f), CircleShape).border(1.dp, Color.White, CircleShape))
        }
    }
}

/** Marks a folder that contains .nomedia (hidden from galleries). */
@Composable
private fun NoMediaBadge(modifier: Modifier) {
    val c = ChoiTheme.colors
    Box(modifier.size(22.dp).background(c.background, CircleShape).border(Hairline, c.border, CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.VisibilityOff, stringResource(R.string.nomedia_badge), Modifier.size(IconSize.xs), tint = c.mutedForeground)
    }
}

/** Duration of a local video (null for anything else, or until it is known). Read off the main thread, cached in memory. */
@Composable
private fun rememberVideoDuration(item: FileItem): Long? {
    val video = !item.isDirectory && kindOfPath(item.path) == LocationKind.LOCAL && kindOf(File(item.name)) == FileKind.VIDEO
    if (!video) return null
    val d by produceState(VideoDurations.cached(item.path, item.modified), item.path, item.modified) {
        if (value == null) value = VideoDurations.of(item.path, item.modified)
    }
    return d
}

/**
 * Icon tile (muted square with a 1px border), or for local images and videos a thumbnail decoded at the cell's
 * size (Coil; a video shows one frame plus a play mark and its duration). Leaving the screen cancels the decode.
 */
@Composable
private fun FileThumb(item: FileItem, modifier: Modifier, large: Boolean, privateFolder: Boolean) {
    val c = ChoiTheme.colors
    val context = LocalContext.current
    val kind = if (item.isDirectory) null else kindOf(File(item.path))
    if ((kind == FileKind.IMAGE || kind == FileKind.VIDEO) && kindOfPath(item.path) == LocationKind.LOCAL) {
        val request = remember(item.path, item.modified, privateFolder) {
            ImageRequest.Builder(context)
                .data(File(item.path))
                .apply {
                    if (privateFolder) diskCachePolicy(CachePolicy.DISABLED)
                    if (kind == FileKind.VIDEO) videoFramePercent(0.1)
                }
                .build()
        }
        Box(modifier.background(c.muted)) {
            AsyncImage(request, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (kind == FileKind.VIDEO) {
                val scrim = Color.Black.copy(alpha = 0.55f)
                Box(
                    Modifier.align(Alignment.Center).size(if (large) 30.dp else 20.dp).background(scrim, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.PlayArrow, null, Modifier.size(if (large) IconSize.md else IconSize.xs), tint = Color.White) }
                if (large) {
                    rememberVideoDuration(item)?.let { ms ->
                        Text(
                            formatDuration(ms),
                            Modifier.align(Alignment.BottomEnd).padding(Space.xs).background(scrim, RoundedCornerShape(Radius.sm)).padding(horizontal = Space.xs),
                            style = MaterialTheme.typography.labelSmall, color = Color.White,
                        )
                    }
                }
            }
        }
    } else {
        val icon: ImageVector = when (kind) {
            null -> Icons.Default.Folder
            FileKind.VIDEO -> Icons.Default.Movie
            FileKind.TEXT, FileKind.MARKDOWN -> Icons.Default.Description
            FileKind.PDF -> Icons.Default.PictureAsPdf
            else -> if (ArchiveFormat.detect(item.name) != null) Icons.Default.FolderZip else Icons.AutoMirrored.Filled.InsertDriveFile
        }
        Box(
            modifier.background(if (kind == null) c.secondary else c.background).border(Hairline, c.border, RoundedCornerShape(Radius.lg)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, Modifier.size(if (large) 36.dp else IconSize.md), tint = if (kind == null) c.foreground else c.mutedForeground)
        }
    }
}

@Composable
private fun NavRow(
    state: BrowserState,
    atRoot: Boolean,
    vm: BrowserViewModel,
    favorites: List<Favorite>,
    onMenu: () -> Unit,
    onOpenComic: (String) -> Unit,
    onDialog: (DialogState) -> Unit,
) {
    val path = state.tab.path
    val kind = kindOfPath(path)
    val c = ChoiTheme.colors
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onMenu) { Icon(Icons.Default.Menu, stringResource(R.string.drawer_open), Modifier.size(IconSize.md)) }
        IconButton(vm::up, enabled = !atRoot) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.up), Modifier.size(IconSize.md)) }
        Column(Modifier.weight(1f).padding(start = Space.xxs)) {
            Text(vm.tabLabel(path), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            Text(vm.displayPath(path), maxLines = 1, overflow = TextOverflow.StartEllipsis, style = MaterialTheme.typography.bodySmall, color = c.mutedForeground)
        }
        IconButton(vm::toggleGrid) {
            Icon(if (state.tab.grid) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView, stringResource(if (state.tab.grid) R.string.view_list else R.string.view_grid), Modifier.size(IconSize.md))
        }
        Box {
            IconButton({ sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.sort), Modifier.size(IconSize.md)) }
            ChoiMenu(sortMenu, { sortMenu = false }) {
                listOf(SortKey.NAME to R.string.sort_name, SortKey.SIZE to R.string.sort_size, SortKey.DATE to R.string.sort_date).forEach { (key, label) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(label)) },
                        leadingIcon = { Checkbox(state.sort == key, null) },
                        onClick = { vm.setSort(key); sortMenu = false },
                    )
                }
                HorizontalDivider(thickness = Hairline, color = c.border)
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sort_ascending)) },
                    leadingIcon = { Checkbox(state.ascending, null) },
                    onClick = { vm.toggleAscending(); sortMenu = false },
                )
            }
        }
        Box {
            IconButton({ moreMenu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more), Modifier.size(IconSize.md)) }
            // Short on purpose: things to do with this folder. Places and tools live in the drawer.
            ChoiMenu(moreMenu, { moreMenu = false }) {
                @Composable fun item(label: Int, action: () -> Unit) = DropdownMenuItem({ Text(stringResource(label)) }, { moreMenu = false; action() })
                val local = kind == LocationKind.LOCAL
                val hasNoMedia = remember(moreMenu, path) { local && vm.hasNoMedia(path) }
                if (kind != LocationKind.ARCHIVE) {
                    item(R.string.new_folder) { onDialog(DialogState.NewFolder) }
                    item(R.string.new_text_file) { onDialog(DialogState.NewFile) }
                }
                if (local) {
                    item(R.string.new_dummy_file) { onDialog(DialogState.NewDummy) }
                    if (!hasNoMedia) item(R.string.nomedia_new) { vm.toggleNoMedia(listOf(path), on = true) }
                }
                if (kind == LocationKind.ARCHIVE) {
                    // The whole archive; to take only some entries, select them and use 추출 in the bottom bar.
                    item(R.string.extract_mode_here) { vm.extractFromCurrentArchive(null, ExtractMode.HERE) }
                    item(R.string.extract_mode_folder) { vm.extractFromCurrentArchive(null, ExtractMode.FOLDER) }
                    item(R.string.extract_mode_elsewhere) {
                        onDialog(DialogState.ExtractTo(emptyList(), null, true, File(ArchiveLocation.archiveOf(path)).parent ?: vm.rootPath))
                    }
                }
                if (comicCapable(path, isDirectory = true)) item(R.string.comic_open_here) { onOpenComic(path) }
                HorizontalDivider(thickness = Hairline, color = c.border)
                if (vm.canFavorite(path)) {
                    val starred = favorites.any { it.path == FavoritesStore.normalize(path) }
                    DropdownMenuItem(
                        text = { Text(stringResource(if (starred) R.string.fav_remove else R.string.fav_add)) },
                        leadingIcon = { Icon(if (starred) Icons.Default.Star else Icons.Default.StarBorder, null, Modifier.size(IconSize.md)) },
                        onClick = { moreMenu = false; vm.toggleFavorite(path) },
                    )
                }
                if (local) {
                    DropdownMenuItem(
                        text = { Text(stringResource(if (hasNoMedia) R.string.nomedia_off else R.string.nomedia_on)) },
                        leadingIcon = { Icon(Icons.Default.VisibilityOff, null, Modifier.size(IconSize.md)) },
                        onClick = { moreMenu = false; vm.toggleNoMedia(listOf(path), on = !hasNoMedia) },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.show_hidden)) },
                    leadingIcon = { Checkbox(state.showHidden, null) },
                    onClick = { vm.toggleHidden(); moreMenu = false },
                )
            }
        }
    }
}

@Composable
private fun SelectionRow(state: BrowserState, vm: BrowserViewModel) {
    Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        IconButton(vm::clearSelection) { Icon(Icons.Default.Close, stringResource(R.string.close), Modifier.size(IconSize.md)) }
        Text(stringResource(R.string.selected_count, state.selection.size), Modifier.weight(1f).padding(start = Space.xs), style = MaterialTheme.typography.titleMedium)
        IconButton(vm::selectAll) { Icon(Icons.Default.SelectAll, stringResource(R.string.select_all), Modifier.size(IconSize.md)) }
    }
}

@Composable
private fun TabStrip(state: BrowserState, vm: BrowserViewModel) {
    val c = ChoiTheme.colors
    val rowState = rememberLazyListState()
    LaunchedEffect(state.current, state.tabs.size) { rowState.animateScrollToItem(state.current.coerceAtLeast(0)) }
    LazyRow(
        Modifier.fillMaxWidth().padding(bottom = Space.sm),
        state = rowState,
        contentPadding = PaddingValues(horizontal = Space.md),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(state.tabs, key = { _, t -> t.id }) { index, tab ->
            val on = index == state.current
            Row(
                Modifier
                    .height(32.dp)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(if (on) c.secondary else Color.Transparent)
                    .border(Hairline, if (on) c.border else Color.Transparent, RoundedCornerShape(Radius.md))
                    .clickable { vm.selectTab(index) }
                    .padding(start = Space.md, end = if (state.tabs.size > 1) Space.xs else Space.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    vm.tabLabel(tab.path), Modifier.widthIn(max = 132.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge, color = if (on) c.foreground else c.mutedForeground,
                )
                if (state.tabs.size > 1) {
                    Box(Modifier.padding(start = Space.xxs).size(22.dp).clip(RoundedCornerShape(Radius.sm)).clickable { vm.closeTab(index) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Close, stringResource(R.string.tab_close), Modifier.size(IconSize.xs), tint = c.mutedForeground)
                    }
                }
            }
        }
        item {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(Radius.md)).border(Hairline, c.border, RoundedCornerShape(Radius.md)).clickable(onClick = vm::addTab),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Add, stringResource(R.string.tab_new), Modifier.size(IconSize.sm)) }
        }
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
    favorites: List<Favorite>,
    noMedia: Set<String>,
    onOpenComic: (String) -> Unit,
    onOpenWith: (String) -> Unit,
) {
    val selected = entries.filter { it.path in state.selection }
    val requestAuth = rememberRequestAuth()
    var securityMenu by remember { mutableStateOf(false) }
    var extractMenu by remember { mutableStateOf(false) }
    val folders = selected.filter { it.isDirectory }.map { it.path }
    val plainFiles = selected.filter { !it.isDirectory && !CfEnc.isEncryptedName(it.name) }.map { it.path }
    val encFiles = selected.filter { !it.isDirectory && CfEnc.isEncryptedName(it.name) }.map { it.path }
    val single = selected.singleOrNull()
    val kind = kindOfPath(state.tab.path)
    val archives = selected.filter { !it.isDirectory && ArchiveFormat.detect(it.name) != null }.map { it.path }
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.xxs)) {
        ActionButton(Icons.Default.ContentCopy, R.string.copy, onClick = vm::copySelection)
        if (kind == LocationKind.ARCHIVE) {
            Box {
                ActionButton(Icons.Default.Unarchive, R.string.extract) { extractMenu = true }
                ExtractMenu(extractMenu, { extractMenu = false }) { mode ->
                    val picked = selected.map { it.path }
                    if (mode == ExtractMode.ELSEWHERE) {
                        onDialog(DialogState.ExtractTo(emptyList(), picked, true, File(ArchiveLocation.archiveOf(state.tab.path)).parent ?: vm.rootPath))
                    } else vm.extractFromCurrentArchive(picked, mode)
                }
            }
            if (single != null && comicCapable(single.path, single.isDirectory)) ActionButton(Icons.AutoMirrored.Filled.MenuBook, R.string.comic_open) { onOpenComic(single.path) }
            return@Row
        }
        ActionButton(Icons.AutoMirrored.Filled.DriveFileMove, R.string.move, onClick = vm::cutSelection)
        ActionButton(Icons.Default.DeleteOutline, R.string.delete) { onDialog(if (kind == LocationKind.REMOTE) DialogState.RemoteDelete else DialogState.Delete) }
        if (selected.size == 1) ActionButton(Icons.Default.Edit, R.string.rename) { onDialog(DialogState.Rename(selected[0].path, selected[0].name)) }
        if (kind == LocationKind.REMOTE) return@Row
        if (single != null && single.isDirectory && vm.canFavorite(single.path)) {
            val starred = favorites.any { it.path == FavoritesStore.normalize(single.path) }
            ActionButton(if (starred) Icons.Default.Star else Icons.Default.StarBorder, if (starred) R.string.fav_unstar else R.string.fav_star) {
                vm.toggleFavorite(single.path)
            }
        }
        ActionButton(Icons.Default.Share, R.string.share) { onShare(selected.filter { !it.isDirectory }.map { File(it.path) }) }
        if (single != null && comicCapable(single.path, single.isDirectory)) ActionButton(Icons.AutoMirrored.Filled.MenuBook, R.string.comic_open) { onOpenComic(single.path) }
        if (single != null && !single.isDirectory) ActionButton(Icons.AutoMirrored.Filled.OpenInNew, R.string.open_with_short) { onOpenWith(single.path) }
        if (single != null) ActionButton(Icons.Default.Info, R.string.properties) { onDialog(DialogState.Properties(single.path)) }
        ActionButton(Icons.Default.FolderZip, R.string.compress) { onDialog(DialogState.Compress(selected.map { it.path })) }
        if (archives.isNotEmpty()) {
            Box {
                ActionButton(Icons.Default.Unarchive, R.string.extract_here) { extractMenu = true }
                ExtractMenu(extractMenu, { extractMenu = false }) { mode ->
                    if (mode == ExtractMode.ELSEWHERE) onDialog(DialogState.ExtractTo(archives, null, false, state.tab.path))
                    else vm.extractArchives(archives, mode)
                }
            }
        }
        Box {
            ActionButton(Icons.Default.MoreHoriz, R.string.security_menu) { securityMenu = true }
            ChoiMenu(securityMenu, { securityMenu = false }) {
                @Composable fun item(label: Int, action: () -> Unit) =
                    DropdownMenuItem({ Text(stringResource(label)) }, { securityMenu = false; action() })
                if (single != null && single.isDirectory) {
                    if (single.path in protectedPaths) {
                        item(R.string.unprotect_folder) { requestAuth(R.string.auth_subtitle_change, false) { vm.setProtected(single.path, false) } }
                    } else {
                        item(R.string.protect_folder) { requestAuth(R.string.auth_subtitle_change, true) { vm.setProtected(single.path, true) } }
                    }
                }
                if (folders.isNotEmpty()) {
                    // All selected folders already hidden from galleries: offer to show them again, otherwise hide all.
                    val allMarked = folders.all { it in noMedia }
                    item(if (allMarked) R.string.nomedia_off else R.string.nomedia_on) { vm.toggleNoMedia(folders, on = !allMarked) }
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

/** 여기에 풀기 / 폴더로 풀기 / 다른 위치에 풀기. Nothing existing is overwritten in any of them. */
@Composable
private fun ExtractMenu(expanded: Boolean, onDismiss: () -> Unit, onPick: (ExtractMode) -> Unit) {
    ChoiMenu(expanded, onDismiss) {
        listOf(
            ExtractMode.HERE to R.string.extract_mode_here,
            ExtractMode.FOLDER to R.string.extract_mode_folder,
            ExtractMode.ELSEWHERE to R.string.extract_mode_elsewhere,
        ).forEach { (mode, label) ->
            DropdownMenuItem({ Text(stringResource(label)) }, { onDismiss(); onPick(mode) })
        }
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: Int, onClick: () -> Unit) {
    Column(
        Modifier.widthIn(min = 56.dp).clip(RoundedCornerShape(Radius.md)).clickable(onClick = onClick).padding(horizontal = Space.sm, vertical = Space.xs + 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(IconSize.md))
        Spacer(Modifier.height(Space.xs))
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun TransferBar(t: TransferProgress, onCancel: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val c = ChoiTheme.colors
    Surface(
        modifier.fillMaxWidth().padding(horizontal = Space.md),
        shape = RoundedCornerShape(Radius.xl),
        color = c.popover,
        border = BorderStroke(Hairline, c.border),
        shadowElevation = 8.dp,
    ) {
        Row(Modifier.padding(start = Space.lg, end = Space.sm, top = Space.md, bottom = Space.md), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.currentName.ifEmpty { stringResource(R.string.transfer_working) }, maxLines = 1, overflow = TextOverflow.MiddleEllipsis, style = MaterialTheme.typography.bodyMedium)
                if (t.bytesTotal > 0) {
                    LinearProgressIndicator(progress = { t.fraction }, Modifier.fillMaxWidth().padding(vertical = Space.sm), color = c.primary, trackColor = c.muted, drawStopIndicator = {})
                    Text(
                        Formatter.formatShortFileSize(context, t.bytesDone) + " / " + Formatter.formatShortFileSize(context, t.bytesTotal) + "  ·  " + t.filesDone + "/" + t.filesTotal,
                        style = MaterialTheme.typography.labelSmall, color = c.mutedForeground,
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = Space.sm), color = c.primary, trackColor = c.muted)
                    if (t.bytesDone > 0) Text(Formatter.formatShortFileSize(context, t.bytesDone), style = MaterialTheme.typography.labelSmall, color = c.mutedForeground)
                }
            }
            GhostButton(onCancel) { Text(stringResource(R.string.cancel)) }
        }
    }
}
