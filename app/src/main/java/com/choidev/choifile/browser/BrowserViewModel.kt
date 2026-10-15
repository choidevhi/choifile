package com.choidev.choifile.browser

import android.app.Application
import android.os.Environment
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.choidev.choifile.R
import com.choidev.choifile.archive.ArchiveCancelledException
import com.choidev.choifile.archive.ArchiveEntry
import com.choidev.choifile.archive.ArchiveExtractor
import com.choidev.choifile.archive.ArchiveFormat
import com.choidev.choifile.archive.ArchiveLimitException
import com.choidev.choifile.archive.ArchiveLocation
import com.choidev.choifile.archive.ArchiveReader
import com.choidev.choifile.archive.ArchiveSecurityException
import com.choidev.choifile.archive.ArchiveUnsupportedException
import com.choidev.choifile.archive.ArchiveWriter
import com.choidev.choifile.archive.archiveBaseName
import com.choidev.choifile.archive.childrenOf
import com.choidev.choifile.remote.CancelToken
import com.choidev.choifile.remote.ConnectionConfig
import com.choidev.choifile.remote.HostKeyDecision
import com.choidev.choifile.remote.HostKeyPolicy
import com.choidev.choifile.remote.HostKeyStore
import com.choidev.choifile.remote.KeystoreKey
import com.choidev.choifile.remote.LocalRemoteStore
import com.choidev.choifile.remote.RemoteFactory
import com.choidev.choifile.remote.RemoteLocation
import com.choidev.choifile.remote.RemotePaths
import com.choidev.choifile.remote.RemoteSessions
import com.choidev.choifile.remote.RemoteStore
import com.choidev.choifile.remote.Tailscale
import com.choidev.choifile.remote.TransferCancelledException
import com.choidev.choifile.remote.TransferEngine
import com.choidev.choifile.remote.TransferProgress
import com.choidev.choifile.AppStores
import com.choidev.choifile.oplog.FileOps
import com.choidev.choifile.oplog.NoUndo
import com.choidev.choifile.oplog.OpRecord
import com.choidev.choifile.oplog.OpType
import com.choidev.choifile.oplog.SkipReason
import com.choidev.choifile.rules.PlannedItem
import com.choidev.choifile.rules.Rule
import com.choidev.choifile.rules.RuleEngine
import com.choidev.choifile.rules.RuleProblem
import com.choidev.choifile.rules.RuleWorker
import com.choidev.choifile.security.PathGuard
import com.choidev.choifile.search.SearchController
import com.choidev.choifile.util.FileKind
import com.choidev.choifile.util.kindOf
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import com.choidev.choifile.crypto.CfEnc
import com.choidev.choifile.crypto.CfEncAuthException
import com.choidev.choifile.crypto.CfEncFormatException
import com.choidev.choifile.security.AppPrefs
import com.choidev.choifile.security.ProtectedFolders
import com.choidev.choifile.security.SavedTab
import com.choidev.choifile.storage.Favorite
import com.choidev.choifile.storage.FavoriteAdd
import com.choidev.choifile.storage.FavoritesStore
import com.choidev.choifile.storage.FileItem
import com.choidev.choifile.storage.NoMedia
import com.choidev.choifile.storage.FileStore
import com.choidev.choifile.storage.LocalFileStore
import com.choidev.choifile.storage.SortKey
import com.choidev.choifile.storage.SortSpec
import com.choidev.choifile.storage.ScrollPos
import com.choidev.choifile.storage.TrashEntry
import com.choidev.choifile.storage.sortItems
import com.choidev.choifile.util.Associations
import com.choidev.choifile.util.OpenChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class LocationKind { LOCAL, REMOTE, ARCHIVE }

fun kindOfPath(path: String): LocationKind = when {
    RemoteLocation.isRemote(path) -> LocationKind.REMOTE
    ArchiveLocation.isArchive(path) -> LocationKind.ARCHIVE
    else -> LocationKind.LOCAL
}

/** Shown to the user when an SFTP host key is new or changed. [reply] unblocks the connecting thread. */
data class HostKeyRequest(val host: String, val port: Int, val fingerprint: String, val previous: String?, val reply: (HostKeyDecision) -> Unit)

sealed interface ConnectionTest {
    data object Running : ConnectionTest
    data object Ok : ConnectionTest
    data class Failed(val message: String) : ConnectionTest
}

/**
 * One tab: its folder, how that folder is shown (grid, a sort of its own when the user set one for it) and the
 * filter on it.
 */
data class Tab(
    val id: Int,
    val path: String,
    val grid: Boolean = false,
    val sort: SortSpec? = null,
    val filter: FileFilter = FileFilter(),
)

/** Items of one folder. [path] tells which folder, so a stale listing is never shown for another one. */
data class Listing(
    val path: String,
    val items: List<FileItem>,
    val error: Boolean = false,
    val locked: Boolean = false,
    /** Folders in [items] that contain a .nomedia file (local folders only). */
    val noMedia: Set<String> = emptySet(),
    /** Items in the folder before the tab's filter, for "3 of 120". */
    val unfiltered: Int = items.size,
)

/** Where an archive goes: next to it (merged), into a new folder named after it, or merged into a chosen folder. */
enum class ExtractMode { HERE, FOLDER, ELSEWHERE }

data class Clipboard(val paths: List<String>, val move: Boolean)

data class BrowserState(
    val tabs: List<Tab>,
    val current: Int = 0,
    val sort: SortKey = SortKey.NAME,
    val ascending: Boolean = true,
    val foldersFirst: Boolean = true,
    val showHidden: Boolean = false,
    val selection: Set<String> = emptySet(),
    val clipboard: Clipboard? = null,
) {
    val tab: Tab get() = tabs[current]
}

enum class DeleteMode { TRASH, PERMANENT, SECURE }

/** What to do with the plaintext original after encrypting it. */
enum class OriginalAction { KEEP, TRASH, PERMANENT, SECURE }

/**
 * One-shot event for the UI. [undo] non-empty means the action can be reverted; [shareFile] asks the UI
 * to open the share sheet; [viewTemp] asks it to open a decrypted temporary copy in the viewer.
 */
data class UiEvent(
    val message: String,
    /** Id of an operation-log record the snackbar offers to undo. */
    val undoRecord: String? = null,
    val shareFile: String? = null,
    val viewTemp: String? = null,
)

private const val MAX_VIEW_BYTES = 200L * 1024 * 1024

private data class Persisted(val tabs: List<SavedTab>, val current: Int, val sort: SortKey, val ascending: Boolean, val foldersFirst: Boolean, val showHidden: Boolean)

class BrowserViewModel(app: Application) : AndroidViewModel(app) {

    private val store: FileStore = LocalFileStore(Environment.getExternalStorageDirectory())
    val rootPath: String = store.root.absolutePath

    val prefs = AppPrefs(app)
    val protection = ProtectedFolders(prefs.protectedFolders, prefs.relockSeconds * 1000L)

    val search = SearchController(app, viewModelScope, rootPath, protection, prefs)

