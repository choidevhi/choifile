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

enum class SortKey { NAME, SIZE, DATE }

/** Folders always come first; [ascending] applies within each group. */
fun sortItems(items: List<FileItem>, key: SortKey, ascending: Boolean): List<FileItem> {
    val base: Comparator<FileItem> = when (key) {
        SortKey.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        SortKey.SIZE -> compareBy<FileItem> { it.size }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        SortKey.DATE -> compareBy<FileItem> { it.modified }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    }
    val directed = if (ascending) base else base.reversed()
    return items.sortedWith(compareByDescending<FileItem> { it.isDirectory }.then(directed))
}

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
