package com.choidev.choifile.browser

import com.choidev.choifile.analysis.ScanResult
import com.choidev.choifile.analysis.StorageAnalyzer
import com.choidev.choifile.dupes.DupGroup
import com.choidev.choifile.dupes.DupStage
import com.choidev.choifile.dupes.DuplicateFinder
import com.choidev.choifile.oplog.FileOps
import com.choidev.choifile.security.PathGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class AnalysisState(
    val scanning: Boolean = false,
    val filesSeen: Int = 0,
    val result: ScanResult? = null,
    val error: String? = null,
)

/** Background scan of internal storage. The guard is asked live, so folders locked at scan time stay out of the totals. */
class AnalysisController(private val scope: CoroutineScope, private val root: File, private val guard: () -> PathGuard) {
    private val _state = MutableStateFlow(AnalysisState())
    val state: StateFlow<AnalysisState> = _state
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        _state.value = AnalysisState(scanning = true)
        job = scope.launch(Dispatchers.IO) {
            try {
                val r = StorageAnalyzer.scan(root, guard(), cancelled = { !isActive }, onProgress = { n -> _state.update { it.copy(filesSeen = n) } })
                _state.value = AnalysisState(result = r, filesSeen = r.totalFiles)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.value = AnalysisState(error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancel() { job?.cancel(); _state.update { it.copy(scanning = false) } }
}

data class DuplicateState(
    val scanning: Boolean = false,
    val stage: DupStage = DupStage.LISTING,
    val done: Int = 0,
    val total: Int = 0,
    val groups: List<DupGroup>? = null,
    /** Paths marked for deletion. Never covers every copy of a group. */
    val selected: Set<String> = emptySet(),
    val error: String? = null,
)

class DuplicatesController(private val scope: CoroutineScope, private val root: File, private val guard: () -> PathGuard, private val ops: FileOps) {
    private val _state = MutableStateFlow(DuplicateState())
    val state: StateFlow<DuplicateState> = _state
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        _state.value = DuplicateState(scanning = true)
        job = scope.launch(Dispatchers.IO) {
            try {
                val groups = DuplicateFinder.find(root, guard(), cancelled = { !isActive }) { stage, done, total ->
                    _state.update { it.copy(stage = stage, done = done, total = total) }
                }
                _state.value = DuplicateState(groups = groups, selected = groups.flatMap { DuplicateFinder.defaultSelection(it) }.toSet())
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.value = DuplicateState(error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancel() { job?.cancel(); _state.value = DuplicateState() }

    /** Ticking the last unticked copy of a group is ignored: at least one copy always stays. */
    fun toggle(path: String) = _state.update { s ->
        val group = s.groups?.firstOrNull { g -> g.files.any { it.path == path } } ?: return@update s
        if (path in s.selected) s.copy(selected = s.selected - path)
        else {
            val remaining = group.files.count { it.path !in s.selected && it.path != path }
            if (remaining == 0) s else s.copy(selected = s.selected + path)
        }
    }

    fun resetGroup(group: DupGroup) = _state.update { s ->
        s.copy(selected = (s.selected - group.files.map { it.path }.toSet()) + DuplicateFinder.defaultSelection(group))
    }

    /** Files that [deleteSelected] would trash right now (a copy always stays per group). */
    fun pending(): List<String> {
        val s = _state.value
        return s.groups.orEmpty().flatMap { DuplicateFinder.safeSelection(it, s.selected) }
    }

    /**
     * Moves the selected copies to the trash (one logged, undoable operation). A group is skipped when the copy that
     * would remain is no longer on disk. Returns the log record id, or null when nothing was trashed.
     */
    fun deleteSelected(): String? {
        val s = _state.value
        val groups = s.groups ?: return null
        val files = ArrayList<File>()
        for (g in groups) {
            val doomed = DuplicateFinder.safeSelection(g, s.selected)
            val survivors = g.files.filter { it.path !in doomed }
            if (survivors.none { File(it.path).isFile }) continue
            doomed.map(::File).filter { it.isFile }.forEach { files += it }
        }
        if (files.isEmpty()) return null
        val id = ops.trash(files)
        val gone = files.map { it.absolutePath }.toSet()
        _state.update { cur ->
            val left = cur.groups.orEmpty().map { g -> DupGroup(g.size, g.hash, g.files.filter { it.path !in gone }) }.filter { it.files.size > 1 }
            cur.copy(groups = left, selected = cur.selected - gone)
        }
        return id
    }
}
