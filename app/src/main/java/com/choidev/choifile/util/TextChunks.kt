package com.choidev.choifile.util

import java.io.File
import java.io.RandomAccessFile

/**
 * A big text file read in pieces: the file is cut into chunks of about [target] bytes at line breaks, and a chunk
 * is decoded only when it is shown. Cutting right after a '\n' byte is safe for UTF-8 and CP949 (that byte never
 * occurs inside a multi-byte character), so each chunk decodes on its own. Free of Android so it can be tested.
 */
class TextChunks private constructor(private val file: File, private val starts: LongArray, val charset: String) {

    val size: Int get() = starts.size

    /** Text of chunk [i], without the trailing line break. */
    fun read(i: Int): String {
        val start = starts[i]
        val end = if (i + 1 < starts.size) starts[i + 1] else file.length()
        val bytes = ByteArray((end - start).toInt())
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            raf.readFully(bytes)
        }
        val skip = if (i == 0) bomLength(bytes) else 0
        return String(bytes, skip, bytes.size - skip, charset(charset)).removeSuffix("\n").removeSuffix("\r")
    }

    companion object {
        const val DEFAULT_TARGET = 64 * 1024

        /** Indexes [file]; reads it once from start to end. [charset] is detected from its start when null. */
        fun index(file: File, target: Int = DEFAULT_TARGET, charset: String? = null): TextChunks {
            val length = file.length()
            val head = ByteArray(minOf(length, 256L * 1024).toInt())
            RandomAccessFile(file, "r").use { it.readFully(head) }
            val cs = charset ?: SubtitleText.detect(head)
            // UTF-16 has '\n' as two bytes, so the byte-level cut would split characters: keep the file whole.
            if (cs.startsWith("UTF-16")) return TextChunks(file, longArrayOf(0), cs)

            val starts = ArrayList<Long>()
            starts.add(0)
            val buf = ByteArray(1 shl 20)
            var nextCut = target.toLong()
            var pos = 0L
            file.inputStream().buffered(1 shl 20).use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    var i = 0
                    while (i < n) {
                        val at = pos + i
                        if (at >= nextCut && buf[i] == '\n'.code.toByte() && at + 1 < length) {
                            starts.add(at + 1)
                            nextCut = at + 1 + target
                        }
                        i++
                    }
                    pos += n
                }
            }
            return TextChunks(file, starts.toLongArray(), cs)
        }

        private fun bomLength(b: ByteArray): Int = when {
            b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> 3
            b.size >= 2 && (b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() || b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) -> 2
            else -> 0
        }
    }
}
