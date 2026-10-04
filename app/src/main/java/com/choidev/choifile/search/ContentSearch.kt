package com.choidev.choifile.search

import com.choidev.choifile.storage.FileItem
import java.io.File

data class ContentHit(val item: FileItem, val lineNumber: Int, val snippet: String)

/** Scans the text of small text files. Nothing is indexed or stored; it reads files on demand. */
object ContentSearch {

    /** Returns the first hit of [needle] in [file] or null. Binary files and files over [maxBytes] never match. */
    fun searchFile(file: File, needle: String, maxBytes: Long = CONTENT_SEARCH_MAX_BYTES): Pair<Int, String>? {
        if (needle.isEmpty() || !file.isFile || file.length() > maxBytes) return null
        val lower = needle.lowercase()
        file.inputStream().buffered().use { input ->
            input.mark(8192)
            val head = ByteArray(8192)
            val n = input.read(head)
            if (n > 0 && head.take(n).any { it == 0.toByte() }) return null // binary
            input.reset()
            var lineNo = 0
            input.reader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    lineNo++
                    val i = line.lowercase().indexOf(lower)
                    if (i >= 0) return lineNo to snippet(line, i, lower.length)
                }
            }
        }
        return null
    }

    fun search(
        candidates: List<FileItem>,
        needle: String,
        maxBytes: Long = CONTENT_SEARCH_MAX_BYTES,
        cancelled: () -> Boolean = { false },
    ): List<ContentHit> {
        val out = ArrayList<ContentHit>()
        for (c in candidates) {
            if (cancelled()) break
            if (c.isDirectory || c.size > maxBytes) continue
            val hit = runCatching { searchFile(File(c.path), needle, maxBytes) }.getOrNull() ?: continue
            out += ContentHit(c, hit.first, hit.second)
        }
        return out
    }

    private fun snippet(line: String, at: Int, len: Int): String {
        val from = (at - 30).coerceAtLeast(0)
        val to = (at + len + 60).coerceAtMost(line.length)
        return (if (from > 0) "…" else "") + line.substring(from, to).trim() + (if (to < line.length) "…" else "")
    }
}
