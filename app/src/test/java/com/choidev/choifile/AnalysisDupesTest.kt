package com.choidev.choifile

import com.choidev.choifile.analysis.StorageAnalyzer
import com.choidev.choifile.analysis.TypeCategory
import com.choidev.choifile.analysis.categoryOf
import com.choidev.choifile.dupes.DupFile
import com.choidev.choifile.dupes.DupGroup
import com.choidev.choifile.dupes.DuplicateFinder
import com.choidev.choifile.security.PathGuard
import com.choidev.choifile.util.FileHash
import com.choidev.choifile.util.HashAlgo
import com.choidev.choifile.util.HashCompare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AnalysisDupesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun bytes(dir: File, rel: String, n: Int, fill: Byte = 1): File {
        val f = File(dir, rel); f.parentFile.mkdirs(); f.writeBytes(ByteArray(n) { fill }); return f
    }

    // ---- storage analysis ----

    @Test fun analysisSumsFoldersAndTypes() {
        val r = tmp.root
        bytes(r, "Pictures/a.jpg", 1000)
        bytes(r, "Pictures/sub/b.png", 500)
        bytes(r, "Movies/c.mp4", 4000)
        bytes(r, "notes.txt", 10)
        bytes(r, "setup.apk", 20)
        val res = StorageAnalyzer.scan(r, PathGuard(emptySet()))
        assertEquals(5530L, res.root.totalSize)
        assertEquals(5, res.totalFiles)
        val bySize = res.root.childrenBySize()
        assertEquals(listOf("Movies", "Pictures"), bySize.map { it.name })
        assertEquals(1500L, bySize[1].totalSize)
        assertEquals(1500L, res.byType[TypeCategory.IMAGE]!!.bytes)
        assertEquals(4000L, res.byType[TypeCategory.VIDEO]!!.bytes)
        assertEquals(20L, res.byType[TypeCategory.APK]!!.bytes)
        assertEquals(10L, res.byType[TypeCategory.DOCUMENT]!!.bytes)
        assertEquals("c.mp4", File(res.largestFiles.first().path).name)
        assertTrue(res.complete)
    }

    @Test fun analysisSkipsVaultTrashAndLockedProtectedFolders() {
        val r = tmp.root
        bytes(r, ".choifile_vault/secret.bin", 9000)
        bytes(r, ".choifile_trash/x/item/t.bin", 9000)
        bytes(r, "Private/p.bin", 7000)
        bytes(r, "Public/q.bin", 100)
        val protectedPath = File(r, "Private").absolutePath

        val locked = StorageAnalyzer.scan(r, PathGuard(setOf(protectedPath), isLocked = { true }))
        assertEquals(100L, locked.root.totalSize)
        assertTrue(locked.excludedFolders >= 3)

        val unlocked = StorageAnalyzer.scan(r, PathGuard(setOf(protectedPath), isLocked = { false }))
        assertEquals(7100L, unlocked.root.totalSize) // vault and trash still excluded
    }

    @Test fun analysisCanBeCancelled() {
        bytes(tmp.root, "a/x.bin", 10)
        val res = StorageAnalyzer.scan(tmp.root, PathGuard(emptySet()), cancelled = { true })
        assertFalse(res.complete)
    }

    @Test fun categories() {
        assertEquals(TypeCategory.IMAGE, categoryOf("A.JPG"))
        assertEquals(TypeCategory.ARCHIVE, categoryOf("x.zip"))
        assertEquals(TypeCategory.OTHER, categoryOf("noext"))
        assertEquals(TypeCategory.APK, categoryOf("app.apk"))
    }

    // ---- hashing ----

    @Test fun hashesMatchKnownVectors() {
        val f = File(tmp.root, "abc.txt").also { it.writeText("abc") }
        val h = FileHash.all(f)
        assertEquals("900150983cd24fb0d6963f7d28e17f72", h[HashAlgo.MD5])
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", h[HashAlgo.SHA1])
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", h[HashAlgo.SHA256])
        assertEquals(h[HashAlgo.SHA256], FileHash.sha256(f))
        val empty = File(tmp.root, "empty").also { it.writeBytes(ByteArray(0)) }
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", FileHash.all(empty)[HashAlgo.SHA256])
    }

    @Test fun comparePastedHash() {
        val f = File(tmp.root, "abc.txt").also { it.writeText("abc") }
        val h = FileHash.all(f)
        assertEquals(HashCompare.MATCH to HashAlgo.MD5, FileHash.compare(h, "  900150983CD24FB0D6963F7D28E17F72\n"))
        assertEquals(HashCompare.MATCH to HashAlgo.SHA256, FileHash.compare(h, "BA:78:16:BF:8F:01:CF:EA:41:41:40:DE:5D:AE:22:23:B0:03:61:A3:96:17:7A:9C:B4:10:FF:61:F2:00:15:AD"))
        assertEquals(HashCompare.MISMATCH to HashAlgo.SHA1, FileHash.compare(h, "a9993e364706816aba3e25717850c26c9cd0d89e"))
        assertEquals(HashCompare.INVALID, FileHash.compare(h, "not a hash").first)
        assertEquals(HashCompare.INVALID, FileHash.compare(h, "abcd").first)
        assertNull(FileHash.detect("zz".repeat(16)))
    }

    // ---- duplicates ----

    @Test fun groupsBySizeThenPartialThenFull() {
        val r = tmp.root
        // Same content in three places, one of them elsewhere.
        val big = ByteArray(300_000) { (it % 251).toByte() }
        File(r, "a").mkdirs(); File(r, "b/deep").mkdirs()
        File(r, "a/one.bin").writeBytes(big)
        File(r, "b/deep/one-copy.bin").writeBytes(big)
        File(r, "two.bin").writeBytes(big)
        // Same size and same first and last 64 KiB, but different in the middle: must NOT be a duplicate.
        val almost = big.copyOf().also { it[150_000] = (it[150_000] + 1).toByte() }
        File(r, "almost.bin").writeBytes(almost)
        // Same size as the others in a group of small files, different content.
        File(r, "s1").writeText("aaaa"); File(r, "s2").writeText("bbbb")
        // Unique size.
        File(r, "unique").writeText("hello there")
        // Empty files are ignored by default.
        File(r, "e1").writeBytes(ByteArray(0)); File(r, "e2").writeBytes(ByteArray(0))

        val sizeGroups = DuplicateFinder.groupBySize(DuplicateFinder.listFiles(r, PathGuard(emptySet()), 1) { false })
        assertEquals(2, sizeGroups.size) // 4 files of 300000 bytes, 2 files of 4 bytes

        val groups = DuplicateFinder.find(r, PathGuard(emptySet()))
        assertEquals(1, groups.size)
        val g = groups.single()
        assertEquals(setOf("one.bin", "one-copy.bin", "two.bin"), g.files.map { File(it.path).name }.toSet())
        assertEquals(2 * 300_000L, g.wasted)
        assertEquals(FileHash.sha256(File(r, "two.bin")), g.hash)
    }

    @Test fun duplicatesIgnoreVaultAndLockedFolders() {
        val r = tmp.root
        File(r, "x.bin").writeBytes(ByteArray(100) { 7 })
        bytes(r, ".choifile_vault/x.bin", 100, 7)
        bytes(r, "Locked/x.bin", 100, 7)
        val g = PathGuard(setOf(File(r, "Locked").absolutePath), isLocked = { true })
        assertTrue(DuplicateFinder.find(r, g).isEmpty())
        assertEquals(1, DuplicateFinder.find(r, PathGuard(setOf(File(r, "Locked").absolutePath), isLocked = { false })).size)
    }

    private fun group(vararg f: DupFile) = DupGroup(10, "h", f.toList())

    @Test fun defaultKeepsOldestThenShortestPath() {
        val g = group(DupFile("/s/zzz/new.bin", 10, 3000), DupFile("/s/a/very/long/old.bin", 10, 1000), DupFile("/s/o.bin", 10, 1000))
        assertEquals("/s/o.bin", DuplicateFinder.defaultKeep(g).path)
        assertEquals(setOf("/s/zzz/new.bin", "/s/a/very/long/old.bin"), DuplicateFinder.defaultSelection(g))
    }

    @Test fun aSelectionCanNeverCoverEveryCopy() {
        val g = group(DupFile("/a", 10, 2), DupFile("/b", 10, 1))
        val all = setOf("/a", "/b")
        assertEquals(setOf("/a"), DuplicateFinder.safeSelection(g, all)) // /b is the oldest and is kept
        assertEquals(setOf("/a"), DuplicateFinder.safeSelection(g, setOf("/a")))
        assertTrue(DuplicateFinder.safeSelection(g, emptySet()).isEmpty())
    }
}
