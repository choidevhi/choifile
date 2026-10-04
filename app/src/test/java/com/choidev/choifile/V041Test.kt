package com.choidev.choifile

import com.choidev.choifile.archive.ArchiveCancelledException
import com.choidev.choifile.archive.ArchiveExtractor
import com.choidev.choifile.archive.ArchiveFormat
import com.choidev.choifile.archive.ArchiveSecurityException
import com.choidev.choifile.archive.ArchiveWriter
import com.choidev.choifile.storage.NoMedia
import com.choidev.choifile.util.formatDuration
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** .nomedia toggling, extraction "here / elsewhere", archive cancel, video duration labels. */
class V041Test {
    @get:Rule val tmp = TemporaryFolder()

    // ---- .nomedia ----

    @Test fun nomediaCreateDetectRemove() {
        val dir = tmp.newFolder("Screenshots")
        assertFalse(NoMedia.has(dir))
        assertTrue(NoMedia.set(dir, true))
        assertTrue(NoMedia.has(dir))
        assertEquals(0L, File(dir, ".nomedia").length())
        assertFalse("already there: nothing changes", NoMedia.set(dir, true))
        assertTrue(NoMedia.set(dir, false))
        assertFalse(NoMedia.has(dir))
        assertFalse(NoMedia.set(dir, false))
    }

