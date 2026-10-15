package com.choidev.choifile

import com.choidev.choifile.storage.FolderViewModes
import com.choidev.choifile.storage.VideoResume
import com.choidev.choifile.util.DragKind
import com.choidev.choifile.util.VideoFit
import com.choidev.choifile.util.VideoMath
import com.choidev.choifile.util.ZoomMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class V044Test {
    @get:Rule val tmp = TemporaryFolder()

    // ---- grid or list per folder ----

    @Test fun unsetFoldersOpenAsList() {
        val modes = FolderViewModes(File(tmp.root, "view_modes.json"))
        assertFalse(modes.isGrid("/sd/DCIM"))
    }

    @Test fun eachFolderKeepsItsOwnModeAcrossRestart() {
        val f = File(tmp.root, "view_modes.json")
        FolderViewModes(f).apply {
            set("/sd/DCIM", true)
            set("/sd/Download", false)
            set("/sd/Pictures", true)
            set("/sd/Pictures", false)
        }
        val again = FolderViewModes(f)
        assertTrue(again.isGrid("/sd/DCIM"))
        assertFalse(again.isGrid("/sd/Download"))
        assertFalse("the last choice wins", again.isGrid("/sd/Pictures"))
        assertFalse("a subfolder does not inherit", again.isGrid("/sd/DCIM/Camera"))
    }

    @Test fun oldestFolderIsDroppedPastTheLimitEvenAfterRestart() {
        val f = File(tmp.root, "view_modes.json")
        FolderViewModes(f, max = 2).apply { set("/a", true); set("/b", true) }
        FolderViewModes(f, max = 2).set("/c", true)
        val again = FolderViewModes(f, max = 2)
        assertFalse("oldest dropped", again.isGrid("/a"))
        assertTrue(again.isGrid("/b"))
        assertTrue(again.isGrid("/c"))
    }

    @Test fun unreadableFileStartsEmpty() {
        val f = File(tmp.root, "view_modes.json").apply { writeText("{not json") }
        val modes = FolderViewModes(f)
        assertFalse(modes.isGrid("/a"))
        modes.set("/a", true)
        assertTrue(FolderViewModes(f).isGrid("/a"))
    }

    // ---- image zoom ----

    private val w = 1000f
    private val h = 2000f

    private fun ZoomMath.View.finite() = scale.isFinite() && offsetX.isFinite() && offsetY.isFinite()

    @Test fun liftedFingerCentroidLeavesViewUntouched() {
        val zoomed = ZoomMath.zoomAround(ZoomMath.View(), w, h, 500f, 1000f, 3f)
        val after = ZoomMath.zoomAround(zoomed, w, h, Float.NaN, Float.NaN, zoomed.scale)
        assertEquals("a NaN centroid (finger lifted) must not change the view", zoomed, after)
        assertTrue(after.finite())
    }

    @Test fun nanOrInfinitePanAndScaleAreIgnored() {
        val v = ZoomMath.View(2f, 100f, -50f)
        assertEquals(v, ZoomMath.zoomAround(v, w, h, 10f, 10f, 2f, Float.NaN, 0f))
        assertEquals(v, ZoomMath.zoomAround(v, w, h, 10f, 10f, Float.POSITIVE_INFINITY))
        assertEquals("box not measured yet", v, ZoomMath.zoomAround(v, 0f, 0f, 10f, 10f, 3f))
    }

    @Test fun panIsClampedInsideTheScaledImage() {
        val v = ZoomMath.zoomAround(ZoomMath.View(), w, h, 500f, 1000f, 2f, panX = 99_999f, panY = -99_999f)
        assertEquals(2f, v.scale)
        assertEquals("half of the extra width", 500f, v.offsetX)
        assertEquals("half of the extra height", -1000f, v.offsetY)
    }

    @Test fun scaleStaysBetweenOneAndMax() {
        val big = ZoomMath.zoomAround(ZoomMath.View(), w, h, 500f, 1000f, 100f)
        assertEquals(ZoomMath.MAX_ZOOM, big.scale)
        val small = ZoomMath.zoomAround(big, w, h, 500f, 1000f, 0.01f)
        assertEquals(ZoomMath.View(1f, 0f, 0f), small)
    }

    @Test fun zoomKeepsThePointUnderTheFingers() {
        // Zooming 2x around the top-left quarter point: that image point must stay under the finger.
        val v = ZoomMath.zoomAround(ZoomMath.View(), w, h, 250f, 500f, 2f)
        val imageX = 250f - w / 2
        val imageY = 500f - h / 2
        assertEquals(250f, imageX * v.scale + w / 2 + v.offsetX, 0.001f)
        assertEquals(500f, imageY * v.scale + h / 2 + v.offsetY, 0.001f)
    }

    @Test fun manyPansWhileZoomedStayFinite() {
        var v = ZoomMath.zoomAround(ZoomMath.View(), w, h, 500f, 1000f, 4f)
        repeat(1000) { i -> v = ZoomMath.zoomAround(v, w, h, 400f + i % 7, 900f, v.scale, (i % 13 - 6).toFloat(), (i % 5 - 2).toFloat()) }
        assertTrue(v.finite())
        assertEquals(4f, v.scale)
    }

    // ---- video: picture modes ----

    @Test fun fitLetterboxesAndCropFills() {
        // 1920x1080 video on a 1080x2000 portrait screen.
        assertEquals(1080f to 607.5f, VideoMath.fitSize(VideoFit.FIT, 1920, 1080, 1f, 1080f, 2000f))
        val (cw, ch) = VideoMath.fitSize(VideoFit.CROP, 1920, 1080, 1f, 1080f, 2000f)
        assertEquals(2000f, ch)
        assertEquals(2000f * 16 / 9, cw, 0.01f)
        assertEquals(1080f to 2000f, VideoMath.fitSize(VideoFit.STRETCH, 1920, 1080, 1f, 1080f, 2000f))
    }

    @Test fun forcedRatiosAndPixelAspect() {
        assertEquals(1600f to 1200f, VideoMath.fitSize(VideoFit.RATIO_4_3, 1920, 1080, 1f, 2000f, 1200f))
        // Anamorphic DVD: 720x480 stored, pixels 32:27 wide -> shown 16:9.
        val (w, h) = VideoMath.fitSize(VideoFit.FIT, 720, 480, 32f / 27f, 1600f, 900f)
        assertEquals(1600f, w, 0.5f)
        assertEquals(900f, h, 0.5f)
    }

    @Test fun originalSizeNeverExceedsTheScreen() {
        assertEquals(640f to 360f, VideoMath.fitSize(VideoFit.ORIGINAL, 640, 360, 1f, 1080f, 1920f))
        assertEquals(1080f to 607.5f, VideoMath.fitSize(VideoFit.ORIGINAL, 3840, 2160, 1f, 1080f, 1920f))
    }

    @Test fun unknownVideoSizeFillsTheBoxAndEmptyBoxIsZero() {
        assertEquals(1080f to 1920f, VideoMath.fitSize(VideoFit.FIT, 0, 0, 1f, 1080f, 1920f))
        assertEquals(0f to 0f, VideoMath.fitSize(VideoFit.FIT, 1920, 1080, 1f, 0f, 0f))
    }

    // ---- video: gestures ----

    @Test fun dragKindDependsOnDirectionAndSide() {
        assertNull("inside the slop", VideoMath.classify(5f, 5f, 10f, 100f, 1000f))
        assertEquals(DragKind.SEEK, VideoMath.classify(30f, 10f, 10f, 100f, 1000f))
        assertEquals(DragKind.BRIGHTNESS, VideoMath.classify(2f, -30f, 10f, 100f, 1000f))
        assertEquals(DragKind.VOLUME, VideoMath.classify(2f, 30f, 10f, 800f, 1000f))
    }

    @Test fun fullSwipeSeeksNinetySecondsOrTheWholeShortClip() {
        assertEquals(90_000L, VideoMath.seekDelta(1000f, 1000f, 3_600_000))
        assertEquals(-45_000L, VideoMath.seekDelta(-500f, 1000f, 3_600_000))
        assertEquals(20_000L, VideoMath.seekDelta(1000f, 1000f, 20_000))
        assertEquals(0L, VideoMath.seekDelta(1000f, 0f, 20_000))
    }

    @Test fun verticalDragLevelsAreClamped() {
        assertEquals(1f, VideoMath.dragLevel(0.5f, -600f, 1000f), 0.0001f)
        assertEquals(0f, VideoMath.dragLevel(0.5f, 5000f, 1000f), 0.0001f)
        assertEquals("up raises", 0.75f, VideoMath.dragLevel(0.5f, -150f, 1000f), 0.0001f)
        assertEquals(2f, VideoMath.dragLevel(1f, -9999f, 1000f, VideoMath.MAX_VOLUME), 0.0001f)
        assertEquals("boost range has the same feel as 0-100%", 1.5f, VideoMath.dragLevel(1f, -300f, 1000f, VideoMath.MAX_VOLUME), 0.0001f)
    }

    @Test fun volumeAboveTheDeviceMaximumBecomesBoost() {
        assertEquals(8, VideoMath.volumeSteps(0.5f, 15))
        assertEquals(15, VideoMath.volumeSteps(1.7f, 15))
        assertEquals(0, VideoMath.boostMillibels(1f))
        assertEquals(500, VideoMath.boostMillibels(1.5f))
        assertEquals("capped at +10 dB", 1000, VideoMath.boostMillibels(9f))
    }

    // ---- video: resume, A-B loop ----

    @Test fun onlyTheMiddleOfAVideoIsWorthResuming() {
        assertFalse(VideoMath.resumable(4_999, 600_000))
        assertTrue(VideoMath.resumable(5_000, 600_000))
        assertTrue(VideoMath.resumable(589_999, 600_000))
        assertFalse("last 10 seconds count as watched", VideoMath.resumable(590_000, 600_000))
        assertTrue("unknown duration", VideoMath.resumable(60_000, -1))
    }

    @Test fun resumeStoreKeepsPositionsAndForgetsFinishedVideos() {
        val f = File(tmp.root, "video_resume.json")
        VideoResume(f).apply {
            put("/sd/Movies/a.mp4", 125_000, 600_000)
            put("/sd/Movies/b.mkv", 2_000, 600_000)
        }
        val again = VideoResume(f)
        assertEquals(125_000L, again.get("/sd/Movies/a.mp4"))
        assertNull("too early to resume", again.get("/sd/Movies/b.mkv"))
        again.put("/sd/Movies/a.mp4", 599_000, 600_000)
        assertNull("watched to the end", VideoResume(f).get("/sd/Movies/a.mp4"))
    }

    @Test fun resumeStoreDropsTheOldest() {
        val f = File(tmp.root, "video_resume.json")
        VideoResume(f, max = 2).apply { put("/a", 10_000, 0); put("/b", 10_000, 0) }
        VideoResume(f, max = 2).put("/c", 10_000, 0)
        val again = VideoResume(f, max = 2)
        assertNull(again.get("/a"))
        assertEquals(10_000L, again.get("/c"))
    }

    @Test fun abLoopSetsAThenBThenClears() {
        assertEquals(10_000L to null, VideoMath.abNext(10_000, null, null))
        assertEquals(10_000L to 20_000L, VideoMath.abNext(20_000, 10_000, null))
        assertEquals("B before A swaps", 5_000L to 10_000L, VideoMath.abNext(5_000, 10_000, null))
        assertEquals(null to null, VideoMath.abNext(30_000, 10_000, 20_000))
        assertEquals(10_000L, VideoMath.abJump(20_000, 10_000, 20_000))
        assertNull(VideoMath.abJump(15_000, 10_000, 20_000))
        assertNull("only A set", VideoMath.abJump(50_000, 10_000, null))
    }

    @Test fun remainingTimeLooksLikeIos() {
        assertEquals("-1:00", VideoMath.remaining(60_000, 120_000))
        assertEquals("-0:00", VideoMath.remaining(130_000, 120_000))
    }
}
