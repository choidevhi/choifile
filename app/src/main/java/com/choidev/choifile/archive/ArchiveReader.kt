package com.choidev.choifile.archive

import com.github.junrar.Archive
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** Visitor gets the entry and a lazy stream opener; the stream is only valid inside the callback. */
typealias EntryVisitor = (ArchiveEntry, () -> InputStream) -> Unit

object ArchiveReader {

    /** Lists every entry. Tar variants must be decompressed end to end to list, so large .tar.gz files take a while. */
    fun list(file: File, format: ArchiveFormat, limits: ArchiveLimits = ArchiveLimits()): List<ArchiveEntry> {
        val out = ArrayList<ArchiveEntry>()
        walk(file, format) { e, _ ->
            if (out.size >= limits.maxEntries) throw ArchiveLimitException("too many entries (> ${limits.maxEntries})")
            out += e
        }
        return out
    }

    /** Streams the entries in archive order. Symlinks and hard links are skipped, never followed. */
    fun walk(file: File, format: ArchiveFormat, visitor: EntryVisitor) {
        try {
            when (format) {
                ArchiveFormat.ZIP -> walkZip(file, visitor)
                ArchiveFormat.SEVEN_Z -> walkSevenZ(file, visitor)
                ArchiveFormat.RAR -> walkRar(file, visitor)
                else -> walkTar(file, format, visitor)
            }
        } catch (e: PasswordRequiredException) {
            throw ArchiveUnsupportedException("encrypted archive")
        }
    }

    /** Copies a single entry to [out]; used to open a file from inside an archive. */
    fun extractOne(file: File, format: ArchiveFormat, entryPath: String, out: File, maxBytes: Long = 512L * 1024 * 1024) {
        var found = false
        walk(file, format) { e, open ->
            if (!found && !e.isDirectory && e.path == entryPath) {
                found = true
                if (e.size > maxBytes) throw ArchiveLimitException("entry too large")
                out.parentFile?.mkdirs()
                open().use { input ->
                    out.outputStream().buffered().use { o ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) throw ArchiveLimitException("entry too large")
                            o.write(buf, 0, n)
                        }
                    }
                }
            }
        }
        if (!found) throw ArchiveException("entry not found: $entryPath")
    }

    private fun walkZip(file: File, visitor: EntryVisitor) {
        ZipFile.builder().setFile(file).get().use { zf ->
            val en = zf.entries
            while (en.hasMoreElements()) {
                val z = en.nextElement()
                if (z.isUnixSymlink) continue
                val name = normalizeEntryName(z.name)
                if (name.isEmpty()) continue
                val entry = ArchiveEntry(name, z.isDirectory, z.size.coerceAtLeast(0), z.time.coerceAtLeast(0))
                visitor(entry) {
                    if (!zf.canReadEntryData(z)) throw ArchiveUnsupportedException("encrypted or unsupported zip entry")
                    zf.getInputStream(z)
                }
            }
        }
    }

    private fun walkSevenZ(file: File, visitor: EntryVisitor) {
        SevenZFile.builder().setFile(file).get().use { sz ->
            while (true) {
                val z = sz.nextEntry ?: break
                if (z.isAntiItem) continue
                val name = normalizeEntryName(z.name ?: continue)
                if (name.isEmpty()) continue
                val modified = if (z.hasLastModifiedDate) z.lastModifiedDate.time else 0L
                val entry = ArchiveEntry(name, z.isDirectory, z.size.coerceAtLeast(0), modified)
                visitor(entry) {
                    object : InputStream() {
                        override fun read() = sz.read()
                        override fun read(b: ByteArray, off: Int, len: Int) = sz.read(b, off, len)
                    }
                }
            }
        }
    }

    private fun walkTar(file: File, format: ArchiveFormat, visitor: EntryVisitor) {
        val raw = BufferedInputStream(FileInputStream(file))
        val decoded: InputStream = try {
            when (format) {
                ArchiveFormat.TAR_GZ -> GZIPInputStream(raw)
                ArchiveFormat.TAR_BZ2 -> BZip2CompressorInputStream(raw)
                ArchiveFormat.TAR_XZ -> XZCompressorInputStream(raw)
                else -> raw
            }
        } catch (e: Exception) {
            raw.close(); throw e
        }
        TarArchiveInputStream(decoded).use { tar ->
            while (true) {
                val t = tar.nextEntry ?: break
                if (t.isSymbolicLink || t.isLink || !(t.isFile || t.isDirectory)) continue
                val name = normalizeEntryName(t.name)
                if (name.isEmpty()) continue
                val entry = ArchiveEntry(name, t.isDirectory, t.size.coerceAtLeast(0), t.modTime?.time ?: 0L)
                visitor(entry) { object : java.io.FilterInputStream(tar) { override fun close() {} } } // the tar stream outlives each entry
            }
        }
    }

    private fun walkRar(file: File, visitor: EntryVisitor) {
        Archive(file).use { rar ->
            if (rar.isEncrypted) throw ArchiveUnsupportedException("encrypted archive")
            for (h in rar.fileHeaders) {
                if (h.isEncrypted) throw ArchiveUnsupportedException("encrypted entry")
                val name = normalizeEntryName(h.fileName ?: continue)
                if (name.isEmpty()) continue
                val entry = ArchiveEntry(name, h.isDirectory, h.fullUnpackSize.coerceAtLeast(0), h.mTime?.time ?: 0L)
                visitor(entry) { rar.getInputStream(h) }
            }
        }
    }
}
