package com.choidev.choifile.oplog

import com.choidev.choifile.storage.FileStore
import com.choidev.choifile.storage.TrashEntry
import java.io.File

/**
 * Local file operations that write to the [OperationLog]. Every method records one log entry for the whole batch,
 * including a partial entry when it fails midway (what already happened is still undoable), then rethrows.
 */
class FileOps(private val store: FileStore, val log: OperationLog, private val clock: () -> Long = System::currentTimeMillis) {

    fun copy(srcs: List<File>, dstDir: File, origin: String = ORIGIN_MANUAL): List<OpItem> =
        batch(OpType.COPY, origin, srcs) { src ->
            val out = store.copy(src, dstDir)
            val (files, dirs) = describeCreated(out)
            OpItem(src.absolutePath, out.absolutePath, created = files, createdDirs = dirs)
        }

    /** Returns one item per moved entry; [OpItem.src] is the old path and [OpItem.dst] the new one. */
    fun move(srcs: List<File>, dstDir: File, origin: String = ORIGIN_MANUAL): List<OpItem> =
        batch(OpType.MOVE, origin, srcs) { src ->
            val out = store.move(src, dstDir)
            if (out.absolutePath == src.absolutePath) null else OpItem(src.absolutePath, out.absolutePath)
        }

    fun rename(file: File, newName: String): File {
        var result = file
        batch(OpType.RENAME, ORIGIN_MANUAL, listOf(file)) { src ->
            result = store.rename(src, newName)
            OpItem(src.absolutePath, result.absolutePath)
        }
        return result
    }

    /** Returns the log record id (for an immediate undo offer), or null when nothing was trashed. */
    fun trash(files: List<File>, origin: String = ORIGIN_MANUAL): String? {
        val items = batch(OpType.TRASH, origin, files) { f ->
            val e = store.moveToTrash(f)
            OpItem(f.absolutePath, null, trashId = e.id, trashName = e.name)
        }
        return lastId(items)
    }

    /** Permanent deletion cannot be undone; the log says so. */
    fun deletePermanently(files: List<File>, secure: Boolean) {
        val firstName = files.firstOrNull()?.name ?: ""
        var n = 0
        var error: Throwable? = null
        try {
            for (f in files) { if (secure) store.secureDelete(f) else store.deletePermanently(f); n++ }
        } catch (t: Throwable) { error = t }
        if (n > 0 || error != null) {
            addRecord(OpType.DELETE, ORIGIN_MANUAL, n, if (error == null) 0 else files.size - n, firstName, emptyList(),
                UndoState.UNAVAILABLE, if (secure) NoUndo.SECURE else NoUndo.PERMANENT)
        }
        error?.let { throw it }
    }

    /** Records files an extraction wrote into [dest] (which the operation created). */
    fun recordExtract(archive: File, dest: File, origin: String = ORIGIN_MANUAL) {
        if (!dest.exists()) return
        val (files, dirs) = describeCreated(dest)
        addRecord(OpType.EXTRACT, origin, 1, 0, archive.name, listOf(OpItem(archive.absolutePath, dest.absolutePath, created = files, createdDirs = dirs)), UndoState.AVAILABLE, null)
    }

    /** Records items an extraction placed directly into an existing folder (each one newly created there). */
    fun recordExtractMerged(archive: File, items: List<File>, origin: String = ORIGIN_MANUAL) {
        val made = items.filter { it.exists() }.map { top ->
            val (files, dirs) = describeCreated(top)
            OpItem(archive.absolutePath, top.absolutePath, created = files, createdDirs = dirs)
        }
        if (made.isEmpty()) return
        addRecord(OpType.EXTRACT, origin, made.size, 0, archive.name, made, UndoState.AVAILABLE, null)
    }

    fun recordArchive(out: File, origin: String = ORIGIN_MANUAL) {
        if (!out.isFile) return
        val (files, dirs) = describeCreated(out)
        addRecord(OpType.ARCHIVE, origin, 1, 0, out.name, listOf(OpItem(null, out.absolutePath, created = files, createdDirs = dirs)), UndoState.AVAILABLE, null)
    }

    /** For operations on servers or mixed locations, which are recorded but cannot be reverted. */
    fun recordIrreversible(type: OpType, count: Int, firstName: String, reason: String) {
        addRecord(type, ORIGIN_MANUAL, count, 0, firstName, emptyList(), UndoState.UNAVAILABLE, reason)
    }

    fun undo(recordId: String): UndoReport? {
        val rec = log.get(recordId) ?: return null
        if (rec.undoState != UndoState.AVAILABLE) return UndoReport(0, emptyList(), emptyList())
        val report = OpUndo.undo(rec, store)
        val state = when {
            report.restored > 0 && report.skipped.isEmpty() -> UndoState.DONE
            report.restored > 0 -> UndoState.PARTIAL
            else -> UndoState.AVAILABLE
        }
        log.update(rec.copy(undoState = state))
        return report
    }

