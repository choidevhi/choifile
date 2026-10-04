package com.choidev.choifile

import com.choidev.choifile.search.ContentSearch
import com.choidev.choifile.search.KindFilter
import com.choidev.choifile.search.SavedSearch
import com.choidev.choifile.search.SavedSearchCodec
import com.choidev.choifile.search.SearchIndex
import com.choidev.choifile.search.SearchQuery
import com.choidev.choifile.search.SqlDb
import com.choidev.choifile.search.SqlRow
import com.choidev.choifile.search.parseExtensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** Runs the real index SQL on a JDBC SQLite so the queries are verified without a device. */
class JdbcSqlDb(private val conn: Connection) : SqlDb {
    override fun execute(sql: String, args: List<Any?>) {
        conn.prepareStatement(sql).use { st -> bind(st, args); st.execute() }
    }

    override fun <T> query(sql: String, args: List<Any?>, map: (SqlRow) -> T): List<T> =
        conn.prepareStatement(sql).use { st ->
            bind(st, args)
            st.executeQuery().use { rs ->
                val row = object : SqlRow {
                    override fun string(i: Int): String = rs.getString(i + 1) ?: ""
                    override fun long(i: Int): Long = rs.getLong(i + 1)
                }
                val out = ArrayList<T>()
                while (rs.next()) out += map(row)
                out
            }
        }

    override fun <T> transaction(block: () -> T): T {
        conn.autoCommit = false
        try { return block().also { conn.commit() } } catch (t: Throwable) { conn.rollback(); throw t } finally { conn.autoCommit = true }
    }

    private fun bind(st: java.sql.PreparedStatement, args: List<Any?>) {
        args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
    }
}

class SearchTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var index: SearchIndex
    private lateinit var root: File

    @Before fun setUp() {
        index = SearchIndex(JdbcSqlDb(DriverManager.getConnection("jdbc:sqlite::memory:")))
        root = tmp.newFolder("storage")
    }

    private fun file(rel: String, text: String = "x", modified: Long? = null): File {
        val f = File(root, rel)
        f.parentFile.mkdirs()
        f.writeText(text)
        modified?.let { f.setLastModified(it) }
        return f
    }

    private fun build(protectedFolders: Set<String> = emptySet(), locked: (String) -> Boolean = { true }) =
        index.rebuild(root, protectedFolders, locked)

    private fun names(q: SearchQuery, lockedRoots: Set<String> = emptySet()) =
        index.search(q, lockedRoots).map { it.name }.sorted()

    @Test fun nameSearchIsCaseInsensitiveAndAllWordsMustMatch() {
        file("Holiday Photo.JPG"); file("holiday notes.txt"); file("work.txt")
        build()
        assertEquals(listOf("Holiday Photo.JPG", "holiday notes.txt"), names(SearchQuery("HOLIDAY")))
        assertEquals(listOf("holiday notes.txt"), names(SearchQuery("holiday notes")))
        assertEquals(emptyList<String>(), names(SearchQuery("holiday zzz")))
    }

    @Test fun likeWildcardsInQueryAreLiteral() {
        file("100%.txt"); file("a_b.txt"); file("axb.txt")
        build()
        assertEquals(listOf("100%.txt"), names(SearchQuery("%")))
        assertEquals(listOf("a_b.txt"), names(SearchQuery("a_b")))
    }

    @Test fun extensionAndKindFilters() {
        file("a.jpg"); file("b.png"); file("c.mp4"); file("d.txt"); file("e.zip"); file("dir/f.jpg")
        build()
        assertEquals(listOf("a.jpg", "b.png", "f.jpg"), names(SearchQuery(extensions = parseExtensions(".jpg, PNG"))))
        assertEquals(listOf("a.jpg", "b.png", "f.jpg"), names(SearchQuery(kinds = setOf(KindFilter.IMAGE))))
        assertEquals(listOf("e.zip"), names(SearchQuery(kinds = setOf(KindFilter.ARCHIVE))))
        assertEquals(listOf("dir"), names(SearchQuery(kinds = setOf(KindFilter.FOLDER))))
        assertEquals(listOf("c.mp4", "dir"), names(SearchQuery(kinds = setOf(KindFilter.VIDEO, KindFilter.FOLDER))))
    }

    @Test fun sizeFilterExcludesFoldersAndAppliesBounds() {
        file("small.bin", "x"); file("big.bin", "x".repeat(5000)); file("d/inner.txt")
        build()
        assertEquals(listOf("big.bin"), names(SearchQuery(minSize = 1000)))
        assertEquals(listOf("inner.txt", "small.bin"), names(SearchQuery(maxSize = 1000)))
        assertEquals(listOf("small.bin"), names(SearchQuery(minSize = 1, maxSize = 100, text = "small")))
    }

    @Test fun dateFilter() {
        val day = 86_400_000L
        val now = System.currentTimeMillis()
        file("old.txt", modified = now - 400 * day); file("recent.txt", modified = now - 2 * day)
        build()
        assertEquals(listOf("recent.txt"), names(SearchQuery(modifiedAfter = now - 30 * day)))
        assertEquals(listOf("old.txt"), names(SearchQuery(modifiedBefore = now - 30 * day)))
    }

    @Test fun vaultAndTrashAreNeverIndexed() {
        file(".choifile_vault/1/item/secret.txt"); file(".choifile_trash/1/item/trashed.txt"); file("visible.txt")
        build()
        assertEquals(listOf("visible.txt"), names(SearchQuery("txt")))
        assertEquals(emptyList<String>(), names(SearchQuery("secret")))
        // even when asked to treat everything as unlocked
        build(locked = { false })
        assertEquals(emptyList<String>(), names(SearchQuery("secret")))
        assertEquals(emptyList<String>(), names(SearchQuery("trashed")))
    }

    @Test fun lockedProtectedFolderIsNotIndexed() {
        file("prot/diary.txt"); file("open.txt")
        val prot = File(root, "prot").absolutePath
        build(setOf(prot)) { true }
        assertEquals(emptyList<String>(), names(SearchQuery("diary")))
        assertEquals(listOf("open.txt"), names(SearchQuery("open")))
    }

    @Test fun unlockedProtectedFolderIsIndexedButHiddenWhenLockedAgain() {
        file("prot/diary.txt"); file("open.txt")
        val prot = File(root, "prot").absolutePath
        build(setOf(prot)) { false } // unlocked while indexing
        assertEquals(listOf("diary.txt"), names(SearchQuery("diary")))
        // the folder locks again: query-time filter removes it without a rebuild
        assertEquals(emptyList<String>(), names(SearchQuery("diary"), lockedRoots = setOf(prot)))
        assertEquals(emptyList<String>(), names(SearchQuery(kinds = setOf(KindFilter.FOLDER)), lockedRoots = setOf(prot)))
        assertEquals(listOf("open.txt"), names(SearchQuery("open"), lockedRoots = setOf(prot)))
    }

    @Test fun lockedRootFilterDoesNotHideSiblingWithSamePrefix() {
        file("prot/a.txt"); file("prot2/b.txt")
        build()
        val prot = File(root, "prot").absolutePath
        assertEquals(listOf("b.txt"), names(SearchQuery("txt"), lockedRoots = setOf(prot)))
    }

    @Test fun rebuildRemovesDeletedFiles() {
        val a = file("gone.txt"); file("stay.txt")
        build()
        a.delete()
        build()
        assertEquals(listOf("stay.txt"), names(SearchQuery("txt")))
        assertEquals(1, index.count())
    }

    // ---- content search ----

    @Test fun contentSearchFindsTextAndSkipsBinaryAndLargeFiles() {
        file("a.txt", "first line\nThe Needle is here\nlast")
        file("b.txt", "nothing")
        File(root, "bin.txt").writeBytes(byteArrayOf(0x4e, 0x65, 0x65, 0x64, 0x6c, 0x65, 0, 1, 2))
        file("big.txt", "needle " + "x".repeat(3000))
        build()
        val candidates = index.search(SearchQuery(text = "needle", content = true), emptySet())
        assertEquals(setOf("a.txt", "b.txt", "bin.txt", "big.txt"), candidates.map { it.name }.toSet())
        val hits = ContentSearch.search(candidates, "needle", maxBytes = 2000)
        assertEquals(listOf("a.txt"), hits.map { it.item.name })
        assertEquals(2, hits[0].lineNumber)
        assertTrue(hits[0].snippet.contains("Needle"))
        assertEquals(listOf("a.txt", "big.txt"), ContentSearch.search(candidates, "NEEDLE").map { it.item.name }.sorted())
    }

    @Test fun contentModeOnlyConsidersTextExtensionsAndHonoursLock() {
        file("note.md", "secret word"); file("photo.jpg", "secret word"); file("prot/p.txt", "secret word")
        build(locked = { false })
        val prot = File(root, "prot").absolutePath
        val c = index.search(SearchQuery(text = "secret", content = true), setOf(prot))
        assertEquals(listOf("note.md"), c.map { it.name })
    }

    // ---- saved searches ----

    @Test fun savedSearchRoundTrip() {
        val q = SearchQuery("abc", setOf("jpg"), setOf(KindFilter.IMAGE, KindFilter.FOLDER), 10, 200, 5, 9, true, withinDays = 7)
        val back = SavedSearchCodec.decode(SavedSearchCodec.encode(listOf(SavedSearch("내 검색", q), SavedSearch("empty", SearchQuery()))))
        assertEquals(listOf("내 검색", "empty"), back.map { it.name })
        assertEquals(q, back[0].query)
        assertEquals(SearchQuery(), back[1].query)
        assertTrue(SavedSearchCodec.decode("not json").isEmpty())
        assertTrue(SavedSearchCodec.decode(null).isEmpty())
    }

    @Test fun emptyQueryDetection() {
        assertTrue(SearchQuery().isEmpty)
        assertFalse(SearchQuery(minSize = 1).isEmpty)
    }
}
