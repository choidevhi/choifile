package com.choidev.choifile

import com.choidev.choifile.browser.TapAction
import com.choidev.choifile.browser.TapRouting
import com.choidev.choifile.browser.TapTarget
import com.choidev.choifile.util.ScrubSeeker
import com.choidev.choifile.util.ScrubSeeker.Seek
import com.choidev.choifile.util.SidecarSubtitles
import com.choidev.choifile.util.TrackLabels
import com.choidev.choifile.util.kindOf
import com.choidev.choifile.util.FileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V043Test {

    // ---- live scrubbing: seek coalescing ----

    @Test fun firstMoveSeeksToKeyFrameImmediately() {
        val s = ScrubSeeker()
        s.move(10_000, precise = false, nowMs = 0)
        assertEquals(Seek(10_000, exact = false), s.next(0))
    }

    @Test fun onlyOneSeekInFlightAndNewestTargetWins() {
        val s = ScrubSeeker()
        s.move(1_000, false, 0)
        assertEquals(Seek(1_000, false), s.next(0))
        s.move(2_000, false, 10)
        s.move(3_000, false, 20)
        s.move(4_000, false, 30)
        assertNull("a seek is still decoding", s.next(30))
        s.seekDone()
        assertEquals("intermediate targets are dropped", Seek(4_000, false), s.next(40))
    }

    @Test fun nothingToDoWhenTargetAlreadyShown() {
        val s = ScrubSeeker()
        s.move(5_000, false, 0)
        s.next(0)
        s.seekDone()
        assertNull(s.next(16))
    }

    @Test fun restingFingerUpgradesToExactFrame() {
        val s = ScrubSeeker(settleMs = 180)
        s.move(5_000, false, 0)
        assertEquals(Seek(5_000, false), s.next(0))
        s.seekDone()
        assertNull(s.next(100))
        assertEquals(Seek(5_000, true), s.next(180))
        s.seekDone()
        assertNull("exact frame shown, nothing more", s.next(400))
    }

    @Test fun movingAgainGoesBackToFastSeeks() {
        val s = ScrubSeeker(settleMs = 180)
        s.move(5_000, false, 0); s.next(0); s.seekDone()
        s.next(200); s.seekDone()
        s.move(6_000, false, 210)
        assertEquals(Seek(6_000, false), s.next(210))
    }

    @Test fun preciseScrubbingAlwaysSeeksExactly() {
        val s = ScrubSeeker()
        s.move(7_000, precise = true, nowMs = 0)
        assertEquals(Seek(7_000, true), s.next(0))
    }

    @Test fun stalledSeekIsGivenUp() {
        val s = ScrubSeeker(stallMs = 400)
        s.move(1_000, false, 0); s.next(0)
        s.move(2_000, false, 300)
        assertNull(s.next(399))
        assertEquals(Seek(2_000, false), s.next(400))
    }

    @Test fun releaseEndsWithExactSeekThenSettles() {
        val s = ScrubSeeker()
        s.move(1_000, false, 0); s.next(0)
        s.release(9_000, 30)
        assertFalse(s.settled(30))
        assertNull("waits for the in-flight seek", s.next(30))
        s.seekDone()
        assertEquals(Seek(9_000, true), s.next(40))
        assertFalse(s.settled(40))
        s.seekDone()
        assertTrue(s.settled(50))
        assertEquals(9_000L, s.targetMs)
    }

    @Test fun releaseOnExactFrameAlreadyShownSettlesAtOnce() {
        val s = ScrubSeeker()
        s.move(3_000, true, 0); s.next(0); s.seekDone()
        s.release(3_000, 10)
        assertNull(s.next(10))
        assertTrue(s.settled(10))
    }

    @Test fun newDragAfterReleaseIsNotSettled() {
        val s = ScrubSeeker()
        s.release(3_000, 0); s.next(0); s.seekDone()
        s.move(4_000, false, 10)
        assertFalse(s.settled(10))
        s.reset()
        assertNull(s.next(20))
        assertNull(s.targetMs)
    }

    // ---- sidecar subtitles ----

    @Test fun matchesSameBaseNameSubtitles() {
        val siblings = listOf("Movie.mkv", "movie.SRT", "Movie.ko.srt", "Movie.en.forced.vtt", "Movie.ass", "Movie2.srt", "Other.srt", "Movie.txt", "Movie.nfo")
        val m = SidecarSubtitles.match("Movie.mkv", siblings)
        assertEquals(listOf("Movie.ass", "movie.SRT", "Movie.en.forced.vtt", "Movie.ko.srt"), m.map { it.fileName })
        assertEquals("text/x-ssa", m[0].mimeType)
        assertEquals("application/x-subrip", m[1].mimeType)
        assertEquals("text/vtt", m[2].mimeType)
        assertEquals("en", m[2].language)
        assertEquals("ko", m[3].language)
        assertNull(m[0].language)
        assertEquals("ko (SRT)", m[3].label)
    }

    @Test fun baseNameWithDotsAndPrefixNamesDoNotMatch() {
        val m = SidecarSubtitles.match("my.trip.2024.mp4", listOf("my.trip.2024.srt", "my.trip.srt", "my.trip.2024.kor.ssa", "my.trip.20245.srt"))
        assertEquals(listOf("my.trip.2024.srt", "my.trip.2024.kor.ssa"), m.map { it.fileName })
        assertEquals("kor", m[1].language)
    }

    @Test fun languageTags() {
        assertTrue(SidecarSubtitles.isLanguageTag("ko"))
        assertTrue(SidecarSubtitles.isLanguageTag("eng"))
        assertTrue(SidecarSubtitles.isLanguageTag("pt-BR"))
        assertFalse(SidecarSubtitles.isLanguageTag("forced"))
        assertFalse(SidecarSubtitles.isLanguageTag("sdh"))
        assertFalse(SidecarSubtitles.isLanguageTag("1080p"))
        assertTrue(SidecarSubtitles.isSubtitle("a.VTT"))
        assertFalse(SidecarSubtitles.isSubtitle("a.sub"))
    }

    @Test fun trackLabels() {
        assertEquals("#1  English · AC3 · 5.1", TrackLabels.label(null, "en", "audio/ac3", 6, 0).replace(java.util.Locale.ENGLISH.getDisplayLanguage(java.util.Locale.getDefault()), "English"))
        assertEquals("#2  Commentary · AAC · stereo", TrackLabels.label("Commentary", "und", "audio/mp4a-latm", 2, 1))
        assertEquals("#3", TrackLabels.label(null, null, null, -1, 2))
        assertEquals("DTS", TrackLabels.codecName("audio/vnd.dts.hd"))
        assertEquals("SRT", TrackLabels.codecName("application/x-subrip"))
    }

    // ---- grid tap routing ----

    @Test fun gridCellOpensAndCornerCheckSelects() {
        assertEquals(TapAction.OPEN, TapRouting.route(TapTarget.BODY, grid = true, selecting = false))
        assertEquals(TapAction.OPEN, TapRouting.route(TapTarget.ICON, grid = true, selecting = false))
        assertEquals(TapAction.TOGGLE, TapRouting.route(TapTarget.CHECK, grid = true, selecting = false))
    }

    @Test fun anyTapTogglesWhileSelecting() {
        TapTarget.entries.forEach { t ->
            assertEquals(TapAction.TOGGLE, TapRouting.route(t, grid = true, selecting = true))
            assertEquals(TapAction.TOGGLE, TapRouting.route(t, grid = false, selecting = true))
        }
    }

    @Test fun listKeepsIconSelectsRowOpens() {
        assertEquals(TapAction.TOGGLE, TapRouting.route(TapTarget.ICON, grid = false, selecting = false))
        assertEquals(TapAction.OPEN, TapRouting.route(TapTarget.BODY, grid = false, selecting = false))
    }

    // ---- wider video containers ----

    @Test fun newContainersOpenInThePlayer() {
        listOf("a.ts", "a.flv", "a.ogv", "a.ogg", "a.MPG", "a.mpeg", "a.3g2", "a.f4v", "a.mkv", "a.webm", "a.avi", "a.mov", "a.3gp")
            .forEach { assertEquals(it, FileKind.VIDEO, kindOf(File(it))) }
        assertEquals(FileKind.OTHER, kindOf(File("a.wmv")))
    }
}
