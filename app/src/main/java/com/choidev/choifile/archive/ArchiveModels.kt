package com.choidev.choifile.archive

import java.io.File

enum class ArchiveFormat(val label: String) {
    ZIP("zip"), SEVEN_Z("7z"), TAR("tar"), TAR_GZ("tar.gz"), TAR_BZ2("tar.bz2"), TAR_XZ("tar.xz"), RAR("rar");

    /** Formats this app can create. rar is read-only (no free compressor exists). */
    val writable: Boolean get() = this == ZIP || this == SEVEN_Z || this == TAR_GZ

    companion object {
        fun detect(name: String): ArchiveFormat? {
            val n = name.lowercase()
            return when {
                n.endsWith(".zip") || n.endsWith(".jar") || n.endsWith(".cbz") -> ZIP
                n.endsWith(".7z") -> SEVEN_Z
                n.endsWith(".tar.gz") || n.endsWith(".tgz") -> TAR_GZ
                n.endsWith(".tar.bz2") || n.endsWith(".tbz2") || n.endsWith(".tbz") -> TAR_BZ2
                n.endsWith(".tar.xz") || n.endsWith(".txz") -> TAR_XZ
                n.endsWith(".tar") -> TAR
                n.endsWith(".rar") -> RAR
                else -> null
            }
        }
    }
}

/** [path] is normalized: forward slashes, no leading or trailing slash. */
data class ArchiveEntry(val path: String, val isDirectory: Boolean, val size: Long, val modified: Long) {
    val name: String get() = path.substringAfterLast('/')
}

/**
 * Safety caps against zip bombs. All checks use the bytes actually produced while extracting,
 * never the sizes an archive header claims.
 */
data class ArchiveLimits(
    val maxEntries: Int = 100_000,
    val maxTotalBytes: Long = 8L * 1024 * 1024 * 1024,
    val maxEntryBytes: Long = 4L * 1024 * 1024 * 1024,
    /** Once this much has been written, the output may not exceed [maxRatio] times the archive's own size. */
    val ratioFloorBytes: Long = 64L * 1024 * 1024,
    val maxRatio: Long = 200,
)

open class ArchiveException(message: String) : Exception(message)

/** A path escaping the destination (zip-slip) or another hostile construct. Extraction is aborted. */
class ArchiveSecurityException(message: String) : ArchiveException(message)

class ArchiveLimitException(message: String) : ArchiveException(message)

class ArchiveUnsupportedException(message: String) : ArchiveException(message)

class ArchiveCancelledException : ArchiveException("cancelled")

/** "photos.tar.gz" -> "photos", "a.zip" -> "a". Names without an archive extension are returned unchanged. */
fun archiveBaseName(name: String): String {
    val lower = name.lowercase()
    val suffix = listOf(".tar.gz", ".tar.bz2", ".tar.xz", ".tgz", ".tbz2", ".tbz", ".txz", ".zip", ".jar", ".cbz", ".7z", ".tar", ".rar")
        .firstOrNull { lower.endsWith(it) } ?: return name
    return name.dropLast(suffix.length).ifEmpty { name }
}

/** Normalizes an entry name: backslashes to slashes, drops "./" and trailing slashes. */
fun normalizeEntryName(name: String): String {
    var n = name.replace('\\', '/')
    while (n.startsWith("./")) n = n.substring(2)
    return n.trimEnd('/')
}

/**
 * Resolves [entryName] below [destDir], or throws [ArchiveSecurityException] if it would land elsewhere:
 * absolute paths, drive letters, ".." segments, NUL bytes, or a canonical path outside [destDir].
 */
fun safeResolve(destDir: File, entryName: String): File {
    val n = normalizeEntryName(entryName)
    if (n.isEmpty()) throw ArchiveSecurityException("empty entry name")
    if ('\u0000' in n) throw ArchiveSecurityException("NUL in entry name")
    if (n.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(n)) throw ArchiveSecurityException("absolute path: $n")
    if (n.split('/').any { it == ".." }) throw ArchiveSecurityException("path traversal: $n")
    val base = destDir.canonicalFile
    val target = File(base, n).canonicalFile
    if (target.path != base.path && !target.path.startsWith(base.path + File.separator)) {
        throw ArchiveSecurityException("escapes destination: $n")
    }
    return target
}

/** Immediate children of [dir] ("" = root), synthesising folders that exist only implicitly. */
fun childrenOf(entries: List<ArchiveEntry>, dir: String): List<ArchiveEntry> {
    val prefix = if (dir.isEmpty()) "" else "$dir/"
    val result = LinkedHashMap<String, ArchiveEntry>()
    for (e in entries) {
        if (!e.path.startsWith(prefix) || e.path.length == prefix.length) continue
        val rest = e.path.substring(prefix.length)
        val slash = rest.indexOf('/')
        if (slash < 0) {
            result[rest] = e
        } else {
            val folder = rest.substring(0, slash)
            if (result[folder]?.isDirectory != true) {
                result[folder] = ArchiveEntry(prefix + folder, true, 0, e.modified)
            }
        }
    }
    return result.values.toList()
}

/** Tab/clipboard location of something inside an archive: "archive:<file>!<inner path>". */
object ArchiveLocation {
    private const val PREFIX = "archive:"
    fun isArchive(path: String) = path.startsWith(PREFIX)
    fun make(archive: String, inner: String = "") = "$PREFIX$archive!$inner"
    fun archiveOf(path: String) = path.removePrefix(PREFIX).substringBeforeLast('!')
    fun innerOf(path: String) = path.substringAfterLast('!', "").trim('/')
}
