package com.choidev.choifile.remote

import com.choidev.choifile.storage.FileItem
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Path-string based storage used for network backends and as the common currency of the transfer engine.
 * It sits next to [com.choidev.choifile.storage.FileStore] (which is bound to java.io.File and carries
 * trash and vault semantics that make no sense on a remote); [LocalRemoteStore] adapts local folders to
 * it so local-to-remote and remote-to-remote copies share one code path.
 *
 * Paths always use "/". All methods block and must be called off the main thread.
 */
interface RemoteStore : Closeable {
    fun list(path: String): List<FileItem>
    fun open(path: String): InputStream

    /** Creates or overwrites a file. [sizeHint] is the final size when known, or -1. Closing the stream finishes the upload. */
    fun create(path: String, sizeHint: Long = -1): OutputStream
    fun mkdir(path: String)

    /** Deletes a file or a folder with everything in it. */
    fun delete(path: String)
    fun rename(from: String, to: String)
}

object RemotePaths {
    fun join(dir: String, name: String): String = if (dir.endsWith("/")) dir + name else "$dir/$name"

    fun parent(path: String): String {
        val p = path.trimEnd('/')
        val i = p.lastIndexOf('/')
        return if (i <= 0) "/" else p.substring(0, i)
    }

    fun name(path: String): String = path.trimEnd('/').substringAfterLast('/')

    /** Collapses duplicate slashes and guarantees a leading slash. */
    fun normalize(path: String): String {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        return "/" + parts.joinToString("/")
    }

    /** "name.ext" -> "name (1).ext" until it is not in [taken]. Same scheme as the local store. */
    fun uniqueName(name: String, taken: Set<String>): String {
        if (name !in taken) return name
        val base = name.substringBeforeLast('.', name).ifEmpty { name }
        val ext = if (base.length < name.length) name.substring(base.length) else ""
        var i = 1
        while ("$base ($i)$ext" in taken) i++
        return "$base ($i)$ext"
    }
}

/** Where a tab or clipboard entry lives on a network connection: "remote:<connectionId>:<path>". */
object RemoteLocation {
    private const val PREFIX = "remote:"
    fun isRemote(path: String) = path.startsWith(PREFIX)
    fun make(connectionId: String, path: String = "/") = "$PREFIX$connectionId:${RemotePaths.normalize(path)}"
    fun connectionOf(location: String) = location.removePrefix(PREFIX).substringBefore(':')
    fun pathOf(location: String) = RemotePaths.normalize(location.removePrefix(PREFIX).substringAfter(':', "/"))
}

/** A local folder seen as a [RemoteStore], so the transfer engine can treat both sides alike. */
class LocalRemoteStore : RemoteStore {
    override fun list(path: String): List<FileItem> =
        File(path).listFiles().orEmpty().map {
            FileItem(it.absolutePath.replace('\\', '/'), it.name, it.isDirectory, if (it.isFile) it.length() else 0L, it.lastModified(), it.name.startsWith("."))
        }

    override fun open(path: String): InputStream = File(path).inputStream().buffered()

    override fun create(path: String, sizeHint: Long): OutputStream = File(path).outputStream().buffered()

    override fun mkdir(path: String) {
        val f = File(path)
        check(f.isDirectory || f.mkdirs()) { "mkdir" }
    }

    override fun delete(path: String) {
        check(File(path).deleteRecursively()) { "delete" }
    }

    override fun rename(from: String, to: String) {
        check(File(from).renameTo(File(to))) { "rename" }
    }

    override fun close() = Unit
}
