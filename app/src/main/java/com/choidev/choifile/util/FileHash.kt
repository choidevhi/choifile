package com.choidev.choifile.util

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

enum class HashAlgo(val javaName: String, val hexLength: Int, val label: String) {
    MD5("MD5", 32, "MD5"), SHA1("SHA-1", 40, "SHA-1"), SHA256("SHA-256", 64, "SHA-256")
}

enum class HashCompare { MATCH, MISMATCH, INVALID }

object FileHash {
    private const val BUFFER = 256 * 1024

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /** MD5, SHA-1 and SHA-256 of [input] computed in a single pass. [onProgress] gets the bytes read so far. */
    fun all(input: InputStream, cancelled: () -> Boolean = { false }, onProgress: (Long) -> Unit = {}): Map<HashAlgo, String> {
        val digests = HashAlgo.entries.associateWith { MessageDigest.getInstance(it.javaName) }
        val buf = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            if (cancelled()) throw java.util.concurrent.CancellationException("cancelled")
            val n = input.read(buf)
            if (n < 0) break
            digests.values.forEach { it.update(buf, 0, n) }
            total += n
            onProgress(total)
        }
        return digests.mapValues { hex(it.value.digest()) }
    }

    fun all(file: File, cancelled: () -> Boolean = { false }, onProgress: (Long) -> Unit = {}): Map<HashAlgo, String> =
        file.inputStream().use { all(it, cancelled, onProgress) }

    fun sha256(file: File, cancelled: () -> Boolean = { false }): String {
        val d = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(BUFFER)
        file.inputStream().use { i ->
            while (true) {
                if (cancelled()) throw java.util.concurrent.CancellationException("cancelled")
                val n = i.read(buf)
                if (n < 0) break
                d.update(buf, 0, n)
            }
        }
        return hex(d.digest())
    }

    /** SHA-256 over at most the first and last [chunk] bytes plus the length; a cheap filter before hashing everything. */
    fun partialSha256(file: File, chunk: Int = 64 * 1024): String {
        val d = MessageDigest.getInstance("SHA-256")
        val length = file.length()
        d.update(length.toString().toByteArray())
        java.io.RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(chunk)
            var n = raf.read(buf)
            if (n > 0) d.update(buf, 0, n)
            if (length > chunk) {
                raf.seek(maxOf(chunk.toLong(), length - chunk))
                n = raf.read(buf)
                if (n > 0) d.update(buf, 0, n)
            }
        }
        return hex(d.digest())
    }

    /** Strips spaces, colons and dashes and lowercases, so "AB:CD ..." copied from other tools still compares. */
    fun normalize(pasted: String): String = pasted.trim().lowercase().filter { it.isLetterOrDigit() }

    /** Which algorithm a pasted hash belongs to, by its length; null when it is not a valid hex digest of a known size. */
    fun detect(pasted: String): HashAlgo? {
        val n = normalize(pasted)
        if (!n.all { it in '0'..'9' || it in 'a'..'f' }) return null
        return HashAlgo.entries.firstOrNull { it.hexLength == n.length }
    }

    fun compare(computed: Map<HashAlgo, String>, pasted: String): Pair<HashCompare, HashAlgo?> {
        val algo = detect(pasted) ?: return HashCompare.INVALID to null
        return (if (computed[algo] == normalize(pasted)) HashCompare.MATCH else HashCompare.MISMATCH) to algo
    }
}
