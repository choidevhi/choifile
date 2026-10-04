package com.choidev.choifile.search

import android.content.Context
import android.os.Environment
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.choidev.choifile.security.AppPrefs
import com.choidev.choifile.security.ProtectedFolders
import com.choidev.choifile.storage.FileItem
import com.choidev.choifile.util.hasAllFilesAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

data class SearchHit(val item: FileItem, val snippet: String? = null)

data class SearchUiState(
    val query: SearchQuery = SearchQuery(),
    val results: List<SearchHit> = emptyList(),
    val searching: Boolean = false,
    val indexing: Boolean = false,
    val indexedCount: Int = 0,
    val indexedAt: Long = 0,
    val error: String? = null,
)

/** Owns the index, runs searches off the main thread and keeps saved searches. */
class SearchController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val rootPath: String,
    private val protection: ProtectedFolders,
    private val prefs: AppPrefs,
) {
    private val index by lazy { SearchIndex(AndroidSqlDb.get(context)) }

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state

    private val _saved = MutableStateFlow(SavedSearchCodec.decode(prefs.savedSearches))
    val saved: StateFlow<List<SavedSearch>> = _saved

    private var searchJob: Job? = null
    private var indexJob: Job? = null

    private fun lockedRoots(): Set<String> = protection.paths.filter { protection.lockedRoot(it) != null }.toSet()

    /** Called when the search screen opens: shows index status and refreshes when it is missing or old. */
    fun onOpen() {
        scope.launch(Dispatchers.IO) {
            val at = index.lastIndexedAt()
            _state.update { it.copy(indexedCount = index.count(), indexedAt = at) }
            if (at == 0L || System.currentTimeMillis() - at > TimeUnit.HOURS.toMillis(6)) reindex()
        }
    }

    fun setQuery(shown: SearchQuery) {
        _state.update { it.copy(query = shown) }
        val q = shown.withinDays?.let { d -> shown.copy(modifiedAfter = System.currentTimeMillis() - d * 86_400_000L) } ?: shown
        searchJob?.cancel()
        if (q.isEmpty) {
            _state.update { it.copy(results = emptyList(), searching = false, error = null) }
            return
        }
        searchJob = scope.launch(Dispatchers.IO) {
            delay(250) // debounce typing
            _state.update { it.copy(searching = true, error = null) }
            try {
                val locked = lockedRoots()
                val hits = if (q.content) {
                    if (q.text.isBlank()) emptyList() else {
                        val candidates = index.search(q, locked, limit = 3000)
                        ContentSearch.search(candidates, q.text.trim(), cancelled = { !isActive }).map { SearchHit(it.item, "${it.lineNumber}: ${it.snippet}") }
                    }
                } else index.search(q, locked).map { SearchHit(it) }
                _state.update { it.copy(results = hits, searching = false) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(results = emptyList(), searching = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun reindex() {
        if (indexJob?.isActive == true) return
        indexJob = scope.launch(Dispatchers.IO) {
            _state.update { it.copy(indexing = true) }
            try {
                index.rebuild(
                    File(rootPath), protection.paths,
                    isLockedProtected = { protection.lockedRoot(it) != null },
                    cancelled = { !isActive },
                    onProgress = { n -> _state.update { it.copy(indexedCount = n) } },
                )
                _state.update { it.copy(indexedCount = index.count(), indexedAt = index.lastIndexedAt()) }
                setQuery(_state.value.query)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            } finally {
                _state.update { it.copy(indexing = false) }
            }
        }
    }

    fun saveCurrent(name: String) {
        val n = name.trim()
        if (n.isEmpty() || _state.value.query.isEmpty) return
        persist(_saved.value.filter { it.name != n } + SavedSearch(n, _state.value.query))
    }

    fun deleteSaved(name: String) = persist(_saved.value.filter { it.name != name })

    private fun persist(list: List<SavedSearch>) {
        _saved.value = list
        prefs.savedSearches = SavedSearchCodec.encode(list)
    }
}

/**
 * Background refresh of the index. It has no way to ask for authentication, so it treats every protected
 * folder as locked and never indexes it; opening the search screen re-indexes with the current unlock state.
 */
class IndexWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val ctx = applicationContext
        if (!hasAllFilesAccess(ctx)) return Result.success()
        return try {
            SearchIndex(AndroidSqlDb.get(ctx)).rebuild(
                Environment.getExternalStorageDirectory(), AppPrefs(ctx).protectedFolders,
                isLockedProtected = { true }, cancelled = { isStopped },
            )
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<IndexWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("choifile_index", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
