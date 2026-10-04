package com.choidev.choifile.search

import com.choidev.choifile.storage.FileItem
import java.io.File
import java.nio.file.Files

/** Minimal SQL seam so the same queries run on Android's SQLite and on a JDBC SQLite in unit tests. */
interface SqlDb {
    fun execute(sql: String, args: List<Any?> = emptyList())
    fun <T> query(sql: String, args: List<Any?> = emptyList(), map: (SqlRow) -> T): List<T>
    fun <T> transaction(block: () -> T): T
}

interface SqlRow {
    fun string(i: Int): String
    fun long(i: Int): Long
}

private fun norm(path: String) = path.replace('\\', '/')

/** Folders that must never appear in the index regardless of lock state. */
private val ALWAYS_SKIPPED_NAMES = setOf(".choifile_vault", ".choifile_trash", ".glass_trash")

/**
 * Persistent file-name index. The index only ever holds names, sizes and dates, never file contents.
 * Protected folders are skipped while building unless the caller says they are unlocked, and every query
 * additionally filters out folders that are locked right now.
 */
class SearchIndex(private val db: SqlDb) {

    init {
        db.execute(
            "CREATE TABLE IF NOT EXISTS files (path TEXT PRIMARY KEY, name TEXT NOT NULL, name_lc TEXT NOT NULL, " +
                "ext TEXT NOT NULL, size INTEGER NOT NULL, modified INTEGER NOT NULL, is_dir INTEGER NOT NULL, gen INTEGER NOT NULL)",
        )
        db.execute("CREATE INDEX IF NOT EXISTS idx_files_name ON files(name_lc)")
        db.execute("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
    }

    fun count(): Int = db.query("SELECT COUNT(*) FROM files") { it.long(0).toInt() }.first()

    fun lastIndexedAt(): Long = db.query("SELECT v FROM meta WHERE k='indexed_at'") { it.string(0).toLong() }.firstOrNull() ?: 0L

    /**
     * Re-scans [root]. [isLockedProtected] is asked for every protected folder met on the way: true means
     * "skip it". Old rows are replaced generation-wise so a search during a rebuild still sees data.
     */
    fun rebuild(
        root: File,
        protectedFolders: Set<String>,
        isLockedProtected: (String) -> Boolean,
        cancelled: () -> Boolean = { false },
        onProgress: (Int) -> Unit = {},
    ): Int {
        val protectedNorm = protectedFolders.map(::norm)
        val gen = (db.query("SELECT COALESCE(MAX(gen),0) FROM files") { it.long(0) }.first()) + 1
        var n = 0
        val batch = ArrayList<IndexRow>(500)
        fun flush() {
            if (batch.isEmpty()) return
            val rows = batch.toList(); batch.clear()
            db.transaction {
                for (r in rows) {
                    db.execute(
                        "INSERT OR REPLACE INTO files(path,name,name_lc,ext,size,modified,is_dir,gen) VALUES(?,?,?,?,?,?,?,?)",
                        listOf(r.path, r.name, r.name.lowercase(), r.ext, r.size, r.modified, if (r.isDir) 1L else 0L, gen),
                    )
                }
            }
        }
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            if (cancelled()) return n
            val dir = stack.removeLast()
            val children = try { dir.listFiles() } catch (_: SecurityException) { null } ?: continue
            for (f in children) {
                if (cancelled()) return n
                if (f.name in ALWAYS_SKIPPED_NAMES) continue
                val p = norm(f.absolutePath)
                val isDir = f.isDirectory
                if (isDir && Files.isSymbolicLink(f.toPath())) continue // avoid loops
                if (isDir) {
                    if (p == norm(File(root, "Android/data").absolutePath) || p == norm(File(root, "Android/obb").absolutePath)) continue
                    if (p in protectedNorm && isLockedProtected(f.absolutePath)) continue
                    stack.addLast(f)
                }
                batch += IndexRow(p, f.name, if (isDir) "" else f.extension.lowercase(), if (isDir) 0L else f.length(), f.lastModified(), isDir)
                n++
                if (batch.size >= 500) { flush(); onProgress(n) }
            }
        }
        flush()
        db.transaction {
            db.execute("DELETE FROM files WHERE gen < ?", listOf(gen))
            db.execute("INSERT OR REPLACE INTO meta(k,v) VALUES('indexed_at', ?)", listOf(System.currentTimeMillis().toString()))
        }
        onProgress(n)
        return n
    }

    /** [lockedRoots]: protected folders that are locked at this moment; nothing below them is returned. */
    fun search(q: SearchQuery, lockedRoots: Set<String>, limit: Int = 500): List<FileItem> {
        val where = ArrayList<String>()
        val args = ArrayList<Any?>()
        if (!q.content) {
            for (w in q.words) { where += "name_lc LIKE ? ESCAPE '\\'"; args += "%" + likeEscape(w) + "%" }
        }
        val exts = HashSet<String>(q.extensions)
        q.kinds.filter { it != KindFilter.FOLDER }.forEach { exts += it.extensions }
        if (q.content) {
            // Content mode only considers text files, narrowed by whatever extension filter is set.
            if (exts.isEmpty()) exts += TEXT_EXTENSIONS else exts.retainAll(TEXT_EXTENSIONS)
        }
        val wantFolders = KindFilter.FOLDER in q.kinds && !q.content
        if (exts.isNotEmpty() || wantFolders || q.content) {
            val parts = ArrayList<String>()
            if (exts.isNotEmpty()) { parts += "(is_dir=0 AND ext IN (${exts.joinToString(",") { "?" }}))"; args.addAll(exts) }
            else if (q.content) parts += "(0)"
            if (wantFolders) parts += "is_dir=1"
            where += parts.joinToString(" OR ", "(", ")")
        }
        val sizeFiltered = q.minSize != null || q.maxSize != null
        if (sizeFiltered) where += "is_dir=0"
        q.minSize?.let { where += "size>=?"; args += it }
        q.maxSize?.let { where += "size<=?"; args += it }
        q.modifiedAfter?.let { where += "modified>=?"; args += it }
        q.modifiedBefore?.let { where += "modified<=?"; args += it }
        for (r in lockedRoots.map { norm(it).trimEnd('/') }) {
            where += "NOT (path = ? OR path LIKE ? ESCAPE '\\')"
            args += r
            args += likeEscape(r) + "/%"
        }
        val sql = "SELECT path,name,is_dir,size,modified FROM files" +
            (if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) +
            " ORDER BY is_dir DESC, modified DESC LIMIT ${limit.coerceIn(1, 5000)}"
        return db.query(sql, args) {
            FileItem(it.string(0), it.string(1), it.long(2) == 1L, it.long(3), it.long(4), it.string(1).startsWith("."))
        }
    }

    private data class IndexRow(val path: String, val name: String, val ext: String, val size: Long, val modified: Long, val isDir: Boolean)

    private fun likeEscape(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
