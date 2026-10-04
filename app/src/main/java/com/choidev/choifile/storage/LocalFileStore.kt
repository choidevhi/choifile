package com.choidev.choifile.storage

import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom

class LocalFileStore(override val root: File) : FileStore {

    /** Hidden folders on the same volume so trashing/hiding is a cheap rename. */
    private val trashDir = File(root, ".choifile_trash")
    private val vaultDir = File(root, ".choifile_vault")
    private val trash = Bin(trashDir)
    private val vault = Bin(vaultDir)

    init {
        // One-time migration of the trash folder from the pre-rename app name.
        val old = File(root, ".glass_trash")
        if (old.isDirectory && !trashDir.exists()) old.renameTo(trashDir)
    }

    override fun list(dir: File): List<FileItem> =
        dir.listFiles().orEmpty().filter { !isInternal(it) }.map {
            FileItem(it.absolutePath, it.name, it.isDirectory, if (it.isFile) it.length() else 0L, it.lastModified(), it.isHidden || it.name.startsWith("."))
        }

    override fun createFolder(dir: File, name: String): File =
        uniqueChild(dir, validName(name)).also { check(it.mkdirs()) { "mkdir" } }

    override fun createTextFile(dir: File, name: String): File =
        uniqueChild(dir, validName(name)).also { check(it.createNewFile()) { "create" } }

    override fun createDummyFile(dir: File, name: String, megabytes: Int): File {
        val target = uniqueChild(dir, validName(name))
        val chunk = ByteArray(1024 * 1024)
        target.outputStream().buffered().use { out -> repeat(megabytes) { out.write(chunk) } }
        return target
    }

    override fun copy(src: File, dstDir: File): File {
        require(!isInside(dstDir, src)) { "target is inside source" }
        val target = uniqueChild(dstDir, src.name)
        if (src.isDirectory) check(src.copyRecursively(target)) { "copy" } else src.copyTo(target)
        return target
    }

    override fun move(src: File, dstDir: File): File {
        require(!isInside(dstDir, src)) { "target is inside source" }
        if (src.parentFile?.canonicalPath == dstDir.canonicalPath) return src
        val target = uniqueChild(dstDir, src.name)
        if (!src.renameTo(target)) {
            copy(src, dstDir)
            check(src.deleteRecursively()) { "delete source" }
        }
        return target
    }

    override fun rename(file: File, newName: String): File {
        val target = File(file.parentFile, validName(newName))
        check(!target.exists()) { "exists" }
        check(file.renameTo(target)) { "rename" }
        return target
    }

    override fun deletePermanently(file: File) {
        check(file.deleteRecursively()) { "delete" }
    }

    override fun secureDelete(file: File) {
        file.walkBottomUp().forEach { f ->
            if (f.isFile) overwriteWithRandom(f)
            check(f.delete()) { "delete" }
        }
    }

    override fun moveToTrash(file: File) = trash.put(file)
    override fun listTrash() = trash.list()
    override fun restore(entry: TrashEntry) = trash.restore(entry)
    override fun emptyTrash() = trash.clear()

    override fun hide(file: File) = vault.put(file)
    override fun listHidden() = vault.list()
    override fun unhide(entry: TrashEntry) = vault.restore(entry)

    override fun isInternal(file: File) = file == trashDir || file == vaultDir

    /**
     * A holding folder with layout id/origin (text file) and id/item/name. Used for the trash and for
     * hidden items. The .nomedia file makes galleries and media scanners skip the whole folder.
     */
    private inner class Bin(val dir: File) {
        fun put(file: File): TrashEntry {
            val id = System.currentTimeMillis().toString() + "_" + file.name.hashCode().toUInt()
            val itemDir = File(dir, "$id/item").also { it.mkdirs() }
            File(dir, ".nomedia").createNewFile()
            File(dir, "$id/origin").writeText(file.absolutePath)
            val target = File(itemDir, file.name)
            if (!file.renameTo(target)) {
                if (file.isDirectory) file.copyRecursively(target) else file.copyTo(target)
                check(file.deleteRecursively()) { "delete" }
            }
            return TrashEntry(id, file.name, file.absolutePath)
        }

        fun list(): List<TrashEntry> =
            dir.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { d ->
                val origin = File(d, "origin").takeIf { it.isFile }?.readText() ?: return@mapNotNull null
                val name = File(d, "item").list()?.firstOrNull() ?: return@mapNotNull null
                TrashEntry(d.name, name, origin)
            }.sortedByDescending { it.id }

        fun restore(entry: TrashEntry): File {
            val item = File(dir, "${entry.id}/item/${entry.name}")
            check(item.exists()) { "missing" }
            val originDir = File(entry.origin).parentFile ?: root
            originDir.mkdirs()
            val target = uniqueChild(originDir, entry.name)
            if (!item.renameTo(target)) {
                if (item.isDirectory) item.copyRecursively(target) else item.copyTo(target)
            }
            File(dir, entry.id).deleteRecursively()
            return target
        }

        fun clear() {
            dir.listFiles().orEmpty().filter { it.name != ".nomedia" }.forEach { it.deleteRecursively() }
        }
    }

    private fun validName(name: String): String {
        val n = name.trim()
        require(n.isNotEmpty() && '/' !in n && n != "." && n != "..") { "invalid name" }
        return n
    }

    private fun isInside(dir: File, ancestor: File): Boolean {
        val d = dir.canonicalPath
        val a = ancestor.canonicalPath
        return d == a || d.startsWith(a + File.separator)
    }

    /** "name.ext" -> "name (1).ext" until free. */
    override fun uniqueChild(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name).ifEmpty { name }
        val ext = if (base.length < name.length) name.substring(base.length) else ""
        var i = 1
        while (candidate.exists()) candidate = File(dir, "$base ($i)$ext").also { i++ }
        return candidate
    }
}

/** Replaces the file's bytes in place with random data and syncs, without changing its length. */
fun overwriteWithRandom(file: File) {
    val random = SecureRandom()
    val buf = ByteArray(64 * 1024)
    RandomAccessFile(file, "rw").use { raf ->
        var left = raf.length()
        raf.seek(0)
        while (left > 0) {
            random.nextBytes(buf)
            val n = minOf(left, buf.size.toLong()).toInt()
            raf.write(buf, 0, n)
            left -= n
        }
        raf.fd.sync()
    }
}