    @Test fun nomediaLeavesUserContentAndFoldersAlone() {
        val dir = tmp.newFolder("a")
        File(dir, ".nomedia").writeText("keep")
        assertFalse("an existing .nomedia is not truncated", NoMedia.set(dir, true))
        assertEquals("keep", File(dir, ".nomedia").readText())
        val weird = tmp.newFolder("b")
        File(weird, ".nomedia").mkdirs()
        assertFalse("a folder named .nomedia is never deleted", NoMedia.set(weird, false))
        assertTrue(File(weird, ".nomedia").isDirectory)
        try {
            NoMedia.set(File(tmp.root, "missing"), true)
            fail("not a folder")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test fun nomediaOnManyFolders() {
        val a = tmp.newFolder("x1"); val b = tmp.newFolder("x2"); val c = tmp.newFolder("x3")
        val file = File(tmp.root, "plain.txt").also { it.writeText("t") }
        NoMedia.set(a, true)
        val all = listOf(a, b, c, file)
        assertEquals(setOf(a.path), NoMedia.marked(all))
        assertTrue("not all marked yet: turn on", NoMedia.targetState(all))
        assertEquals("only the ones that changed (need a rescan)", listOf(b, c), NoMedia.setAll(all, true))
        assertEquals(setOf(a.path, b.path, c.path), NoMedia.marked(all))
        assertFalse("all marked: the toggle turns off", NoMedia.targetState(all))
        assertEquals(listOf(a, b, c), NoMedia.setAll(all, false))
        assertTrue(NoMedia.marked(all).isEmpty())
        assertFalse(File(file.path + "/.nomedia").exists())
    }

    // ---- extraction into an existing folder ----

    private fun zip(name: String, vararg entries: Pair<String, String>): File {
        val f = File(tmp.root, name)
        ZipArchiveOutputStream(f).use { z ->
            for ((n, data) in entries) {
                z.putArchiveEntry(ZipArchiveEntry(n))
                z.write(data.toByteArray())
                z.closeArchiveEntry()
            }
        }
        return f
    }

    private fun unique(dir: File, name: String): File {
        var c = File(dir, name)
        var i = 1
        while (c.exists()) c = File(dir, "$name ($i)").also { i++ }
        return c
    }

    @Test fun extractHereNeverOverwrites() {
        val z = zip("pack.zip", "readme.txt" to "new", "docs/a.txt" to "A", "docs/b.txt" to "B")
        val parent = tmp.newFolder("dest")
        File(parent, "readme.txt").writeText("old")
        val moved = ArchiveExtractor.extractMerged(z, ArchiveFormat.ZIP, parent, this::unique)
        assertEquals(setOf("docs", "readme.txt (1)"), moved.map { it.name }.toSet())
        assertEquals("old", File(parent, "readme.txt").readText())
        assertEquals("new", File(parent, "readme.txt (1)").readText())
        assertEquals("A", File(parent, "docs/a.txt").readText())
        assertTrue("no scratch folder left", parent.listFiles()!!.none { it.name.startsWith(ArchiveExtractor.SCRATCH_PREFIX) })
    }

    @Test fun extractSelectedEntriesLiftsThemToTheTarget() {
        val z = zip("pack.zip", "a/b/one.txt" to "1", "a/b/two.txt" to "2", "a/c/three.txt" to "3")
        val target = tmp.newFolder("elsewhere")
        val moved = ArchiveExtractor.extractMerged(z, ArchiveFormat.ZIP, target, this::unique, select = setOf("a/b/one.txt", "a/c"))
        assertEquals(setOf("one.txt", "c"), moved.map { it.name }.toSet())
        assertEquals("1", File(target, "one.txt").readText())
        assertEquals("3", File(target, "c/three.txt").readText())
        assertFalse(File(target, "two.txt").exists())
        assertEquals(2, target.listFiles()!!.size)
    }

    @Test fun cancelledOrHostileExtractLeavesTheFolderUntouched() {
        val z = zip("pack.zip", "x.txt" to "x", "y.txt" to "y")
        val parent = tmp.newFolder("p")
        File(parent, "mine.txt").writeText("m")
        try {
            ArchiveExtractor.extractMerged(z, ArchiveFormat.ZIP, parent, this::unique, cancelled = { true })
            fail("expected cancel")
        } catch (e: ArchiveCancelledException) {
            // expected
        }
        assertEquals(listOf("mine.txt"), parent.list()!!.toList())
        val evil = zip("evil.zip", "ok.txt" to "o", "../escape.txt" to "e")
        try {
            ArchiveExtractor.extractMerged(evil, ArchiveFormat.ZIP, parent, this::unique)
            fail("expected security exception")
        } catch (e: ArchiveSecurityException) {
            // expected
        }
        assertEquals(listOf("mine.txt"), parent.list()!!.toList())
        assertFalse(File(tmp.root, "escape.txt").exists())
    }

    @Test fun compressingCanBeCancelledCleanly() {
        val src = tmp.newFolder("src").also { File(it, "a.txt").writeText("a".repeat(1000)) }
        for (format in listOf(ArchiveFormat.ZIP, ArchiveFormat.SEVEN_Z, ArchiveFormat.TAR_GZ)) {
            val out = File(tmp.root, "out." + format.label)
            try {
                ArchiveWriter.create(format, out, listOf(src), cancelled = { true })
                fail("expected cancel for $format")
            } catch (e: ArchiveCancelledException) {
                // expected: reported as "취소했습니다", not as an error
            }
            assertFalse("partial $format output is removed", out.exists())
        }
    }

    @Test fun createThenExtractRoundTripsEveryWritableFormat() {
        val src = tmp.newFolder("photos").also { File(it, "1.jpg").writeText("one"); File(it, "sub").mkdirs(); File(it, "sub/2.jpg").writeText("two") }
        for (format in listOf(ArchiveFormat.ZIP, ArchiveFormat.SEVEN_Z, ArchiveFormat.TAR_GZ)) {
            val out = File(tmp.root, "photos." + format.label)
            ArchiveWriter.create(format, out, listOf(src))
            val dest = tmp.newFolder("rt-" + format.name)
            ArchiveExtractor.extractMerged(out, format, dest, this::unique)
            assertEquals("one", File(dest, "photos/1.jpg").readText())
            assertEquals("two", File(dest, "photos/sub/2.jpg").readText())
        }
    }

    // ---- video duration labels ----

    @Test fun durationLabels() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:07", formatDuration(7_400))
        assertEquals("12:34", formatDuration(754_000))
        assertEquals("1:02:03", formatDuration(3_723_000))
        assertEquals("", formatDuration(-1))
    }
}
