package com.choidev.choifile.storage

import java.io.File

data class FileItem(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
    val isHidden: Boolean,
)

enum class SortKey { NAME, DATE, SIZE, TYPE }

/**
 * How a folder is ordered. Names compare naturally ("file2" before "file10", case ignored); [foldersFirst] keeps
 * folders above files whatever the direction, the way Finder and iOS Files do by default.
 */
data class SortSpec(val key: SortKey = SortKey.NAME, val ascending: Boolean = true, val foldersFirst: Boolean = true) {
    /** Packed into one number for [KeyedLongStore]: key, direction and the folders-first switch. */
    fun pack(): Long = key.ordinal * 4L + (if (ascending) 2 else 0) + (if (foldersFirst) 1 else 0)

    companion object {
        fun unpack(v: Long): SortSpec? {
            val key = SortKey.entries.getOrNull((v / 4).toInt()) ?: return null
            return SortSpec(key, ascending = (v and 2L) != 0L, foldersFirst = (v and 1L) != 0L)
        }
    }
}

private val byName = Comparator<FileItem> { a, b -> com.choidev.choifile.util.NaturalOrder.compare(a.name, b.name) }

private fun extensionOf(item: FileItem): String = if (item.isDirectory) "" else item.name.substringAfterLast('.', "").lowercase()

fun sortItems(items: List<FileItem>, spec: SortSpec): List<FileItem> {
    val base: Comparator<FileItem> = when (spec.key) {
        SortKey.NAME -> byName
        SortKey.DATE -> compareBy<FileItem> { it.modified }.then(byName)
        SortKey.SIZE -> compareBy<FileItem> { it.size }.then(byName)
        SortKey.TYPE -> compareBy<FileItem> { extensionOf(it) }.then(byName)
    }
    val directed = if (spec.ascending) base else base.reversed()
    return items.sortedWith(if (spec.foldersFirst) compareByDescending<FileItem> { it.isDirectory }.then(directed) else directed)
}

/** Folders first; [ascending] applies within each group. */
fun sortItems(items: List<FileItem>, key: SortKey, ascending: Boolean): List<FileItem> = sortItems(items, SortSpec(key, ascending))

data class TrashEntry(val id: String, val name: String, val origin: String)

/**
 * Storage backend used by the browser. Only [LocalFileStore] exists today;
 * a remote or encrypted backend would implement this same contract.
 * All methods block and must be called off the main thread.
 */
interface FileStore {
    val root: File

    fun list(dir: File): List<FileItem>
    fun createFolder(dir: File, name: String): File
    fun createTextFile(dir: File, name: String): File
    fun createDummyFile(dir: File, name: String, megabytes: Int): File
    fun copy(src: File, dstDir: File): File
    fun move(src: File, dstDir: File): File
    fun rename(file: File, newName: String): File
    fun deletePermanently(file: File)

    /** Overwrites file contents with random bytes, then deletes. Best effort only; flash storage gives no guarantee. */
    fun secureDelete(file: File)

    fun moveToTrash(file: File): TrashEntry
    fun listTrash(): List<TrashEntry>
    fun restore(entry: TrashEntry): File
    fun emptyTrash()

    /**
     * Hidden items live in a private dot-folder with a .nomedia file that is never listed.
     * This is obscurity, not encryption: anyone with file access can find them.
     */
    fun hide(file: File): TrashEntry
    fun listHidden(): List<TrashEntry>
    fun unhide(entry: TrashEntry): File
    fun isInternal(file: File): Boolean
    fun uniqueChild(dir: File, name: String): File
}
