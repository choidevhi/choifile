package com.choidev.choifile.archive

import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

object ArchiveWriter {

    /** Creates [out] from [sources]; entry names are relative to each source's parent. Output is removed on failure. */
    fun create(
        format: ArchiveFormat,
        out: File,
        sources: List<File>,
        cancelled: () -> Boolean = { false },
        onProgress: (name: String, bytes: Long) -> Unit = { _, _ -> },
    ) {
        require(format.writable) { "cannot create ${format.label}" }
        val outCanonical = out.canonicalFile
        val items = ArrayList<Pair<File, String>>() // file or folder, entry name
        for (src in sources) {
            val base = src.canonicalFile.parentFile ?: continue
            src.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }.forEach { f ->
                if (Files.isSymbolicLink(f.toPath()) || f.canonicalFile == outCanonical) return@forEach
                items += f to f.canonicalFile.relativeTo(base).path.replace(File.separatorChar, '/')
            }
        }
        var bytes = 0L
        try {
            when (format) {
                ArchiveFormat.ZIP -> ZipArchiveOutputStream(out).use { z ->
                    z.setEncoding("UTF-8")
                    for ((f, name) in items) {
                        check(!cancelled()) { "cancelled" }
                        z.putArchiveEntry(ZipArchiveEntry(f, if (f.isDirectory) "$name/" else name))
                        if (f.isFile) { bytes += copyTo(f, z::write, cancelled); onProgress(name, bytes) }
                        z.closeArchiveEntry()
                    }
                }
                ArchiveFormat.SEVEN_Z -> SevenZOutputFile(out).use { s ->
                    for ((f, name) in items) {
                        check(!cancelled()) { "cancelled" }
                        s.putArchiveEntry(s.createArchiveEntry(f, name))
                        if (f.isFile) { bytes += copyTo(f, s::write, cancelled); onProgress(name, bytes) }
                        s.closeArchiveEntry()
                    }
                }
                else -> TarArchiveOutputStream(GZIPOutputStream(out.outputStream().buffered())).use { t ->
                    t.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    t.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                    for ((f, name) in items) {
                        check(!cancelled()) { "cancelled" }
                        t.putArchiveEntry(TarArchiveEntry(f, if (f.isDirectory) "$name/" else name))
                        if (f.isFile) { bytes += copyTo(f, t::write, cancelled); onProgress(name, bytes) }
                        t.closeArchiveEntry()
                    }
                }
            }
        } catch (t: Throwable) {
            out.delete()
            throw t
        }
    }

    private fun copyTo(f: File, write: (ByteArray, Int, Int) -> Unit, cancelled: () -> Boolean): Long {
        var n = 0L
        f.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                check(!cancelled()) { "cancelled" }
                write(buf, 0, r)
                n += r
            }
        }
        return n
    }
}
