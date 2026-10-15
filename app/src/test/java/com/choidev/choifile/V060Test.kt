package com.choidev.choifile

import com.choidev.choifile.browser.DateRange
import com.choidev.choifile.browser.FileFilter
import com.choidev.choifile.browser.NameQuery
import com.choidev.choifile.browser.SizeRange
import com.choidev.choifile.browser.TypeGroup
import com.choidev.choifile.browser.typeGroupOf
import com.choidev.choifile.storage.FileItem
import com.choidev.choifile.storage.SortKey
import com.choidev.choifile.storage.SortSpec
import com.choidev.choifile.storage.sortItems
import com.choidev.choifile.util.FileKind
import com.choidev.choifile.util.TextChunks
import com.choidev.choifile.util.kindOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class V060Test {
    @get:Rule val tmp = TemporaryFolder()

    private val now = 1_000_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun file(name: String, size: Long = 0, ageDays: Int = 0) = FileItem("/sd/$name", name, false, size, now - ageDays * day, false)
    private fun dir(name: String, ageDays: Int = 0) = FileItem("/sd/$name", name, true, 0, now - ageDays * day, false)

    private val items = listOf(
        file("song.mp3", 5_000_000, 2),
        dir("Photos", 40),
        file("IMG_2024.jpg", 3_000_000, 0),
        file("file10.txt", 10, 400),
        file("file2.txt", 20, 1),
        file("movie.mkv", 2_000_000_000, 10),
        dir("archive", 3),
        file("pack.zip", 500_000, 5),
        file("app.apk", 40_000_000, 5),
    )

    // ---- sorting ----

    @Test fun namesSortNaturallyWithFoldersFirst() {
        val names = sortItems(items, SortSpec(SortKey.NAME)).map { it.name }
        assertEquals(listOf("archive", "Photos", "app.apk", "file2.txt", "file10.txt", "IMG_2024.jpg", "movie.mkv", "pack.zip", "song.mp3"), names)
    }

    @Test fun foldersCanMixWithFiles() {
        val names = sortItems(items, SortSpec(SortKey.NAME, foldersFirst = false)).map { it.name }
        assertEquals(listOf("app.apk", "archive", "file2.txt", "file10.txt", "IMG_2024.jpg", "movie.mkv", "pack.zip", "Photos", "song.mp3"), names)
    }

    @Test fun dateSizeAndTypeOrders() {
        assertEquals("newest file first", "IMG_2024.jpg", sortItems(items, SortSpec(SortKey.DATE, ascending = false)).first { !it.isDirectory }.name)
        assertEquals("largest first", "movie.mkv", sortItems(items, SortSpec(SortKey.SIZE, ascending = false)).first { !it.isDirectory }.name)
        val byType = sortItems(items, SortSpec(SortKey.TYPE)).filter { !it.isDirectory }.map { it.name.substringAfterLast('.') }
        assertEquals(listOf("apk", "jpg", "mkv", "mp3", "txt", "txt", "zip"), byType)
    }

    @Test fun sortSpecPacksIntoOneNumber() {
        SortKey.entries.forEach { k ->
            listOf(true, false).forEach { asc ->
                listOf(true, false).forEach { ff ->
                    val spec = SortSpec(k, asc, ff)
                    assertEquals(spec, SortSpec.unpack(spec.pack()))
                }
            }
        }
        assertEquals(null, SortSpec.unpack(999))
    }

    // ---- filtering ----

    @Test fun plainTextAndWildcards() {
        assertTrue(NameQuery("img").matches("IMG_2024.jpg"))
        assertTrue(NameQuery("*.jpg").matches("IMG_2024.jpg"))
        assertFalse("a wildcard matches the whole name", NameQuery("*.jp").matches("IMG_2024.jpg"))
        assertTrue(NameQuery("file?.txt").matches("file2.txt"))
        assertFalse(NameQuery("file?.txt").matches("file10.txt"))
        assertTrue("regex characters are literal", NameQuery("a+b").matches("a+b (1).txt"))
    }

    @Test fun koreanInitialConsonants() {
        assertTrue(NameQuery("ㅎㄱ").matches("휴가 사진"))
        assertTrue(NameQuery("ㅎㄱ").matches("한글문서.hwp"))
        assertFalse(NameQuery("ㅎㄱ").matches("가족"))
        assertEquals("ㅎㄱㅁㅅ", NameQuery.initialsOf("한글 문서"))
    }

    @Test fun kindsDateAndSize() {
        val docs = FileFilter(types = setOf(TypeGroup.DOCUMENT)).apply(items, now).map { it.name }
        assertEquals(listOf("file10.txt", "file2.txt"), docs)
        val recent = FileFilter(date = DateRange.WEEK).apply(items, now).map { it.name }.toSet()
        assertEquals(setOf("song.mp3", "IMG_2024.jpg", "file2.txt", "archive", "pack.zip", "app.apk"), recent)
        val huge = FileFilter(size = SizeRange.HUGE).apply(items, now).map { it.name }
        assertEquals("size filters hide folders", listOf("movie.mkv"), huge)
        val combo = FileFilter(text = "file", date = DateRange.WEEK).apply(items, now).map { it.name }
        assertEquals(listOf("file2.txt"), combo)
        assertEquals(3, FileFilter(text = "a", types = setOf(TypeGroup.IMAGE), size = SizeRange.SMALL).count)
        assertEquals("no filter keeps everything", items, FileFilter().apply(items, now))
    }

    @Test fun typeGroups() {
        assertEquals(TypeGroup.FOLDER, typeGroupOf(dir("x")))
        assertEquals(TypeGroup.APK, typeGroupOf(file("a.APK")))
        assertEquals(TypeGroup.ARCHIVE, typeGroupOf(file("a.7z")))
        assertEquals(TypeGroup.AUDIO, typeGroupOf(file("a.flac")))
        assertEquals(TypeGroup.DOCUMENT, typeGroupOf(file("report.hwp")))
        assertEquals(TypeGroup.OTHER, typeGroupOf(file("blob.bin")))
        assertEquals("ogg is music now, ogv stays video", FileKind.AUDIO, kindOf(File("a.ogg")))
        assertEquals(FileKind.VIDEO, kindOf(File("a.ogv")))
    }

    // ---- big text files ----

    private fun bigText(lines: Int) = (1..lines).joinToString("\n") { "줄 $it: 한글과 English가 섞인 문장입니다." }

    @Test fun chunksCutAtLineBreaksAndRebuildTheText() {
        val text = bigText(5_000)
        val f = File(tmp.root, "big.txt").apply { writeText(text, Charsets.UTF_8) }
        val chunks = TextChunks.index(f, target = 4096)
        assertTrue("several chunks", chunks.size > 10)
        assertEquals("UTF-8", chunks.charset)
        assertEquals(text, (0 until chunks.size).joinToString("\n") { chunks.read(it) })
    }

    @Test fun cp949FilesDecodePerChunk() {
        val text = bigText(3_000)
        val f = File(tmp.root, "old.txt").apply { writeBytes(text.toByteArray(charset("x-windows-949"))) }
        val chunks = TextChunks.index(f, target = 2048)
        assertEquals("x-windows-949", chunks.charset)
        assertEquals(text, (0 until chunks.size).joinToString("\n") { chunks.read(it) })
    }

    @Test fun smallFileIsOneChunkAndTheBomIsDropped() {
        val f = File(tmp.root, "bom.txt").apply { writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "안녕\n".toByteArray()) }
        val chunks = TextChunks.index(f)
        assertEquals(1, chunks.size)
        assertEquals("안녕", chunks.read(0))
    }
}