    private fun lastId(items: List<OpItem>): String? = if (items.isEmpty()) null else log.all().firstOrNull()?.id

    private fun <T> batch(type: OpType, origin: String, inputs: List<T>, step: (T) -> OpItem?): List<OpItem> {
        val done = ArrayList<OpItem>()
        var error: Throwable? = null
        try {
            for (i in inputs) step(i)?.let(done::add)
        } catch (t: Throwable) {
            error = t
        }
        if (done.isNotEmpty()) {
            val first = done.first().let { it.dst ?: it.src }?.let { File(it).name } ?: ""
            val name = if (type == OpType.TRASH) done.first().trashName ?: first else first
            addRecord(type, origin, done.size, if (error == null) 0 else inputs.size - done.size, name, done, UndoState.AVAILABLE, null)
        }
        error?.let { throw it }
        return done
    }

    private fun addRecord(
        type: OpType, origin: String, count: Int, failed: Int, firstName: String, items: List<OpItem>,
        state: UndoState, reason: String?,
    ) {
        log.add(OpRecord(time = clock(), type = type, origin = origin, count = count, failed = failed, firstName = firstName, items = items, undoState = state, reason = reason))
    }
}

/** What the undo did. [moved] lists (from, to) pairs of items moved back so callers can fix up folder protection. */
data class UndoReport(val restored: Int, val skipped: List<Skipped>, val moved: List<Pair<String, String>>)

enum class SkipReason { MISSING, TARGET_EXISTS, MODIFIED, NOT_EMPTY, ERROR }

data class Skipped(val path: String, val reason: SkipReason)

/** Files and folders under [root] (inclusive), with the size and time they have right now. */
fun describeCreated(root: File): Pair<List<CreatedFile>, List<String>> {
    val files = ArrayList<CreatedFile>()
    val dirs = ArrayList<String>()
    root.walkTopDown().forEach { f ->
        if (f.isDirectory) dirs += f.absolutePath else files += CreatedFile(f.absolutePath, f.length(), f.lastModified())
    }
    return files to dirs
}

object OpUndo {
    fun undo(rec: OpRecord, store: FileStore): UndoReport {
        var restored = 0
        val skipped = ArrayList<Skipped>()
        val moved = ArrayList<Pair<String, String>>()
        val trash by lazy { store.listTrash() }
        when (rec.type) {
            OpType.MOVE, OpType.RENAME -> for (i in rec.items) {
                val src = File(i.src ?: continue); val dst = File(i.dst ?: continue)
                when {
                    !dst.exists() -> skipped += Skipped(dst.absolutePath, SkipReason.MISSING)
                    src.exists() -> skipped += Skipped(src.absolutePath, SkipReason.TARGET_EXISTS)
                    else -> try {
                        src.parentFile?.mkdirs()
                        if (!dst.renameTo(src)) {
                            if (dst.isDirectory) check(dst.copyRecursively(src)) { "copy" } else dst.copyTo(src)
                            check(dst.deleteRecursively()) { "delete" }
                        }
                        restored++
                        moved += dst.absolutePath to src.absolutePath
                    } catch (e: Exception) {
                        skipped += Skipped(src.absolutePath, SkipReason.ERROR)
                    }
                }
            }
            OpType.TRASH -> for (i in rec.items) {
                val id = i.trashId ?: continue
                val entry = trash.firstOrNull { it.id == id }
                if (entry == null) { skipped += Skipped(i.src ?: id, SkipReason.MISSING); continue }
                try { store.restore(TrashEntry(entry.id, entry.name, entry.origin)); restored++ }
                catch (e: Exception) { skipped += Skipped(i.src ?: id, SkipReason.ERROR) }
            }
            OpType.COPY, OpType.EXTRACT, OpType.ARCHIVE -> for (i in rec.items) {
                // Only what the operation created, and only files that still look untouched since then.
                for (c in i.created) {
                    val f = File(c.path)
                    when {
                        !f.exists() -> Unit
                        f.length() != c.size || f.lastModified() != c.modified -> skipped += Skipped(c.path, SkipReason.MODIFIED)
                        else -> if (f.delete()) restored++ else skipped += Skipped(c.path, SkipReason.ERROR)
                    }
                }
                for (d in i.createdDirs.sortedByDescending { it.length }) {
                    val dir = File(d)
                    if (!dir.isDirectory) continue
                    if (dir.list().isNullOrEmpty()) dir.delete() else skipped += Skipped(d, SkipReason.NOT_EMPTY)
                }
            }
            OpType.DELETE -> Unit
        }
        return UndoReport(restored, skipped, moved)
    }
}
