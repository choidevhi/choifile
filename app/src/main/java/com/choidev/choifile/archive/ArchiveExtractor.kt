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

    /**
     * "여기에 풀기" / "다른 위치에 풀기": extracts into [parent] itself, never overwriting anything there. The archive
     * is first extracted into a scratch folder inside [parent] (same volume, so the final step is a rename), then each
     * top-level item (or each selected entry when [select] is given) is moved up under a name from [uniqueChild].
     * A failure or cancel leaves [parent] exactly as it was. Returns the items now in [parent].
     */
    fun extractMerged(
        archive: File,
        format: ArchiveFormat,
        parent: File,
        uniqueChild: (dir: File, name: String) -> File,
        select: Set<String>? = null,
        limits: ArchiveLimits = ArchiveLimits(),
        cancelled: () -> Boolean = { false },
        onProgress: (name: String, totalBytes: Long) -> Unit = { _, _ -> },
    ): List<File> {
        val scratch = File(parent, SCRATCH_PREFIX + System.nanoTime())
        try {
            extract(archive, format, scratch, select, limits, cancelled, onProgress)
            if (cancelled()) throw ArchiveCancelledException()
            val tops = if (select == null) scratch.listFiles().orEmpty().sortedBy { it.name }
            else select.map(::normalizeEntryName).filter { it.isNotEmpty() }.sortedBy { it.length }.map { File(scratch, it) }
            val moved = ArrayList<File>()
            for (src in tops) {
                if (!src.exists()) continue // a selected entry that was inside another selected folder
                val dst = uniqueChild(parent, src.name)
                if (!src.renameTo(dst)) {
                    check(src.copyRecursively(dst)) { "could not move ${src.name}" }
                    src.deleteRecursively()
                }
                moved += dst
            }
            return moved
        } finally {
            scratch.deleteRecursively()
        }
    }

    /** Scratch folders of [extractMerged]; a leftover one (process killed midway) is safe to delete. */
    const val SCRATCH_PREFIX = ".choifile_extract_"
}
