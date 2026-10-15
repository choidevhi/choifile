package com.choidev.choifile

import com.choidev.choifile.storage.KeyedLongStore
import com.choidev.choifile.storage.VideoResume
import com.choidev.choifile.util.CueTimeline
import com.choidev.choifile.util.SidecarSubtitles
import com.choidev.choifile.util.SmiParser
import com.choidev.choifile.util.SubtitleText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class V051Test {
    @get:Rule val tmp = TemporaryFolder()

    // ---- subtitle encodings ----

    private val korean = "안녕하세요, 자막입니다"

    @Test fun utf8IsRecognisedWithAndWithoutBom() {
        assertEquals("UTF-8", SubtitleText.detect(korean.toByteArray(Charsets.UTF_8)))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + korean.toByteArray(Charsets.UTF_8)
        assertEquals("UTF-8", SubtitleText.detect(bom))
        assertEquals("the BOM is dropped", korean, String(SubtitleText.toUtf8(bom, "UTF-8"), Charsets.UTF_8))
    }

    @Test fun oldKoreanFilesAreReadAsCp949() {
        val cp949 = korean.toByteArray(charset("x-windows-949"))
        assertEquals("x-windows-949", SubtitleText.detect(cp949))
        assertEquals(korean, String(SubtitleText.toUtf8(cp949, "x-windows-949"), Charsets.UTF_8))
    }

    @Test fun utf16BomsAndUnknownCharsets() {
        assertEquals("UTF-16LE", SubtitleText.detect(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41, 0)))
        assertEquals("UTF-16BE", SubtitleText.detect(byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0, 0x41)))
        assertArrayEquals("unknown charset falls back to UTF-8", "abc".toByteArray(), SubtitleText.toUtf8("abc".toByteArray(), "no-such-charset"))
        assertTrue("every menu choice exists here", SubtitleText.CHOICES.all { runCatching { charset(it) }.isSuccess })
    }

    // ---- cue lookup with delay ----

    private val timeline = CueTimeline(
        listOf(
            CueTimeline.Entry(1_000_000L, 3_000_000L, "a"),
            CueTimeline.Entry(2_000_000L, 2_500_000L, "b"),
            CueTimeline.Entry(10_000_000L, 40_000_000L, "long"),
            CueTimeline.Entry(50_000_000L, 51_000_000L, "c"),
        ),
    )

    @Test fun linesOnScreenIncludingOverlaps() {
        assertEquals(emptyList<String>(), timeline.at(500_000))
        assertEquals(listOf("a"), timeline.at(1_000_000))
        assertEquals(listOf("a", "b"), timeline.at(2_200_000))
        assertEquals("end is exclusive", emptyList<String>(), timeline.at(3_000_000))
        assertEquals("a long line is found far after its start", listOf("long"), timeline.at(39_000_000))
    }

    @Test fun delayShiftsLines() {
        assertEquals("+2s: line a shows 2s later", emptyList<String>(), timeline.at(1_500_000, 2_000_000))
        assertEquals(listOf("a"), timeline.at(3_500_000, 2_000_000))
        assertEquals("-1s: line c shows 1s earlier", listOf("c"), timeline.at(49_500_000, -1_000_000))
        assertEquals(0, CueTimeline<String>(emptyList()).at(1).size)
    }

    // ---- SAMI ----

    private val smi = """
        <SAMI><HEAD><STYLE>.KRCC { Name:Korean; lang:ko-KR; } .ENCC { Name:English; lang:en-US; }</STYLE></HEAD>
        <BODY>
        <SYNC Start=1000><P Class=ENCC>Hello
        <SYNC Start=1000><P Class=KRCC>안녕<br>하세요
        <SYNC Start=3500><P Class=KRCC>&nbsp;
        <SYNC Start=4000><P Class=KRCC>두 번째 &amp; 줄
        <SYNC Start=6000><P Class=KRCC><font color="#ffff00">세 번째</font>
        </BODY></SAMI>
    """.trimIndent()

    @Test fun samiPicksKoreanAndEndsLinesAtTheNextSync() {
        val lines = SmiParser.parse(smi)
        assertEquals(listOf("안녕\n하세요", "두 번째 & 줄", "세 번째"), lines.map { it.text })
        assertEquals(1000L, lines[0].startMs)
        assertEquals("the &nbsp; block ends the first line", 3500L, lines[0].endMs)
        assertEquals(6000L, lines[1].endMs)
        assertEquals("the last line gets a few seconds", 11_000L, lines[2].endMs)
    }

    @Test fun samiWithoutClassesAndBrokenInput() {
        val plain = SmiParser.parse("<SYNC Start=500>one<SYNC Start=900>two")
        assertEquals(listOf("one", "two"), plain.map { it.text })
        assertEquals(900L, plain[0].endMs)
        assertEquals(0, SmiParser.parse("not a subtitle").size)
        assertTrue(SidecarSubtitles.isSubtitle("movie.SMI"))
    }

    // ---- per-video numbers ----

    @Test fun keyedStoreKeepsNegativeDelaysAndDropsTheOldest() {
        val f = File(tmp.root, "subtitle_delays.json")
        KeyedLongStore(f, max = 2).apply { put("/a", -1500); put("/b", 300) }
        KeyedLongStore(f, max = 2).put("/c", 100)
        val again = KeyedLongStore(f, max = 2)
        assertNull(again.get("/a"))
        assertEquals(300L, again.get("/b"))
        again.remove("/b")
        assertNull(KeyedLongStore(f, max = 2).get("/b"))
        assertEquals(100L, KeyedLongStore(f, max = 2).get("/c"))
    }

    @Test fun resumeStoreStillReadsWhatTheOldVersionWrote() {
        // v0.4.4 wrote the same [[path, ms], ...] array.
        val f = File(tmp.root, "video_resume.json").apply { writeText("""[["/sd/a.mp4",125000]]""") }
        assertEquals(125_000L, VideoResume(f).get("/sd/a.mp4"))
    }
}
