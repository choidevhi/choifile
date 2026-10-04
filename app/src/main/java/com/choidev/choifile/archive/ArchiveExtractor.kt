package com.choidev.choifile.archive

import java.io.File

object ArchiveExtractor {

    /**
     * Extracts into [destDir]. [select] (normalized entry paths) limits what is extracted; selecting a folder
     * takes everything below it. Any hostile entry or limit violation aborts the whole run and removes what was
     * already written. Returns the number of files written.
     */
    fun extract(
        archive: File,
        format: ArchiveFormat,
        destDir: File,
        select: Set<String>? = null,
        limits: ArchiveLimits = ArchiveLimits(),
        cancelled: () -> Boolean = { false },
        onProgress: (name: String, totalBytes: Long) -> Unit = { _, _ -> },
    ): Int {
        val archiveLength = archive.length().coerceAtLeast(1)
        val createdFiles = ArrayList<File>()
        val createdDirs = ArrayList<File>()
        var entries = 0
        var files = 0
        var total = 0L
        fun mkdirsTracked(dir: File) {
            val missing = generateSequence(dir) { it.parentFile }.takeWhile { !it.exists() }.toList()
            dir.mkdirs()
            createdDirs += missing.reversed()
        }
        try {
            mkdirsTracked(destDir.canonicalFile)
            ArchiveReader.walk(archive, format) { e, open ->
                if (cancelled()) throw ArchiveCancelledException()
                if (++entries > limits.maxEntries) throw ArchiveLimitException("too many entries (> ${limits.maxEntries})")
                // Validate every entry, even unselected ones: a hostile archive is rejected as a whole.
                val target = safeResolve(destDir, e.path)
                if (select != null && select.none { e.path == it || e.path.startsWith("$it/") }) return@walk
                if (e.isDirectory) {
                    mkdirsTracked(target)
                    return@walk
                }
                target.parentFile?.let { mkdirsTracked(it) }
                createdFiles += target
                var entryBytes = 0L
                open().use { input ->
                    target.outputStream().buffered().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            entryBytes += n
                            total += n
                            if (entryBytes > limits.maxEntryBytes) throw ArchiveLimitException("entry too large: ${e.path}")
                            if (total > limits.maxTotalBytes) throw ArchiveLimitException("archive expands beyond the size limit")
                            if (total > limits.ratioFloorBytes && total / archiveLength > limits.maxRatio) {
                                throw ArchiveLimitException("suspicious compression ratio (possible zip bomb)")
                            }
                            if (cancelled()) throw ArchiveCancelledException()
                            out.write(buf, 0, n)
                            onProgress(e.path, total)
                        }
                    }
                }
                if (e.modified > 0) target.setLastModified(e.modified)
                files++
            }
        } catch (t: Throwable) {
            createdFiles.forEach { it.delete() }
            createdDirs.asReversed().forEach { it.delete() } // only succeeds while empty
            throw t
        }
        return files
    }
}