    // ---- operation log, analysis, duplicates, automation ----
    private val opLog = AppStores.log(app)
    private val viewModes = AppStores.viewModes(app)
    private val folderSorts = AppStores.folderSorts(app)
    private val ops = FileOps(store, opLog)
    private val ruleStore = AppStores.rules(app)
    private val _opRecords = MutableStateFlow(opLog.all())
    val opRecords: StateFlow<List<OpRecord>> = _opRecords
    private val opListener: (List<OpRecord>) -> Unit = { _opRecords.value = it }
    private val _rules = MutableStateFlow(ruleStore.all())
    val rules: StateFlow<List<Rule>> = _rules

    private fun guard() = PathGuard(protection.paths, isLocked = { protection.lockedRoot(it) != null })
    private fun automationGuard() = PathGuard(protection.paths, excludeAllProtected = true)

    val analysis = AnalysisController(viewModelScope, store.root, ::guard)
    val duplicates = DuplicatesController(viewModelScope, store.root, ::guard, ops)

    // ---- network connections ----
    private val connectionRepo by lazy { KeystoreKey.repo(app) }
    private val hostKeyStore = HostKeyStore(File(app.filesDir, "known_hosts.json"))
    private val hostPolicy = HostKeyPolicy(hostKeyStore, ::askHostKey)
    private val sessions = RemoteSessions({ id -> connectionRepo.load().connections.firstOrNull { it.id == id } }, hostPolicy)

    private val _connections = MutableStateFlow<List<ConnectionConfig>>(emptyList())
    val connections: StateFlow<List<ConnectionConfig>> = _connections
    private val _vaultError = MutableStateFlow<String?>(null)
    val vaultError: StateFlow<String?> = _vaultError
    private val _connectionTest = MutableStateFlow<ConnectionTest?>(null)
    val connectionTest: StateFlow<ConnectionTest?> = _connectionTest
    private val _tailscaleKeySet = MutableStateFlow(false)
    val tailscaleKeySet: StateFlow<Boolean> = _tailscaleKeySet
    private val _tailscaleDevices = MutableStateFlow<List<Tailscale.Device>?>(null)
    val tailscaleDevices: StateFlow<List<Tailscale.Device>?> = _tailscaleDevices
    private val _tailscaleStatus = MutableStateFlow<String?>(null)
    val tailscaleStatus: StateFlow<String?> = _tailscaleStatus
    private val _reach = MutableStateFlow<Tailscale.Reach?>(null)
    val reach: StateFlow<Tailscale.Reach?> = _reach
    private val _hostKeyRequest = MutableStateFlow<HostKeyRequest?>(null)
    val hostKeyRequest: StateFlow<HostKeyRequest?> = _hostKeyRequest

    private val _transfer = MutableStateFlow<TransferProgress?>(null)
    val transfer: StateFlow<TransferProgress?> = _transfer
    private var cancelToken: CancelToken? = null

    private val _listError = MutableStateFlow<String?>(null)
    val listError: StateFlow<String?> = _listError
    private val archiveCache = HashMap<String, List<ArchiveEntry>>()

    private val _state = MutableStateFlow(restoreState())
    val state: StateFlow<BrowserState> = _state

    private val refresh = MutableStateFlow(0)
    private val lockTick = MutableStateFlow(0)
    private var nextTabId = _state.value.tabs.size

    private val events = Channel<UiEvent>(Channel.BUFFERED)
    val uiEvents = events.receiveAsFlow()

    private val _trash = MutableStateFlow<List<TrashEntry>>(emptyList())
    val trash: StateFlow<List<TrashEntry>> = _trash

    private val _hidden = MutableStateFlow<List<TrashEntry>>(emptyList())
    val hidden: StateFlow<List<TrashEntry>> = _hidden

    private val _protectedPaths = MutableStateFlow(protection.paths)
    val protectedPaths: StateFlow<Set<String>> = _protectedPaths

    /** The protected folder that currently blocks the open tab, or null when the tab may be shown. */
    val lockedRoot: StateFlow<String?> = combine(_state.map { it.tab.path }.distinctUntilChanged(), lockTick) { path, _ ->
        protection.lockedRoot(physicalPath(path))
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, protection.lockedRoot(physicalPath(_state.value.tab.path)))

    private data class Query(val path: String, val sort: SortSpec, val hidden: Boolean, val filter: FileFilter)

    /** The open tab's listing, tagged with the folder it belongs to (null until the first listing arrives). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val listing: StateFlow<Listing?> = combine(
        _state.map { Query(it.tab.path, effectiveSort(it), it.showHidden, it.tab.filter) }.distinctUntilChanged(),
        refresh,
        lockedRoot,
    ) { query, _, locked -> query to locked }
        .mapLatest { (q, locked) ->
            if (locked != null) return@mapLatest Listing(q.path, emptyList())
            try {
                val all = listLocation(q.path).filter { q.hidden || !it.isHidden }
                _listError.value = null
                val items = q.filter.apply(all, System.currentTimeMillis())
                Listing(q.path, sortItems(items, q.sort), noMedia = noMediaIn(q.path, items), unfiltered = all.size)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _listError.value = describeError(e)
                Listing(q.path, emptyList(), error = true)
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Items of the open tab (empty while its listing is loading). */
    val entries: StateFlow<List<FileItem>> = combine(listing, _state.map { it.tab.path }.distinctUntilChanged()) { l, path ->
        l?.takeIf { it.path == path }?.items.orEmpty()
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Listing for a tab that is not the open one (the neighbour pages while swiping between tabs).
     * Locked protected folders are never listed; errors show as an empty page.
     */
    suspend fun peek(path: String): Listing = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (protection.lockedRoot(physicalPath(path)) != null) return@withContext Listing(path, emptyList(), locked = true)
        val s = _state.value
        try {
            val items = listLocation(path).filter { s.showHidden || !it.isHidden }
            val sort = folderSorts.get(path)?.let(SortSpec::unpack) ?: globalSort(s)
            Listing(path, sortItems(items, sort), noMedia = noMediaIn(path, items))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Listing(path, emptyList(), error = true)
        }
    }

    // ---- remembered scroll positions (per folder, persisted) ----

    private val scrollStore = AppStores.scrollPositions(app)

    fun scrollPosition(path: String): ScrollPos? = scrollStore.get(path)

    fun saveScrollPosition(path: String, pos: ScrollPos) {
        viewModelScope.launch(Dispatchers.IO) {
            if (pos.index == 0 && pos.offset == 0) scrollStore.remove(path) else scrollStore.put(path, pos)
        }
    }

    // ---- "open with" defaults ----

    private val associationStore = Associations.decode(prefs.associations)
    private val _associations = MutableStateFlow(associationStore.all())
    val associations: StateFlow<List<Pair<String, OpenChoice>>> = _associations

    fun associationFor(name: String): OpenChoice? = associationStore.get(name)

    fun setAssociation(extension: String, choice: OpenChoice) {
        associationStore.set(extension, choice)
        persistAssociations()
    }

    fun clearAssociation(extension: String) {
        associationStore.clear(extension)
        persistAssociations()
    }

