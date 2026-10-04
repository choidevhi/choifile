package com.choidev.choifile

import com.choidev.choifile.archive.ArchiveFormat
import com.choidev.choifile.archive.archiveBaseName
import com.choidev.choifile.comic.ComicLayout
import com.choidev.choifile.comic.comicPageOrder
import com.choidev.choifile.comic.isComicImage
import com.choidev.choifile.security.GracePeriod
import com.choidev.choifile.util.NaturalOrder
import com.choidev.choifile.util.ScrubMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerLogicTest {

    // ---- grace period ----

    private var now = 1_000L
    private fun grace(seconds: Int) = GracePeriod(seconds * 1000L) { now }

    @Test fun returningWithinGraceDoesNotAskAgain() {
        val g = grace(10)
        g.onBackground()
        now += 9_999
        assertFalse(g.onForeground())
    }

    @Test fun returningAtOrAfterGraceAsksAgain() {
        val g = grace(10)
        g.onBackground()
        now += 10_000
        assertTrue("exactly the grace period counts as expired", g.onForeground())
        g.onBackground()
        now += 60_000
        assertTrue(g.onForeground())
    }

    @Test fun zeroGraceAlwaysAsks() {
        val g = grace(0)
        g.onBackground()
        assertTrue(g.onForeground())
    }

    @Test fun foregroundWithoutBackgroundIsNotAnAbsence() {
        val g = grace(0)
        assertFalse("first start or a repeated onStart", g.onForeground())
        g.onBackground()
        now += 100_000
        assertTrue(g.onForeground())
        assertFalse("the absence is consumed once", g.onForeground())
    }

    @Test fun secondBackgroundKeepsTheFirstTimestamp() {
        val g = grace(10)
        g.onBackground()
        now += 8_000
        g.onBackground() // e.g. another onStop without a start in between
        now += 3_000
        assertTrue("11 s since the app really left", g.onForeground())
    }

    @Test fun ownAuthenticationPromptIsIgnored() {
        val g = grace(0)
        g.suspended = true // the PIN screen is another activity: onStop/onStart happen during the prompt
        g.onBackground()
        now += 30_000
        assertFalse(g.onForeground())
        g.suspended = false
        assertFalse("nothing was recorded while suspended", g.onForeground())
    }

    @Test fun graceChangeAppliesToPendingAbsenceAndClockJumpsExpire() {
        val g = grace(60)
        g.onBackground()
        now += 20_000
        g.graceMs = 10_000
        assertTrue(g.onForeground())
        g.onBackground()
        now -= 5_000 // clock went backwards: be safe and ask
        assertTrue(g.onForeground())
        g.onBackground()
        g.reset()
        now += 999_999
        assertFalse("reset forgets the absence", g.onForeground())
    }

    // ---- natural order ----

    @Test fun numbersCompareByValue() {
        val names = listOf("10.jpg", "2.jpg", "1.jpg", "100.jpg", "20.jpg")
        assertEquals(listOf("1.jpg", "2.jpg", "10.jpg", "20.jpg", "100.jpg"), names.sortedWith(NaturalOrder))
    }

    @Test fun mixedTextAndNumbers() {
        val names = listOf("ch10 p2.png", "ch2 p10.png", "ch2 p9.png", "Ch1 p1.png", "ch10 p10.png")
        assertEquals(listOf("Ch1 p1.png", "ch2 p9.png", "ch2 p10.png", "ch10 p2.png", "ch10 p10.png"), names.sortedWith(NaturalOrder))
    }

    @Test fun leadingZerosAndHugeNumbers() {
        assertTrue(NaturalOrder.compare("001.jpg", "2.jpg") < 0)
        assertTrue("equal value: fewer zeros first", NaturalOrder.compare("1.jpg", "01.jpg") < 0)
        assertTrue(NaturalOrder.compare("99999999999999999999999.jpg", "100000000000000000000000.jpg") < 0)
        assertEquals(0, NaturalOrder.compare("a1", "a1"))
        assertTrue("prefix first", NaturalOrder.compare("page", "page1") < 0)
        assertTrue("case-insensitive, then stable", NaturalOrder.compare("A.jpg", "a.jpg") != 0)
        assertTrue(NaturalOrder.compare("a.jpg", "B.jpg") < 0)
    }

    @Test fun naturalOrderIsConsistent() {
        val names = listOf("x2", "x10", "x02", "X2", "x", "x1a", "x1b", "10", "9", "", "a")
        val sorted = names.sortedWith(NaturalOrder)
        for (i in sorted.indices) for (j in sorted.indices) {
            val c = NaturalOrder.compare(sorted[i], sorted[j])
            assertEquals("antisymmetric ${sorted[i]} vs ${sorted[j]}", Integer.signum(c), -Integer.signum(NaturalOrder.compare(sorted[j], sorted[i])))
            if (i < j) assertTrue("${sorted[i]} <= ${sorted[j]}", c <= 0)
        }
    }

    // ---- comic layout ----

    @Test fun comicPagesAreImagesInNaturalOrder() {
        val entries = listOf("vol/10.jpg", "vol/2.jpg", "vol/1.webp", "vol/notes.txt", "__MACOSX/vol/._1.jpg", "vol/.hidden.png", "vol/cover.PNG")
        assertEquals(listOf("vol/1.webp", "vol/2.jpg", "vol/10.jpg", "vol/cover.PNG"), comicPageOrder(entries))
        assertTrue(isComicImage("a/B.JPEG"))
        assertFalse(isComicImage("a/b.pdf"))
    }

    @Test fun normalPagesAreOneStripTallPagesAreSplit() {
        assertEquals(1, ComicLayout.slices(0, 800, 1200).size)
        val tall = ComicLayout.slices(3, 800, 12_000)
        assertTrue(tall.size > 1)
        assertEquals(0, tall.first().top)
        assertEquals(12_000, tall.last().bottom)
        tall.zipWithNext { a, b -> assertEquals("strips touch, no gap or overlap", a.bottom, b.top) }
        assertTrue(tall.all { it.page == 3 && it.width == 800 })
        assertEquals(tall.indices.toList(), tall.map { it.part })
        assertTrue("no strip taller than ~1.5 widths plus a folded tail", tall.all { it.height <= 800 * 1.5 + 800 * 1.5 / 4 })
    }

    @Test fun tinyTailIsFoldedIntoLastStrip() {
        val s = ComicLayout.slices(0, 1000, 1500 * 3 + 100) // 100 px left over < strip / 4
        assertEquals(3, s.size)
        assertEquals(1600, s.last().height)
    }

    @Test fun undecodablePageGetsAPlaceholder() {
        val s = ComicLayout.slices(5, 0, 0)
        assertEquals(1, s.size)
        assertEquals(0, s[0].width)
        assertEquals(1f, s[0].aspect)
    }

    @Test fun sampleSizeKeepsAtLeastTargetWidth() {
        assertEquals(1, ComicLayout.sampleSize(1080, 1080))
        assertEquals(1, ComicLayout.sampleSize(2000, 1080))
        assertEquals(2, ComicLayout.sampleSize(2160, 1080))
        assertEquals(4, ComicLayout.sampleSize(5000, 1080))
        assertEquals(1, ComicLayout.sampleSize(0, 1080))
    }

    @Test fun resumeReanchorsWhenPagesChanged() {
        val anchors = listOf("1.jpg#0", "2.jpg#0", "2.jpg#1", "3.jpg#0")
        assertEquals(2, ComicLayout.resolve(2, "2.jpg#1", anchors))
        assertEquals("page inserted before: follow the anchor", 3, ComicLayout.resolve(2, "3.jpg#0", anchors))
        assertEquals("anchor gone: clamp the index", 3, ComicLayout.resolve(9, "zzz#0", anchors))
        assertEquals(1, ComicLayout.resolve(1, null, anchors))
        assertEquals(0, ComicLayout.resolve(5, "x", emptyList()))
    }

    @Test fun cbzIsAZipArchive() {
        assertEquals(ArchiveFormat.ZIP, ArchiveFormat.detect("One Piece 01.CBZ"))
        assertEquals("One Piece 01", archiveBaseName("One Piece 01.cbz"))
    }

    // ---- video scrubbing ----

    @Test fun scrubSlowsDownAwayFromTheBar() {
        assertEquals(1f, ScrubMath.factor(0f))
        assertEquals(1f, ScrubMath.factor(-40f))
        assertEquals(0.5f, ScrubMath.factor(-60f))
        assertEquals(0.25f, ScrubMath.factor(150f))
        assertEquals(0.1f, ScrubMath.factor(-400f))
        assertEquals(0.05f, ScrubMath.factor(0f, fine = true))
        assertEquals("1/4", ScrubMath.factorLabel(0.25f))
        assertEquals("1x", ScrubMath.factorLabel(1f))
    }

    @Test fun scrubAdvanceIsProportionalAndClamped() {
        // 1000 px bar, 100 s video: 10 px = 1 s at full speed, 0.1 s at 1/10.
        assertEquals(51_000, ScrubMath.advance(50_000, 10f, 1000f, 100_000, 1f))
        assertEquals(50_100, ScrubMath.advance(50_000, 10f, 1000f, 100_000, 0.1f))
        assertEquals(0, ScrubMath.advance(500, -100f, 1000f, 100_000, 1f))
        assertEquals(100_000, ScrubMath.advance(99_000, 100f, 1000f, 100_000, 1f))
        assertEquals(25_000, ScrubMath.positionAt(250f, 1000f, 100_000))
        assertEquals(100_000, ScrubMath.positionAt(2000f, 1000f, 100_000))
    }

    @Test fun frameStepAndFormatting() {
        assertEquals(33, ScrubMath.frameMs(30f))
        assertEquals(17, ScrubMath.frameMs(60f))
        assertEquals(42, ScrubMath.frameMs(23.976f))
        assertEquals("unknown rate falls back to 30 fps", 33, ScrubMath.frameMs(Float.NaN))
        assertEquals("1:05", ScrubMath.format(65_400))
        assertEquals("1:05.4", ScrubMath.format(65_400, tenths = true))
        assertEquals("1:02:03", ScrubMath.format(3_723_000))
        assertEquals(ScrubMath.thumbBucket(10_050, 60_000), ScrubMath.thumbBucket(10_090, 60_000))
    }
}
