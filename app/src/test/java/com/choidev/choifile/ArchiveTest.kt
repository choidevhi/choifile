package com.choidev.choifile

import com.choidev.choifile.archive.ArchiveEntry
import com.choidev.choifile.archive.ArchiveExtractor
import com.choidev.choifile.archive.ArchiveFormat
import com.choidev.choifile.archive.ArchiveLimitException
import com.choidev.choifile.archive.ArchiveLimits
import com.choidev.choifile.archive.ArchiveLocation
import com.choidev.choifile.archive.ArchiveReader
import com.choidev.choifile.archive.ArchiveSecurityException
import com.choidev.choifile.archive.ArchiveWriter
import com.choidev.choifile.archive.childrenOf
import com.choidev.choifile.archive.safeResolve
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.GZIPOutputStream

class ArchiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zipOf(name: String, vararg entries: Pair<String, ByteArray>): File {
        val f = File(tmp.root, name)
        ZipArchiveOutputStream(f).use { z ->
            for ((n, data) in entries) {
                z.putArchiveEntry(ZipArchiveEntry(n))
                z.write(data)
                z.closeArchiveEntry()
            }
        }
        return f
    }

    private fun dest() = File(tmp.root, "out").also { it.mkdirs() }

    private fun assertRejected(zip: File) {
        val d = File(tmp.newFolder(), "dest")
        try {
            ArchiveExtractor.extract(zip, ArchiveFormat.ZIP, d)
            fail("expected ArchiveSecurityException")
        } catch (e: ArchiveSecurityException) {
            // expected
        }
        assertFalse("nothing may be written outside", File(d.parentFile, "evil.txt").exists())
        assertTrue("created files are cleaned up", d.walkTopDown().none { it.isFile })
    }

    // ---- zip-slip ----

    @Test fun zipSlipParentTraversalRejected() = assertRejected(zipOf("a.zip", "../evil.txt" to "x".toByteArray()))
    @Test fun zipSlipNestedTraversalRejected() = assertRejected(zipOf("b.zip", "a/b/../../../evil.txt" to "x".toByteArray()))
    @Test fun zipSlipAbsolutePathRejected() = assertRejected(zipOf("c.zip", "/tmp/evil.txt" to "x".toByteArray()))
    @Test fun zipSlipBackslashTraversalRejected() = assertRejected(zipOf("d.zip", "..\\evil.txt" to "x".toByteArray()))
    @Test fun zipSlipDriveLetterRejected() = assertRejected(zipOf("e.zip", "C:/evil.txt" to "x".toByteArray()))

    @Test fun hostileEntryAfterGoodOnesRemovesEarlierOutput() {
        val z = zipOf("f.zip", "good.txt" to "ok".toByteArray(), "../evil.txt" to "x".toByteArray())
        val d = dest()
        try { ArchiveExtractor.extract(z, ArchiveFormat.ZIP, d); fail() } catch (_: ArchiveSecurityException) {}
        assertFalse(File(d, "good.txt").exists())
    }

    @Test fun tarSlipRejected() {
        val f = File(tmp.root, "x.tar.gz")
        TarArchiveOutputStream(GZIPOutputStream(f.outputStream())).use { t ->
            val data = "x".toByteArray()
            val e = TarArchiveEntry("../evil.txt")
            e.size = data.size.toLong()
            t.putArchiveEntry(e); t.write(data); t.closeArchiveEntry()
        }
        try { ArchiveExtractor.extract(f, ArchiveFormat.TAR_GZ, dest()); fail() } catch (_: ArchiveSecurityException) {}
        assertFalse(File(tmp.root, "evil.txt").exists())
    }

    @Test fun tarSymlinkEntriesAreSkipped() {
        val f = File(tmp.root, "s.tar")
        TarArchiveOutputStream(f.outputStream()).use { t ->
            val link = TarArchiveEntry("link", TarArchiveEntry.LF_SYMLINK)
            link.linkName = "/etc"
            t.putArchiveEntry(link); t.closeArchiveEntry()
            val data = "hi".toByteArray()
            val e = TarArchiveEntry("real.txt"); e.size = data.size.toLong()
            t.putArchiveEntry(e); t.write(data); t.closeArchiveEntry()
        }
        val d = dest()
        assertEquals(1, ArchiveExtractor.extract(f, ArchiveFormat.TAR, d))
        assertFalse(File(d, "link").exists())
    }

    @Test fun safeResolveAcceptsNormalAndRejectsEscapes() {
        val root = tmp.newFolder()
        assertTrue(safeResolve(root, "a/b/c.txt").path.startsWith(root.canonicalPath))
        assertTrue(safeResolve(root, "./a//b.txt").path.startsWith(root.canonicalPath))
        listOf("../x", "a/../../x", "/abs", "\\abs", "..", "a\u0000b").forEach {
            try { safeResolve(root, it); fail("accepted $it") } catch (_: ArchiveSecurityException) {}
        }
    }

    // ---- zip bombs ----

    @Test fun totalSizeLimitStopsExpansion() {
        val z = zipOf("bomb.zip", "zeros.bin" to ByteArray(20 * 1024 * 1024))
        assertTrue("zeros must compress well", z.length() < 200_000)
        val d = dest()
        try {
            ArchiveExtractor.extract(z, ArchiveFormat.ZIP, d, limits = ArchiveLimits(maxTotalBytes = 5L * 1024 * 1024))
            fail()
        } catch (_: ArchiveLimitException) {}
        assertFalse(File(d, "zeros.bin").exists())
    }

    @Test fun perEntryLimitStopsExpansion() {
        val z = zipOf("bomb2.zip", "zeros.bin" to ByteArray(8 * 1024 * 1024))
        try {
            ArchiveExtractor.extract(z, ArchiveFormat.ZIP, dest(), limits = ArchiveLimits(maxEntryBytes = 1024 * 1024))
            fail()
        } catch (_: ArchiveLimitException) {}
    }

    @Test fun compressionRatioLimitStopsExpansion() {
        val z = zipOf("bomb3.zip", "zeros.bin" to ByteArray(20 * 1024 * 1024))
        try {
            ArchiveExtractor.extract(z, ArchiveFormat.ZIP, dest(), limits = ArchiveLimits(ratioFloorBytes = 1024 * 1024, maxRatio = 100))
            fail()
        } catch (e: ArchiveLimitException) {
            assertTrue(e.message!!.contains("ratio"))
        }
    }

    @Test fun entryCountLimit() {
        val z = zipOf("many.zip", *Array(50) { "f$it.txt" to ByteArray(1) })
        try {
            ArchiveExtractor.extract(z, ArchiveFormat.ZIP, dest(), limits = ArchiveLimits(maxEntries = 10))
            fail()
        } catch (_: ArchiveLimitException) {}
        try { ArchiveReader.list(z, ArchiveFormat.ZIP, ArchiveLimits(maxEntries = 10)); fail() } catch (_: ArchiveLimitException) {}
    }

    @Test fun normalArchiveStaysWithinDefaultLimits() {
        val z = zipOf("ok.zip", "a.txt" to "hello".toByteArray())
        assertEquals(1, ArchiveExtractor.extract(z, ArchiveFormat.ZIP, dest()))
    }

    // ---- create / list / extract round trips ----

    private fun sampleTree(): File {
        val root = tmp.newFolder("src")
        File(root, "top.txt").writeText("top")
        File(root, "sub/deep").mkdirs()
        File(root, "sub/deep/file.txt").writeText("deep content")
        File(root, "sub/empty").mkdirs()
        return root
    }

    private fun roundTrip(format: ArchiveFormat) {
        val src = sampleTree()
        val out = File(tmp.root, "made.${format.label}")
        ArchiveWriter.create(format, out, listOf(src))
        val entries = ArchiveReader.list(out, format)
        assertTrue(entries.map { it.path }.containsAll(listOf("src/top.txt", "src/sub/deep/file.txt")))
        val d = File(tmp.root, "rt_${format.label}")
        ArchiveExtractor.extract(out, format, d)
        assertEquals("top", File(d, "src/top.txt").readText())
        assertEquals("deep content", File(d, "src/sub/deep/file.txt").readText())
        assertTrue(File(d, "src/sub/empty").isDirectory)
    }

    @Test fun zipRoundTrip() = roundTrip(ArchiveFormat.ZIP)
    @Test fun sevenZRoundTrip() = roundTrip(ArchiveFormat.SEVEN_Z)
    @Test fun tarGzRoundTrip() = roundTrip(ArchiveFormat.TAR_GZ)

    @Test fun selectedExtraction() {
        val src = sampleTree()
        val out = File(tmp.root, "sel.zip")
        ArchiveWriter.create(ArchiveFormat.ZIP, out, listOf(src))
        val d = dest()
        ArchiveExtractor.extract(out, ArchiveFormat.ZIP, d, select = setOf("src/sub"))
        assertTrue(File(d, "src/sub/deep/file.txt").exists())
        assertFalse(File(d, "src/top.txt").exists())
    }

    @Test fun extractOneToFile() {
        val z = zipOf("one.zip", "d/x.txt" to "xx".toByteArray())
        val o = File(tmp.root, "x.out")
        ArchiveReader.extractOne(z, ArchiveFormat.ZIP, "d/x.txt", o)
        assertEquals("xx", o.readText())
    }

    @Test fun writerSkipsItsOwnOutputInsideSource() {
        val src = tmp.newFolder("self")
        File(src, "a.txt").writeText("a")
        val out = File(src, "self.zip")
        ArchiveWriter.create(ArchiveFormat.ZIP, out, listOf(src))
        assertFalse(ArchiveReader.list(out, ArchiveFormat.ZIP).any { it.path.endsWith("self.zip") })
    }

    // ---- browsing helpers ----

    @Test fun childrenSynthesizeImplicitFolders() {
        val entries = listOf(
            ArchiveEntry("a/b/c.txt", false, 3, 0),
            ArchiveEntry("a/d.txt", false, 1, 0),
            ArchiveEntry("top.txt", false, 2, 0),
        )
        assertEquals(setOf("a", "top.txt"), childrenOf(entries, "").map { it.path }.toSet())
        assertTrue(childrenOf(entries, "").first { it.path == "a" }.isDirectory)
        assertEquals(setOf("a/b", "a/d.txt"), childrenOf(entries, "a").map { it.path }.toSet())
        assertEquals(listOf("a/b/c.txt"), childrenOf(entries, "a/b").map { it.path })
    }

    @Test fun archiveBaseNameStripsKnownSuffixes() {
        assertEquals("photos", com.choidev.choifile.archive.archiveBaseName("photos.tar.gz"))
        assertEquals("a", com.choidev.choifile.archive.archiveBaseName("a.ZIP"))
        assertEquals("notes.txt", com.choidev.choifile.archive.archiveBaseName("notes.txt"))
        assertEquals(".zip", com.choidev.choifile.archive.archiveBaseName(".zip"))
    }

    @Test fun formatDetectionAndLocation() {
        assertEquals(ArchiveFormat.TAR_GZ, ArchiveFormat.detect("x.TAR.GZ"))
        assertEquals(ArchiveFormat.TAR_XZ, ArchiveFormat.detect("x.txz"))
        assertEquals(ArchiveFormat.RAR, ArchiveFormat.detect("a.rar"))
        assertNull(ArchiveFormat.detect("a.txt"))
        assertFalse(ArchiveFormat.RAR.writable)
        val loc = ArchiveLocation.make("/sdcard/a!b.zip", "dir/x")
        assertEquals("/sdcard/a!b.zip", ArchiveLocation.archiveOf(loc))
        assertEquals("dir/x", ArchiveLocation.innerOf(loc))
    }
}
