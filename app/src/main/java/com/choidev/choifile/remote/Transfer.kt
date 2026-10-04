package com.choidev.choifile.remote

import java.io.IOException

class TransferCancelledException : IOException("cancelled")

class CancelToken {
    @Volatile var cancelled = false
        private set

    fun cancel() { cancelled = true }
    fun check() { if (cancelled) throw TransferCancelledException() }
}

data class TransferProgress(
    val currentName: String,
    val bytesDone: Long,
    val bytesTotal: Long,
    val filesDone: Int,
    val filesTotal: Int,
) {
    val fraction: Float get() = if (bytesTotal <= 0) 0f else (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f)
}

/**
 * Streams files and folders between any two [RemoteStore]s without staging them on disk.
 * Cancelling or failing deletes the partially written destination file; files already completed stay.
 */
object TransferEngine {
    private const val BUFFER = 256 * 1024

    private data class Step(val src: String, val dst: String, val isDir: Boolean, val size: Long)

    /** Copies (or moves) [srcPath] into folder [dstDir]. Returns the new path. */
    fun transfer(
        src: RemoteStore,
        srcPath: String,
        dst: RemoteStore,
        dstDir: String,
        move: Boolean,
        cancel: CancelToken = CancelToken(),
        onProgress: (TransferProgress) -> Unit = {},
    ): String {
        val name = RemotePaths.name(srcPath)
        val taken = dst.list(dstDir).map { it.name }.toSet()
        val finalName = RemotePaths.uniqueName(name, taken)
        val target = RemotePaths.join(dstDir, finalName)
        require(!(src === dst && (target == srcPath || target.startsWith(srcPath.trimEnd('/') + "/")))) { "target is inside source" }

        if (move && src === dst) {
            if (RemotePaths.parent(srcPath) == RemotePaths.normalize(dstDir) && finalName == name) return srcPath
            src.rename(srcPath, target)
            return target
        }

        val steps = ArrayList<Step>()
        plan(src, srcPath, target, isDirHint = null, steps)
        val total = steps.sumOf { it.size }
        val files = steps.count { !it.isDir }
        var done = 0L
        var filesDone = 0
        var lastReport = System.nanoTime() - 1_000_000_000L // so the first chunk reports
        for (s in steps) {
            cancel.check()
            if (s.isDir) { dst.mkdir(s.dst); continue }
            var written = false
            try {
                src.open(s.src).use { input ->
                    dst.create(s.dst, s.size).use { out ->
                        val buf = ByteArray(BUFFER)
                        while (true) {
                            cancel.check()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.nanoTime()
                            if (now - lastReport > 100_000_000L) {
                                lastReport = now
                                onProgress(TransferProgress(RemotePaths.name(s.src), done, total, filesDone, files))
                            }
                        }
                    }
                }
                written = true
            } finally {
                if (!written) runCatching { dst.delete(s.dst) } // do not leave half a file behind
            }
            filesDone++
            onProgress(TransferProgress(RemotePaths.name(s.src), done, total, filesDone, files))
        }
        if (move) src.delete(srcPath)
        return target
    }

    private fun plan(src: RemoteStore, path: String, target: String, isDirHint: Boolean?, out: MutableList<Step>) {
        // The top item is classified by listing its parent; children come classified from list().
        val isDir: Boolean
        val size: Long
        if (isDirHint == null) {
            val info = src.list(RemotePaths.parent(path)).firstOrNull { RemotePaths.name(it.path) == RemotePaths.name(path) }
            isDir = info?.isDirectory ?: false
            size = info?.size ?: 0L
        } else { isDir = isDirHint; size = 0L }
        if (!isDir) { out += Step(path, target, false, size); return }
        out += Step(path, target, true, 0)
        for (c in src.list(path)) planChild(src, c.path, RemotePaths.join(target, c.name), c.isDirectory, c.size, out)
    }

    private fun planChild(src: RemoteStore, path: String, target: String, isDir: Boolean, size: Long, out: MutableList<Step>) {
        if (!isDir) { out += Step(path, target, false, size); return }
        out += Step(path, target, true, 0)
        for (c in src.list(path)) planChild(src, c.path, RemotePaths.join(target, c.name), c.isDirectory, c.size, out)
    }
}