    fun clearAllAssociations() {
        associationStore.clearAll()
        persistAssociations()
    }

    private fun persistAssociations() {
        prefs.associations = associationStore.encode()
        _associations.value = associationStore.all()
    }

    // ---- cache ----

    /**
     * Settings > "캐시 삭제": temporary copies, extracted comic pages, image thumbnails and the comic reading
     * positions. Returns the bytes freed (files only; the reading positions are tiny).
     */
    suspend fun clearCache(): Long = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        val cache = app.cacheDir
        val before = cache.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        runCatching {
            val loader = coil3.SingletonImageLoader.get(app)
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
        }
        cache.listFiles()?.forEach { f -> if (f.name != "image_cache") f.deleteRecursively() }
        AppStores.readingPositions(app).clear()
        AppStores.videoResume(app).clear()
        AppStores.subtitleDelays(app).clear()
        com.choidev.choifile.util.VideoDurations.clear()
        val after = cache.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        (before - after).coerceAtLeast(0)
    }

    init {
        opLog.addListener(opListener)
        // Persist tabs, view modes, sort and the hidden-files toggle whenever they change.
        viewModelScope.launch {
            _state.map { Persisted(it.tabs.map { t -> SavedTab(t.path, t.grid) }, it.current, it.sort, it.ascending, it.foldersFirst, it.showHidden) }
                .distinctUntilChanged()
                .collect {
                    prefs.tabs = it.tabs
                    prefs.currentTab = it.current
                    prefs.sort = it.sort
                    prefs.ascending = it.ascending
                    prefs.foldersFirst = it.foldersFirst
                    prefs.showHidden = it.showHidden
                }
        }
        // "Immediate" relock: leaving a protected folder in every tab locks it again.
        viewModelScope.launch {
            _state.map { s -> s.tabs.map { it.path } }.distinctUntilChanged().collect {
                protection.onNavigated(it)
                lockTick.update { n -> n + 1 }
            }
        }
        // Timed relock: re-evaluate expiry periodically.
        viewModelScope.launch {
            while (true) {
                delay(5_000)
                lockTick.update { it + 1 }
            }
        }
    }

    private fun restoreState(): BrowserState {
        val saved = prefs.tabs.filter { File(it.path).isDirectory }
        val tabs = if (saved.isEmpty()) listOf(Tab(0, rootPath)) else saved.mapIndexed { i, t -> Tab(i, t.path, viewModes.isGrid(t.path), folderSorts.get(t.path)?.let(SortSpec::unpack)) }
        return BrowserState(
            tabs = tabs,
            current = prefs.currentTab.coerceIn(0, tabs.size - 1),
            sort = prefs.sort,
            ascending = prefs.ascending,
            foldersFirst = prefs.foldersFirst,
            showHidden = prefs.showHidden,
        )
    }

    // ---- navigation / tabs ----

    /** [this] tab moved to [path], showing it the way the user last chose for that folder. */
    // The name query belongs to the folder it was typed in; kind, date and size filters follow the tab.
    private fun Tab.at(path: String) = copy(
        path = path, grid = viewModes.isGrid(path), sort = folderSorts.get(path)?.let(SortSpec::unpack), filter = filter.copy(text = ""),
    )

    fun openDir(path: String) = _state.update { s ->
        s.copy(tabs = s.tabs.mapAt(s.current) { it.at(path) }, selection = emptySet())
    }

    /** Where "up" leads from [path]: the parent folder, the archive's folder, or the device root; null at the root. */
    fun parentPath(path: String): String? = when (kindOfPath(path)) {
        LocationKind.REMOTE -> {
            val p = RemoteLocation.pathOf(path)
            if (p == "/") rootPath else RemoteLocation.make(RemoteLocation.connectionOf(path), RemotePaths.parent(p))
        }
        LocationKind.ARCHIVE -> {
            val archive = ArchiveLocation.archiveOf(path)
            val inner = ArchiveLocation.innerOf(path)
            if (inner.isEmpty()) File(archive).parent else ArchiveLocation.make(archive, inner.substringBeforeLast('/', ""))
        }
        LocationKind.LOCAL -> if (path == rootPath) null else File(path).parent
    }

    fun up(): Boolean {
        val parent = parentPath(_state.value.tab.path) ?: return false
        openDir(parent)
        return true
    }

    fun addTab() = _state.update { s ->
        s.copy(tabs = s.tabs + s.tab.copy(id = nextTabId++), current = s.tabs.size, selection = emptySet())
    }

    fun selectTab(index: Int) = _state.update { it.copy(current = index, selection = emptySet()) }

    fun closeTab(index: Int) = _state.update { s ->
        if (s.tabs.size == 1) return@update s
        val tabs = s.tabs.filterIndexed { i, _ -> i != index }
        val current = if (index < s.current || s.current >= tabs.size) (s.current - 1).coerceAtLeast(0) else s.current
        s.copy(tabs = tabs, current = current, selection = emptySet())
    }

    fun toggleGrid() {
        val tab = _state.value.tab
        val grid = !tab.grid
        viewModes.set(tab.path, grid)
        // Other tabs showing the same folder follow, so one folder never looks two ways at once.
        _state.update { s -> s.copy(tabs = s.tabs.map { if (it.path == tab.path) it.copy(grid = grid) else it }) }
    }
    // ---- sorting: one order for every folder, or one of its own for a folder ----

    private fun globalSort(s: BrowserState) = SortSpec(s.sort, s.ascending, s.foldersFirst)

    fun effectiveSort(s: BrowserState = _state.value): SortSpec = s.tab.sort ?: globalSort(s)

    /** Picking the current key again flips the direction, as in iOS Files; dates and sizes start newest/largest first. */
    fun pickSort(key: SortKey) {
        val cur = effectiveSort()
        applySort(if (cur.key == key) cur.copy(ascending = !cur.ascending) else cur.copy(key = key, ascending = key == SortKey.NAME || key == SortKey.TYPE))
    }

    fun toggleFoldersFirst() = applySort(effectiveSort().let { it.copy(foldersFirst = !it.foldersFirst) })

    private fun applySort(spec: SortSpec) {
        val tab = _state.value.tab
        if (tab.sort != null) {
            folderSorts.put(tab.path, spec.pack())
            _state.update { s -> s.copy(tabs = s.tabs.map { if (it.path == tab.path) it.copy(sort = spec) else it }) }
        } else {
            _state.update { it.copy(sort = spec.key, ascending = spec.ascending, foldersFirst = spec.foldersFirst) }
        }
    }

    /** Gives the open folder an order of its own (starting from the current one), or back to the common one. */
    fun setFolderSort(own: Boolean) {
        val tab = _state.value.tab
        val spec = if (own) effectiveSort() else null
        if (spec != null) folderSorts.put(tab.path, spec.pack()) else folderSorts.remove(tab.path)
        _state.update { s -> s.copy(tabs = s.tabs.map { if (it.path == tab.path) it.copy(sort = spec) else it }) }
    }

    // ---- filtering the open folder ----

    fun setFilter(filter: FileFilter) = _state.update { s -> s.copy(tabs = s.tabs.mapAt(s.current) { it.copy(filter = filter) }, selection = emptySet()) }

    fun clearFilter() = setFilter(FileFilter())
    fun toggleHidden() = _state.update { it.copy(showHidden = !it.showHidden) }

    // ---- selection / clipboard ----

    fun toggleSelect(path: String) = _state.update {
        it.copy(selection = if (path in it.selection) it.selection - path else it.selection + path)
    }

    fun selectAll() = _state.update { s -> s.copy(selection = entries.value.map { it.path }.toSet()) }
    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    /** Drag-to-select replaces the whole selection while the finger moves. */
    fun setSelection(paths: Set<String>) = _state.update { if (it.selection == paths) it else it.copy(selection = paths) }
    fun clearClipboard() = _state.update { it.copy(clipboard = null) }

    fun copySelection() = toClipboard(move = false)
    fun cutSelection() = toClipboard(move = true)

    private fun toClipboard(move: Boolean) = _state.update {
        it.copy(clipboard = Clipboard(it.selection.toList(), move), selection = emptySet())
    }

    // ---- file operations ----

    private fun currentLocked() = lockedRoot.value != null

    fun paste() {
        val clip = _state.value.clipboard ?: return
        if (currentLocked()) return
        val dst = _state.value.tab.path
        if (kindOfPath(dst) == LocationKind.ARCHIVE) {
            runOp { say(R.string.msg_archive_readonly) }
            return
        }
        runJob { token ->
            if (kindOfPath(dst) == LocationKind.LOCAL && clip.paths.all { kindOfPath(it) == LocationKind.LOCAL }) {
                val files = clip.paths.map(::File)
                if (clip.move) ops.move(files, File(dst)).forEach { relocateProtection(it.src!!, it.dst!!) } else ops.copy(files, File(dst))
            } else {
                clip.paths.forEach { pasteOne(it, dst, clip.move, token) }
                ops.recordIrreversible(if (clip.move) OpType.MOVE else OpType.COPY, clip.paths.size, clip.paths[0].substringAfterLast('/').substringAfterLast('\\'), NoUndo.REMOTE)
            }
            if (clip.move) _state.update { it.copy(clipboard = null) }
            say(R.string.msg_pasted, clip.paths.size)
        }
    }

    private fun pasteOne(src: String, dst: String, move: Boolean, token: CancelToken) {
        val sk = kindOfPath(src)
        val dk = kindOfPath(dst)
        if (sk == LocationKind.LOCAL && dk == LocationKind.LOCAL) {
            if (move) relocateProtection(src, store.move(File(src), File(dst)).absolutePath) else store.copy(File(src), File(dst))
            return
        }
        if (sk == LocationKind.ARCHIVE) {
            // Extract the chosen entry to a scratch folder, then hand the result on like any local item.
            val temp = File(viewCacheDir().parentFile, "extract/" + System.nanoTime()).also { it.mkdirs() }
            try {
                val archive = File(ArchiveLocation.archiveOf(src))
                val inner = ArchiveLocation.innerOf(src)
                val format = ArchiveFormat.detect(archive.name) ?: throw IOException("not an archive")
                ArchiveExtractor.extract(archive, format, temp, setOf(inner), cancelled = { token.cancelled }, onProgress = throttledBytes())
                val extracted = File(temp, inner)
                if (dk == LocationKind.LOCAL) store.copy(extracted, File(dst))
                else {
                    val (dstStore, dstPath) = storeAndPath(dst)
                    TransferEngine.transfer(LocalRemoteStore(), extracted.absolutePath.replace('\\', '/'), dstStore, dstPath, false, token, ::report)
                }
            } finally {
                temp.deleteRecursively()
            }
            return
        }
        val (srcStore, srcPath) = storeAndPath(src)
        val (dstStore, dstPath) = storeAndPath(dst)
        TransferEngine.transfer(srcStore, srcPath, dstStore, dstPath, move, token, ::report)
    }

    private fun storeAndPath(location: String): Pair<RemoteStore, String> =
        if (RemoteLocation.isRemote(location)) sessions.get(RemoteLocation.connectionOf(location)) to RemoteLocation.pathOf(location)
        else LocalRemoteStore() to location.replace('\\', '/')

    private fun report(p: TransferProgress) { _transfer.value = p }

    private fun throttledBytes(): (String, Long) -> Unit {
        var last = 0L
        return { name, bytes ->
            val now = System.nanoTime()
            if (now - last > 100_000_000L) { last = now; _transfer.value = TransferProgress(name, bytes, 0, 0, 0) }
        }
    }

    fun cancelTransfer() { cancelToken?.cancel() }

    fun delete(mode: DeleteMode) {
        val paths = _state.value.selection.toList()
        clearSelection()
        if (paths.isNotEmpty() && kindOfPath(paths[0]) != LocationKind.LOCAL) {
            runOp {
                if (kindOfPath(paths[0]) == LocationKind.ARCHIVE) { say(R.string.msg_archive_readonly); return@runOp }
                paths.forEach { p -> sessions.use(RemoteLocation.connectionOf(p)) { it.delete(RemoteLocation.pathOf(p)) } }
                ops.recordIrreversible(OpType.DELETE, paths.size, RemoteLocation.pathOf(paths[0]).substringAfterLast('/'), NoUndo.REMOTE)
                say(R.string.msg_deleted, paths.size)
            }
            return
        }
        runOp {
            when (mode) {
                DeleteMode.PERMANENT -> {
                    ops.deletePermanently(paths.map(::File), secure = false)
                    say(R.string.msg_deleted, paths.size)
                }
                DeleteMode.SECURE -> {
                    ops.deletePermanently(paths.map(::File), secure = true)
                    say(R.string.msg_secure_deleted, paths.size)
                }
                DeleteMode.TRASH -> {
                    val record = ops.trash(paths.map(::File))
                    events.send(UiEvent(str(R.string.msg_trashed, paths.size), undoRecord = record))
                }
            }
        }
    }

    // ---- operation log ----

    /** Reverts one logged operation and tells the user exactly what could and could not be restored. */
    fun undoRecord(id: String) = runOp {
        val report = ops.undo(id)
        if (report == null) { say(R.string.undo_gone); return@runOp }
        report.moved.forEach { (from, to) -> relocateProtection(from, to) }
        val skipped = report.skipped
        if (skipped.isEmpty()) {
            if (report.restored == 0) say(R.string.undo_nothing) else say(R.string.undo_done, report.restored)
        } else {
            val why = skipped.map { it.reason }.distinct().joinToString(", ") { str(undoReasonText(it)) }
            say(R.string.undo_partial, report.restored, skipped.size, why)
        }
    }

    fun clearOperationLog() = opLog.clear()

    /** Sends the ticked duplicate copies to the trash; the snackbar offers an undo. */
    fun deleteDuplicates() = runOp {
        val n = duplicates.pending().size
        val id = duplicates.deleteSelected()
        if (id == null) say(R.string.dup_nothing) else events.send(UiEvent(str(R.string.dup_trashed, n), undoRecord = id))
    }

    fun rename(path: String, newName: String) {
        clearSelection()
        if (kindOfPath(path) == LocationKind.REMOTE) {
            runOp {
                val id = RemoteLocation.connectionOf(path)
                val from = RemoteLocation.pathOf(path)
                sessions.use(id) { it.rename(from, RemotePaths.join(RemotePaths.parent(from), remoteName(newName))) }
                ops.recordIrreversible(OpType.RENAME, 1, newName, NoUndo.REMOTE)
                say(R.string.msg_renamed)
            }
            return
        }
        if (kindOfPath(path) == LocationKind.ARCHIVE) { runOp { say(R.string.msg_archive_readonly) }; return }
        runOp {
            relocateProtection(path, ops.rename(File(path), newName).absolutePath)
            say(R.string.msg_renamed)
        }
    }

    fun createFolder(name: String) {
        val tab = _state.value.tab.path
        when (kindOfPath(tab)) {
            LocationKind.LOCAL -> create(name) { store.createFolder(File(tab), it) }
            LocationKind.REMOTE -> remoteCreate(tab, name, folder = true)
            LocationKind.ARCHIVE -> runOp { say(R.string.msg_archive_readonly) }
        }
    }

    fun createTextFile(name: String) {
        val tab = _state.value.tab.path
        when (kindOfPath(tab)) {
            LocationKind.LOCAL -> create(name) { store.createTextFile(File(tab), it) }
            LocationKind.REMOTE -> remoteCreate(tab, name, folder = false)
            LocationKind.ARCHIVE -> runOp { say(R.string.msg_archive_readonly) }
        }
    }

    fun createDummyFile(name: String, megabytes: Int) {
        if (kindOfPath(_state.value.tab.path) != LocationKind.LOCAL) return
        create(name) { store.createDummyFile(File(_state.value.tab.path), it, megabytes) }
    }

    private fun remoteCreate(tab: String, name: String, folder: Boolean) = runOp {
        val id = RemoteLocation.connectionOf(tab)
        val dir = RemoteLocation.pathOf(tab)
        val clean = remoteName(name)
        sessions.use(id) { s ->
            val unique = RemotePaths.uniqueName(clean, s.list(dir).map { it.name }.toSet())
            val target = RemotePaths.join(dir, unique)
            if (folder) s.mkdir(target) else s.create(target, 0).close()
        }
        say(R.string.msg_created, clean)
    }

    private fun remoteName(name: String): String {
        val n = name.trim()
        require(n.isNotEmpty() && '/' !in n && n != "." && n != "..") { "invalid name" }
        return n
    }

    private fun create(name: String, block: (String) -> File) {
        if (currentLocked()) return
        runOp { say(R.string.msg_created, block(name).name) }
    }

    fun notifyNoShareDirs() = runOp { say(R.string.msg_no_share_dirs) }

    // ---- trash ----

    fun loadTrash() = viewModelScope.launch(Dispatchers.IO) { _trash.value = store.listTrash() }

    fun restoreFromTrash(entry: TrashEntry) = runOp { store.restore(entry); say(R.string.msg_restored); _trash.value = store.listTrash() }

    fun emptyTrash() = runOp { store.emptyTrash(); say(R.string.msg_trash_emptied); _trash.value = emptyList() }

    // ---- favorites (drawer) ----

    private val favoriteStore = AppStores.favorites(app)
    private val _favorites = MutableStateFlow(favoriteStore.all())
    val favorites: StateFlow<List<Favorite>> = _favorites
    private val _missingFavorites = MutableStateFlow<Set<String>>(emptySet())
    /** Favorites whose folder no longer exists; refreshed whenever the drawer opens. */
    val missingFavorites: StateFlow<Set<String>> = _missingFavorites

    fun canFavorite(path: String) = kindOfPath(path) == LocationKind.LOCAL && FavoritesStore.isAllowed(path)
    fun isFavorite(path: String) = favoriteStore.contains(path)

    fun toggleFavorite(path: String) = runOp {
        if (favoriteStore.contains(path)) {
            favoriteStore.remove(path)
            say(R.string.fav_removed)
        } else {
            val label = if (path.trimEnd('/') == rootPath.trimEnd('/')) str(R.string.drawer_internal) else null
            when (favoriteStore.add(path, label)) {
                FavoriteAdd.ADDED -> say(R.string.fav_added)
                FavoriteAdd.DUPLICATE -> say(R.string.fav_duplicate)
                FavoriteAdd.FORBIDDEN -> say(R.string.fav_forbidden)
            }
        }
        _favorites.value = favoriteStore.all()
    }

    fun removeFavorite(path: String) { favoriteStore.remove(path); afterFavoritesChanged() }
    fun renameFavorite(path: String, label: String) { favoriteStore.rename(path, label); afterFavoritesChanged() }
    fun moveFavorite(from: Int, to: Int) { favoriteStore.move(from, to); afterFavoritesChanged() }

    fun refreshFavorites() = viewModelScope.launch(Dispatchers.IO) { _missingFavorites.value = favoriteStore.missing() }

    private fun afterFavoritesChanged() {
        _favorites.value = favoriteStore.all()
        refreshFavorites()
    }

    /** Standard folders shown under "저장소" in the drawer (label resource to path). */
    val storageShortcuts: List<Pair<Int, String>> = listOf(
        R.string.drawer_internal to rootPath,
        R.string.drawer_download to File(rootPath, Environment.DIRECTORY_DOWNLOADS).path,
        R.string.drawer_dcim to File(rootPath, Environment.DIRECTORY_DCIM).path,
        R.string.drawer_pictures to File(rootPath, Environment.DIRECTORY_PICTURES).path,
        R.string.drawer_documents to File(rootPath, Environment.DIRECTORY_DOCUMENTS).path,
    )

    // ---- .nomedia ("갤러리에서 숨기기") ----

    private fun noMediaIn(path: String, items: List<FileItem>): Set<String> =
        if (kindOfPath(path) != LocationKind.LOCAL) emptySet() else NoMedia.marked(items.filter { it.isDirectory }.map { File(it.path) })

    fun hasNoMedia(path: String) = kindOfPath(path) == LocationKind.LOCAL && NoMedia.has(File(path))

    /** Toggles .nomedia on [paths] (folders): on unless all of them already have it. Then asks the media scanner to look again. */
    fun toggleNoMedia(paths: List<String>, on: Boolean? = null) {
        val dirs = paths.filter { kindOfPath(it) == LocationKind.LOCAL }.map(::File).filter { it.isDirectory }
        if (dirs.isEmpty()) return
        if (dirs.any { protection.lockedRoot(it.path) != null }) return
        val target = on ?: NoMedia.targetState(dirs)
        clearSelection()
        runOp {
            val changed = NoMedia.setAll(dirs, target)
            rescanMedia(changed)
            say(if (target) R.string.nomedia_on_done else R.string.nomedia_off_done, dirs.size)
        }
    }

    /** Hands the folders (and the files directly inside) to the media scanner so galleries pick up the change. */
    private fun rescanMedia(dirs: List<File>) {
        if (dirs.isEmpty()) return
        val paths = dirs.flatMap { d -> listOf(d.path, File(d, NoMedia.NAME).path) + d.listFiles().orEmpty().filter { it.isFile }.take(500).map { it.path } }
        runCatching { android.media.MediaScannerConnection.scanFile(getApplication(), paths.distinct().toTypedArray(), null, null) }
    }

    // ---- protected folders ----

    fun isProtected(path: String) = protection.isProtected(path)

    /** Marks [path] as protected. Removing protection must be authenticated by the caller first. */
    fun setProtected(path: String, on: Boolean) {
        if (on) protection.protect(path) else protection.unprotect(path)
        persistProtection()
    }

    fun unlock(root: String) {
        protection.unlock(root)
        lockTick.update { it + 1 }
    }

    /**
     * Called when the app comes back to the foreground. [graceExpired] = it was away at least the grace period
     * (settings). The "immediate" relock then locks every protected folder again; a short trip to another app
     * (share sheet, "open with") within the grace period keeps them open. Timed unlocks expire on their own clock.
     */
    fun onForeground(graceExpired: Boolean) {
        if (graceExpired && protection.timeoutMs == 0L) protection.lockAll()
        lockTick.update { it + 1 }
    }

    fun setRelockSeconds(seconds: Int) {
        prefs.relockSeconds = seconds
        protection.timeoutMs = seconds * 1000L
        protection.onNavigated(_state.value.tabs.map { it.path })
        lockTick.update { it + 1 }
    }

    fun isLocked(path: String) = protection.lockedRoot(physicalPath(path)) != null

    private fun relocateProtection(old: String, new: String) {
        if (old == new) return
        protection.relocate(old, new)
        persistProtection()
        favoriteStore.relocate(old, new)
        _favorites.value = favoriteStore.all()
    }

    private fun persistProtection() {
        prefs.protectedFolders = protection.paths
        _protectedPaths.value = protection.paths
        lockTick.update { it + 1 }
    }

    // ---- hidden items (obscurity, not encryption) ----

    fun hide(paths: List<String>) {
        clearSelection()
        runOp {
            paths.forEach { store.hide(File(it)) }
            say(R.string.msg_hidden, paths.size)
        }
    }

    fun loadHidden() = viewModelScope.launch(Dispatchers.IO) { _hidden.value = store.listHidden() }

    fun unhide(entry: TrashEntry) = runOp {
        store.unhide(entry)
        say(R.string.msg_unhidden)
        _hidden.value = store.listHidden()
    }

    // ---- encryption ----

    fun encrypt(paths: List<String>, password: CharArray, original: OriginalAction) {
        clearSelection()
        runOp {
            try {
                val files = paths.map(::File).filter { it.isFile && !CfEnc.isEncryptedName(it.name) }
                files.forEach { encryptOne(it, password, original) }
                say(R.string.msg_encrypted, files.size)
            } finally {
                password.fill('\u0000')
            }
        }
    }

    fun decrypt(paths: List<String>, password: CharArray) {
        clearSelection()
        runOp {
            try {
                val files = paths.map(::File).filter { it.isFile && CfEnc.isEncryptedName(it.name) }
                files.forEach { f ->
                    CfEnc.decryptFile(f, store.uniqueChild(f.parentFile!!, CfEnc.plainName(f.name)), password)
                }
                say(R.string.msg_decrypted, files.size)
            } finally {
                password.fill('\u0000')
            }
        }
    }

    /** Decrypts into the app cache and asks the UI to open it; the copy is deleted when the viewer closes. */
    fun decryptForView(path: String, password: CharArray) {
        clearSelection()
        runOp {
            try {
                val src = File(path)
                val dir = File(viewCacheDir(), System.nanoTime().toString()).also { it.mkdirs() }
                val dst = File(dir, CfEnc.plainName(src.name))
                try {
                    CfEnc.decryptFile(src, dst, password)
                } catch (e: Exception) {
                    dir.deleteRecursively()
                    throw e
                }
                events.send(UiEvent("", viewTemp = dst.absolutePath))
            } finally {
                password.fill('\u0000')
            }
        }
    }

    /** Encrypts (keeping the original) and opens the share sheet for the .cfenc. The password is never sent. */
    fun encryptAndShare(path: String, password: CharArray) {
        clearSelection()
        runOp {
            try {
                val out = encryptOne(File(path), password, OriginalAction.KEEP)
                events.send(UiEvent("", shareFile = out.absolutePath))
            } finally {
                password.fill('\u0000')
            }
        }
    }

    /** Hide + encrypt: encrypt, move only the .cfenc into the hidden vault, then dispose of the plaintext. */
    fun hideEncrypted(paths: List<String>, password: CharArray, original: OriginalAction) {
        clearSelection()
        runOp {
            try {
                val files = paths.map(::File).filter { it.isFile && !CfEnc.isEncryptedName(it.name) }
                files.forEach { f ->
                    val enc = encryptOne(f, password, OriginalAction.KEEP)
                    store.hide(enc)
                    disposeOriginal(f, original)
                }
                say(R.string.msg_hidden_encrypted, files.size)
            } finally {
                password.fill('\u0000')
            }
        }
    }

    private fun encryptOne(file: File, password: CharArray, original: OriginalAction): File {
        val dst = store.uniqueChild(file.parentFile!!, file.name + "." + CfEnc.EXTENSION)
        CfEnc.encryptFile(file, dst, password)
        disposeOriginal(file, original)
        return dst
    }

    private fun disposeOriginal(file: File, action: OriginalAction) {
        when (action) {
            OriginalAction.KEEP -> Unit
            OriginalAction.TRASH -> store.moveToTrash(file)
            OriginalAction.PERMANENT -> store.deletePermanently(file)
            OriginalAction.SECURE -> store.secureDelete(file)
        }
    }

    fun viewCacheDir() = File(getApplication<Application>().cacheDir, "view")

    // ---- virtual locations: archives and network connections ----

    private fun physicalPath(path: String) = when (kindOfPath(path)) {
        LocationKind.ARCHIVE -> ArchiveLocation.archiveOf(path)
        LocationKind.REMOTE -> "" // network folders are never inside a protected local folder
        LocationKind.LOCAL -> path
    }

    private fun listLocation(path: String): List<FileItem> = when (kindOfPath(path)) {
        LocationKind.LOCAL -> store.list(File(path))
        LocationKind.REMOTE -> sessions.use(RemoteLocation.connectionOf(path)) { it.list(RemoteLocation.pathOf(path)) }
        LocationKind.ARCHIVE -> {
            val file = File(ArchiveLocation.archiveOf(path))
            val format = ArchiveFormat.detect(file.name) ?: throw IOException("not an archive")
            val key = "${file.path}|${file.lastModified()}|${file.length()}"
            val entries = synchronized(archiveCache) { archiveCache[key] } ?: ArchiveReader.list(file, format).also {
                synchronized(archiveCache) { archiveCache.clear(); archiveCache[key] = it }
            }
            childrenOf(entries, ArchiveLocation.innerOf(path)).map {
                FileItem(ArchiveLocation.make(file.path, it.path), it.name, it.isDirectory, it.size, it.modified, it.name.startsWith("."))
            }
        }
    }

    private fun describeError(e: Throwable): String = when (e) {
        is ArchiveUnsupportedException -> str(R.string.msg_archive_encrypted)
        is ArchiveLimitException -> str(R.string.msg_archive_limit, e.message ?: "")
        is ArchiveSecurityException -> str(R.string.msg_archive_unsafe)
        else -> RemoteFactory.describe(e)
    }

    /** Path shown in the top bar. */
    fun displayPath(path: String): String = when (kindOfPath(path)) {
        LocationKind.LOCAL -> path.removePrefix(rootPath).ifEmpty { "/" }
        LocationKind.REMOTE -> (_connections.value.firstOrNull { it.id == RemoteLocation.connectionOf(path) }?.name ?: "?") + ":" + RemoteLocation.pathOf(path)
        LocationKind.ARCHIVE -> File(ArchiveLocation.archiveOf(path)).name + "!/" + ArchiveLocation.innerOf(path)
    }

    fun tabLabel(path: String): String = when (kindOfPath(path)) {
        // The storage root's own name is just "0" (/storage/emulated/0); call it what the drawer calls it.
        LocationKind.LOCAL -> if (path == rootPath) getApplication<Application>().getString(R.string.drawer_internal) else File(path).name.ifEmpty { "/" }
        LocationKind.REMOTE -> displayPath(path).substringBefore(':')
        LocationKind.ARCHIVE -> File(ArchiveLocation.archiveOf(path)).name
    }

    fun currentKind(): LocationKind = kindOfPath(_state.value.tab.path)

    /** Opens a file that is not on local storage (inside an archive or on a server) in the viewer via a temporary copy. */
    fun openVirtualFile(item: FileItem) {
        if (kindOf(File(item.name)) == FileKind.OTHER) {
            runOp { say(R.string.msg_download_to_open) }
            return
        }
        if (item.size > MAX_VIEW_BYTES) {
            runOp { say(R.string.msg_too_large) }
            return
        }
        runJob { _ ->
            val dir = File(viewCacheDir(), System.nanoTime().toString()).also { it.mkdirs() }
            val out = File(dir, item.name)
            try {
                if (RemoteLocation.isRemote(item.path)) {
                    sessions.use(RemoteLocation.connectionOf(item.path)) { s ->
                        s.open(RemoteLocation.pathOf(item.path)).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                    }
                } else {
                    val archive = File(ArchiveLocation.archiveOf(item.path))
                    ArchiveReader.extractOne(archive, ArchiveFormat.detect(archive.name) ?: throw IOException("not an archive"), ArchiveLocation.innerOf(item.path), out)
                }
            } catch (e: Exception) {
                dir.deleteRecursively()
                throw e
            }
            events.send(UiEvent("", viewTemp = out.absolutePath))
        }
    }

    // ---- archives ----

    /**
     * Extracts local archive files. [mode] HERE puts the contents next to each archive, FOLDER makes a new folder
     * named after it, ELSEWHERE puts the contents into [target]. Nothing existing is ever overwritten.
     */
    fun extractArchives(paths: List<String>, mode: ExtractMode = ExtractMode.FOLDER, target: String? = null) {
        clearSelection()
        if (!extractTargetOk(mode, target)) return
        runJob { token ->
            var n = 0
            var last = ""
            for (p in paths) {
                val f = File(p)
                if (ArchiveFormat.detect(f.name) == null) continue
                last = extractOne(f, null, mode, target, token)
                n++
            }
            if (n == 1) say(R.string.msg_extracted_to, last) else say(R.string.msg_extracted, n)
        }
    }

    /** In an archive tab: extract [selected] entries (or everything when null); HERE/FOLDER are relative to the archive file. */
    fun extractFromCurrentArchive(selected: List<String>?, mode: ExtractMode = ExtractMode.FOLDER, target: String? = null) {
        val tab = _state.value.tab.path
        if (kindOfPath(tab) != LocationKind.ARCHIVE) return
        clearSelection()
        if (!extractTargetOk(mode, target)) return
        runJob { token ->
            val archive = File(ArchiveLocation.archiveOf(tab))
            val select = selected?.map { ArchiveLocation.innerOf(it) }?.filter { it.isNotEmpty() }?.toSet()
            say(R.string.msg_extracted_to, extractOne(archive, select, mode, target, token))
        }
    }

    private fun extractTargetOk(mode: ExtractMode, target: String?): Boolean {
        if (mode != ExtractMode.ELSEWHERE) return true
        val ok = target != null && File(target).isDirectory && !PathGuard(protection.paths, isLocked = { protection.lockedRoot(it) != null }).isExcluded(target)
        if (!ok) runOp { say(R.string.msg_extract_target_bad) }
        return ok
    }

    /** Returns a short description of where the result went, for the snackbar. */
    private fun extractOne(archive: File, select: Set<String>?, mode: ExtractMode, target: String?, token: CancelToken): String {
        val format = ArchiveFormat.detect(archive.name) ?: throw IOException("not an archive")
        val parent = if (mode == ExtractMode.ELSEWHERE) File(target!!) else archive.parentFile!!
        if (mode == ExtractMode.FOLDER) {
            val dest = store.uniqueChild(parent, archiveBaseName(archive.name))
            try {
                ArchiveExtractor.extract(archive, format, dest, select, cancelled = { token.cancelled }, onProgress = throttledBytes())
            } finally {
                ops.recordExtract(archive, dest) // also after a cancel or failure, so the partial result can be undone
            }
            return dest.name
        }
        val moved = ArchiveExtractor.extractMerged(archive, format, parent, store::uniqueChild, select, cancelled = { token.cancelled }, onProgress = throttledBytes())
        ops.recordExtractMerged(archive, moved)
        return displayPath(parent.absolutePath)
    }

    fun createArchive(paths: List<String>, format: ArchiveFormat, name: String) {
        clearSelection()
        val dir = File(_state.value.tab.path)
        runJob { token ->
            val base = remoteName(name).let { if (ArchiveFormat.detect(it) == format) it else it + "." + format.label }
            val out = store.uniqueChild(dir, base)
            ArchiveWriter.create(format, out, paths.map(::File), cancelled = { token.cancelled }, onProgress = throttledBytes())
            ops.recordArchive(out)
            say(R.string.msg_archive_created, out.name)
        }
    }

    // ---- connections ----

    private fun askHostKey(host: String, port: Int, fingerprint: String, previous: String?): HostKeyDecision {
        val future = CompletableFuture<HostKeyDecision>()
        _hostKeyRequest.value = HostKeyRequest(host, port, fingerprint, previous) { future.complete(it) }
        return try {
            future.get(120, TimeUnit.SECONDS)
        } catch (e: Exception) {
            HostKeyDecision.REJECT
        } finally {
            _hostKeyRequest.value = null
        }
    }

    fun loadConnections() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val v = connectionRepo.load()
                _connections.value = v.connections
                _tailscaleKeySet.value = v.tailscaleApiKey.isNotEmpty()
                _vaultError.value = null
            } catch (e: Exception) {
                _vaultError.value = RemoteFactory.describe(e)
            }
        }
    }

    fun saveConnection(c: ConnectionConfig) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                connectionRepo.upsert(c)
                sessions.invalidate(c.id)
                _connections.value = connectionRepo.load().connections
            } catch (e: Exception) {
                say(R.string.msg_error, RemoteFactory.describe(e))
            }
        }
    }

    fun deleteConnection(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                connectionRepo.delete(id)
                sessions.invalidate(id)
                _connections.value = connectionRepo.load().connections
                // Leave tabs that pointed at the removed connection.
                _state.update { s -> s.copy(tabs = s.tabs.map { t -> if (RemoteLocation.isRemote(t.path) && RemoteLocation.connectionOf(t.path) == id) t.at(rootPath) else t }) }
            } catch (e: Exception) {
                say(R.string.msg_error, RemoteFactory.describe(e))
            }
        }
    }

    fun testConnection(c: ConnectionConfig) {
        _connectionTest.value = ConnectionTest.Running
        viewModelScope.launch(Dispatchers.IO) {
            val err = RemoteFactory.test(c, hostPolicy)
            _connectionTest.value = if (err == null) ConnectionTest.Ok else ConnectionTest.Failed(err)
        }
    }

    fun clearConnectionTest() { _connectionTest.value = null }

    fun openConnection(id: String) {
        val c = _connections.value.firstOrNull { it.id == id } ?: return
        openDir(RemoteLocation.make(id, RemoteFactory.startPath(c)))
    }

    fun checkReachable(host: String, port: Int) {
        _reach.value = null
        viewModelScope.launch(Dispatchers.IO) { _reach.value = Tailscale.checkReachable(host, port) }
    }

    fun clearReach() { _reach.value = null }

    fun setTailscaleApiKey(key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                connectionRepo.setTailscaleApiKey(key.trim())
                _tailscaleKeySet.value = key.isNotBlank()
                if (key.isBlank()) _tailscaleDevices.value = null
            } catch (e: Exception) {
                say(R.string.msg_error, RemoteFactory.describe(e))
            }
        }
    }

    fun loadTailscaleDevices() {
        _tailscaleStatus.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val key = connectionRepo.load().tailscaleApiKey
                if (key.isEmpty()) { _tailscaleStatus.value = str(R.string.ts_no_key); return@launch }
                _tailscaleDevices.value = Tailscale.listDevices(key)
            } catch (e: Exception) {
                _tailscaleStatus.value = RemoteFactory.describe(e)
            }
        }
    }

    // ---- automation rules ----

    fun saveRule(rule: Rule) {
        ruleStore.upsert(RuleEngine.applyEdit(ruleStore.get(rule.id), rule))
        afterRulesChanged()
    }

    fun deleteRule(id: String) {
        ruleStore.delete(id)
        afterRulesChanged()
    }

    /** Enabling a rule that was never reviewed is refused here; the screen shows the dry run and calls [confirmRule]. */
    fun setRuleEnabled(id: String, enabled: Boolean) {
        val r = ruleStore.get(id) ?: return
        if (enabled && !r.reviewed) return
        ruleStore.upsert(r.copy(enabled = enabled))
        afterRulesChanged()
    }

    /** The user saw the dry-run list for this definition and accepted it: marks it reviewed and enables it. */
    fun confirmRule(id: String) {
        val r = ruleStore.get(id) ?: return
        ruleStore.upsert(r.copy(reviewed = true, enabled = true))
        afterRulesChanged()
    }

    private fun afterRulesChanged() {
        _rules.value = ruleStore.all()
        RuleWorker.sync(getApplication(), ruleStore.all())
    }

    data class RulePreview(val problem: RuleProblem?, val items: List<PlannedItem>)

    /** Dry run for the screen. Never changes any file. */
    suspend fun previewRule(rule: Rule): RulePreview = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val g = automationGuard()
        val problem = RuleEngine.validate(rule, g)
        RulePreview(problem, if (problem == null) RuleEngine.plan(rule, g) else emptyList())
    }

    /** Manual run (the screen has just shown the dry run). Logged like any other operation and undoable from the log. */
    fun runRule(id: String) {
        val rule = ruleStore.get(id) ?: return
        runJob { token ->
            val result = RuleEngine.run(rule, ops, automationGuard(), cancelled = { token.cancelled })
            ruleStore.get(id)?.let { ruleStore.upsert(it.copy(lastRun = System.currentTimeMillis(), lastResult = "ok:${result.acted}")) }
            _rules.value = ruleStore.all()
            val record = if (result.acted > 0) opLog.all().firstOrNull()?.id else null
            events.send(UiEvent(str(R.string.rule_ran, rule.name, result.acted), undoRecord = record))
        }
    }

    override fun onCleared() {
        opLog.removeListener(opListener)
        sessions.closeAll()
        super.onCleared()
    }

    /** One long-running job at a time (transfer, extraction, compression) with progress and cancel. */
    private fun runJob(block: suspend (CancelToken) -> Unit) {
        if (_transfer.value != null) {
            runOp { say(R.string.msg_busy) }
            return
        }
        _transfer.value = TransferProgress("", 0, 0, 0, 0)
        viewModelScope.launch(Dispatchers.IO) {
            val token = CancelToken().also { cancelToken = it }
            try {
                block(token)
            } catch (e: TransferCancelledException) {
                say(R.string.msg_cancelled)
            } catch (e: ArchiveCancelledException) {
                say(R.string.msg_cancelled)
            } catch (e: Exception) {
                if (e is IOException) sessions.closeAll() // stale connections are re-opened on the next use
                say(R.string.msg_error, describeError(e))
            } finally {
                _transfer.value = null
                cancelToken = null
                refresh.update { it + 1 }
            }
        }
    }

    // ---- helpers ----

    private fun runOp(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: CfEncAuthException) {
                say(R.string.msg_wrong_password)
            } catch (e: CfEncFormatException) {
                say(R.string.msg_not_cfenc)
            } catch (e: Exception) {
                say(R.string.msg_error, e.message ?: e.javaClass.simpleName)
            }
            refresh.update { it + 1 }
        }
    }

    private fun str(@StringRes id: Int, vararg args: Any) = getApplication<Application>().getString(id, *args)
    private suspend fun say(@StringRes id: Int, vararg args: Any) = events.send(UiEvent(str(id, *args)))
}

@StringRes
private fun undoReasonText(r: SkipReason): Int = when (r) {
    SkipReason.MISSING -> R.string.undo_why_missing
    SkipReason.TARGET_EXISTS -> R.string.undo_why_exists
    SkipReason.MODIFIED -> R.string.undo_why_modified
    SkipReason.NOT_EMPTY -> R.string.undo_why_not_empty
    SkipReason.ERROR -> R.string.undo_why_error
}

private inline fun <T> List<T>.mapAt(index: Int, transform: (T) -> T) =
    mapIndexed { i, t -> if (i == index) transform(t) else t }
