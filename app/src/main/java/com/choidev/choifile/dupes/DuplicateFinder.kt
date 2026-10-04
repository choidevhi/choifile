package com.choidev.choifile.dupes

import com.choidev.choifile.security.PathGuard
import com.choidev.choifile.util.FileHash
import java.io.File
import java.nio.file.Files

data class DupFile(val path: String, val size: Long, val modified: Long)

/** Files with identical content (same SHA-256). */
class DupGroup(val size: Long, val hash: String, val files: List<DupFile>) {
    /** Bytes freed by keeping a single copy. */
    val wasted: Long get() = size * (files.size - 1)
}

enum class DupStage { LISTING, PARTIAL, FULL }

object DuplicateFinder {
    const val PARTIAL_CHUNK = 64 * 1024

    fun listFiles(root: File, guard: PathGuard, minSize: Long, cancelled: () -> Boolean): List<DupFile> {
        val out = ArrayList<DupFile>()
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            if (cancelled()) return out
            val dir = stack.removeLast()
            val children = try { dir.listFiles() } catch (_: SecurityException) { null } ?: continue
            for (f in children) {
                if (guard.isExcluded(f.absolutePath)) continue
                if (Files.isSymbolicLink(f.toPath())) continue
                if (f.isDirectory) stack.addLast(f)
                else {
                    val size = f.length()
                    if (size >= minSize) out += DupFile(f.absolutePath, size, f.lastModified())
                }
            }
        }
        return out
    }

    /** Candidate sets: same size, at least two files. */
    fun groupBySize(files: List<DupFile>): List<List<DupFile>> =
        files.groupBy { it.size }.values.filter { it.size > 1 }

    /**
     * Stage 1 groups by size, stage 2 by a hash of the first and last 64 KiB, stage 3 by a full SHA-256.
     * Files up to two chunks long are fully covered by stage 2's chunks only when identical, so they still get stage 3.
     * Nothing here deletes anything.
     */
    fun find(
        root: File, guard: PathGuard, minSize: Long = 1, cancelled: () -> Boolean = { false },
        onProgress: (DupStage, done: Int, total: Int) -> Unit = { _, _, _ -> },
    ): List<DupGroup> {
        onProgress(DupStage.LISTING, 0, 0)
        val all = listFiles(root, guard, minSize, cancelled)
        return findIn(all, cancelled, onProgress)
    }

    fun findIn(all: List<DupFile>, cancelled: () -> Boolean = { false }, onProgress: (DupStage, Int, Int) -> Unit = { _, _, _ -> }): List<DupGroup> {
        val bySize = groupBySize(all)
        val partialCandidates = bySize.flatten()
        val partialGroups = ArrayList<List<DupFile>>()
        var done = 0
        val byPartial = HashMap<Pair<Long, String>, MutableList<DupFile>>()
        for (f in partialCandidates) {
            if (cancelled()) return emptyList()
            val h = try { FileHash.partialSha256(File(f.path), PARTIAL_CHUNK) } catch (_: Exception) { null }
            if (h != null) byPartial.getOrPut(f.size to h) { ArrayList() } += f
            onProgress(DupStage.PARTIAL, ++done, partialCandidates.size)
        }
        byPartial.values.filter { it.size > 1 }.forEach { partialGroups += it }

        val fullCandidates = partialGroups.flatten()
        done = 0
        val result = ArrayList<DupGroup>()
        for (group in partialGroups) {
            val byFull = HashMap<String, MutableList<DupFile>>()
            for (f in group) {
                if (cancelled()) return emptyList()
                val h = try { FileHash.sha256(File(f.path), cancelled) } catch (_: java.util.concurrent.CancellationException) { return emptyList() } catch (_: Exception) { null }
                if (h != null) byFull.getOrPut(h) { ArrayList() } += f
                onProgress(DupStage.FULL, ++done, fullCandidates.size)
            }
            for ((hash, files) in byFull) if (files.size > 1) result += DupGroup(group[0].size, hash, files)
        }
        return result.sortedByDescending { it.wasted }
    }

    /** The copy to keep by default: oldest modification time, then shortest path, then alphabetical. */
    fun defaultKeep(group: DupGroup): DupFile =
        group.files.sortedWith(compareBy<DupFile> { it.modified }.thenBy { it.path.length }.thenBy { it.path }).first()

    /** Paths pre-selected for deletion: everything except [defaultKeep]. */
    fun defaultSelection(group: DupGroup): Set<String> {
        val keep = defaultKeep(group)
        return group.files.filter { it !== keep }.map { it.path }.toSet()
    }

    /** A selection may never cover every copy in a group. Returns the paths that may actually be deleted. */
    fun safeSelection(group: DupGroup, selected: Set<String>): Set<String> {
        val inGroup = group.files.map { it.path }.filter { it in selected }.toSet()
        if (inGroup.size < group.files.size) return inGroup
        val keep = defaultKeep(group).path
        return inGroup - keep
    }
}
