package com.choidev.choifile.analysis

import com.choidev.choifile.search.KindFilter
import com.choidev.choifile.security.PathGuard
import java.io.File
import java.nio.file.Files
import java.util.PriorityQueue

enum class TypeCategory { IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, APK, OTHER }

fun categoryOf(name: String): TypeCategory {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        ext.isEmpty() -> TypeCategory.OTHER
        ext in KindFilter.IMAGE.extensions -> TypeCategory.IMAGE
        ext in KindFilter.VIDEO.extensions -> TypeCategory.VIDEO
        ext in KindFilter.AUDIO.extensions -> TypeCategory.AUDIO
        ext in KindFilter.DOCUMENT.extensions -> TypeCategory.DOCUMENT
        ext == "apk" || ext == "xapk" || ext == "apks" -> TypeCategory.APK
        ext in KindFilter.ARCHIVE.extensions -> TypeCategory.ARCHIVE
        else -> TypeCategory.OTHER
    }
}

class FileEntry(val path: String, val size: Long)

/** A folder with its recursive size. [topFiles] are the largest files directly inside it, biggest first. */
class DirNode(val path: String, val name: String) {
    var totalSize = 0L
    var fileCount = 0
    val dirs = ArrayList<DirNode>()
    val topFiles = ArrayList<FileEntry>()

    fun childrenBySize(): List<DirNode> = dirs.sortedByDescending { it.totalSize }
}

class TypeStat(val bytes: Long, val count: Int)

class ScanResult(
    val root: DirNode,
    val byType: Map<TypeCategory, TypeStat>,
    val largestFiles: List<FileEntry>,
    val totalFiles: Int,
    /** Folders that were left out (locked protected folders, app vault and trash). */
    val excludedFolders: Int,
    val complete: Boolean,
)

object StorageAnalyzer {
    private const val TOP_PER_DIR = 30
    private const val TOP_GLOBAL = 100

    /**
     * Walks [root] once and builds the folder tree with recursive sizes, a per-type breakdown and the biggest files.
     * Anything [guard] excludes is skipped completely, so its size is not part of any total.
     */
    fun scan(root: File, guard: PathGuard, cancelled: () -> Boolean = { false }, onProgress: (Int) -> Unit = {}): ScanResult {
        val top = PriorityQueue<FileEntry>(compareBy { it.size })
        val bytes = LongArray(TypeCategory.entries.size)
        val counts = IntArray(TypeCategory.entries.size)
        var files = 0
        var excluded = 0
        var stopped = false

        fun walk(dir: File, node: DirNode) {
            val children = try { dir.listFiles() } catch (_: SecurityException) { null } ?: return
            for (f in children) {
                if (stopped || cancelled()) { stopped = true; return }
                val path = f.absolutePath
                if (guard.isExcluded(path)) { if (f.isDirectory) excluded++; continue }
                if (Files.isSymbolicLink(f.toPath())) continue
                if (f.isDirectory) {
                    val child = DirNode(path, f.name)
                    walk(f, child)
                    node.dirs += child
                    node.totalSize += child.totalSize
                    node.fileCount += child.fileCount
                } else {
                    val size = f.length()
                    node.totalSize += size
                    node.fileCount++
                    files++
                    val cat = categoryOf(f.name).ordinal
                    bytes[cat] += size
                    counts[cat]++
                    val entry = FileEntry(path, size)
                    node.topFiles += entry
                    if (node.topFiles.size > TOP_PER_DIR * 2) trim(node)
                    if (top.size < TOP_GLOBAL) top += entry else if (size > top.peek()!!.size) { top.poll(); top += entry }
                    if (files % 2000 == 0) onProgress(files)
                }
            }
        }

        val rootNode = DirNode(root.absolutePath, root.name.ifEmpty { "/" })
        walk(root, rootNode)
        finish(rootNode)
        onProgress(files)
        return ScanResult(
            rootNode,
            TypeCategory.entries.associateWith { TypeStat(bytes[it.ordinal], counts[it.ordinal]) },
            top.sortedByDescending { it.size },
            files, excluded, !stopped,
        )
    }

    private fun trim(node: DirNode) {
        node.topFiles.sortByDescending { it.size }
        while (node.topFiles.size > TOP_PER_DIR) node.topFiles.removeAt(node.topFiles.size - 1)
    }

    private fun finish(node: DirNode) {
        trim(node)
        node.dirs.forEach(::finish)
    }
}
