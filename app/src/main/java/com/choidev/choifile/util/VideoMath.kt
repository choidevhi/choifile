package com.choidev.choifile.util

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** How the picture fills the screen. */
enum class VideoFit { FIT, CROP, STRETCH, RATIO_16_9, RATIO_4_3, ORIGINAL }

/** What happens when a video ends: play the next one in the folder, repeat it, repeat the folder, or stop. */
enum class VideoEnd { NEXT, REPEAT_ONE, REPEAT_ALL, STOP }

/** What a one-finger drag on the video controls, decided once the finger has moved past the touch slop. */
enum class DragKind { SEEK, BRIGHTNESS, VOLUME }

/** Video player arithmetic, kept free of Android so it can be tested. */
object VideoMath {

    /**
     * Size in px of the picture for [fit], for a video of [videoW] x [videoH] pixels with pixel aspect [par], on a
     * [boxW] x [boxH] screen. CROP fills the screen and cuts the overflow; ORIGINAL is one video pixel per screen
     * pixel, shrunk only when it would not fit.
     */
    fun fitSize(fit: VideoFit, videoW: Int, videoH: Int, par: Float, boxW: Float, boxH: Float): Pair<Float, Float> {
        if (boxW <= 0f || boxH <= 0f) return 0f to 0f
        if (videoW <= 0 || videoH <= 0) return boxW to boxH
        val aspect = when (fit) {
            VideoFit.RATIO_16_9 -> 16f / 9f
            VideoFit.RATIO_4_3 -> 4f / 3f
            else -> videoW * (if (par > 0f) par else 1f) / videoH
        }
        val boxAspect = boxW / boxH
        return when (fit) {
            VideoFit.STRETCH -> boxW to boxH
            VideoFit.CROP -> if (aspect > boxAspect) boxH * aspect to boxH else boxW to boxW / aspect
            VideoFit.ORIGINAL -> {
                val w = videoW * (if (par > 0f) par else 1f)
                val h = videoH.toFloat()
                val shrink = min(1f, min(boxW / w, boxH / h))
                w * shrink to h * shrink
            }
            else -> if (aspect > boxAspect) boxW to boxW / aspect else boxH * aspect to boxH
        }
    }

    /**
     * Which control a drag starting at [startX] on a [width] wide screen drives, given how far it has moved.
     * Null while the finger is still inside [slop]. Mostly horizontal = seek; vertical on the left half =
     * brightness, on the right half = volume (the MX Player layout).
     */
    fun classify(dx: Float, dy: Float, slop: Float, startX: Float, width: Float): DragKind? {
        if (abs(dx) < slop && abs(dy) < slop) return null
        if (abs(dx) > abs(dy)) return DragKind.SEEK
        return if (startX < width / 2f) DragKind.BRIGHTNESS else DragKind.VOLUME
    }

    /**
     * Seek offset in ms for a horizontal drag of [dxPx] on a [widthPx] wide screen. A full-width swipe covers
     * 90 seconds, or the whole video when it is shorter, so short clips stay controllable and long films precise.
     */
    fun seekDelta(dxPx: Float, widthPx: Float, durationMs: Long): Long {
        if (widthPx <= 0f || durationMs <= 0) return 0
        val span = min(durationMs, 90_000L)
        return (dxPx / widthPx * span).roundToLong()
    }

    /**
     * New level after a vertical drag of [dyPx] (down is positive) on a [heightPx] screen. 60% of the height moves
     * the level by 1 (0 to 100%) whatever [maxLevel] is, so the volume boost above 100% needs a further drag.
     */
    fun dragLevel(start: Float, dyPx: Float, heightPx: Float, maxLevel: Float = 1f): Float {
        if (heightPx <= 0f) return start
        return (start - dyPx / (heightPx * 0.6f)).coerceIn(0f, maxLevel)
    }

    /** Max of the volume level: 1 = the device maximum, above that is the software boost. */
    const val MAX_VOLUME = 2f

    /** System volume step for a [level] in 0..[MAX_VOLUME] on a device with [maxSteps] volume steps. */
    fun volumeSteps(level: Float, maxSteps: Int): Int = (min(level, 1f) * maxSteps).roundToInt().coerceIn(0, maxSteps)

    /** LoudnessEnhancer gain in millibels for the part of [level] above the device maximum (200% = +10 dB). */
    fun boostMillibels(level: Float): Int = ((level - 1f).coerceIn(0f, 1f) * 1000).roundToInt()

    /** Percent shown while changing volume or brightness. */
    fun percent(level: Float): Int = (level * 100).roundToInt()

    /** Remembered position worth resuming: not the first 5 seconds, not the last 10 (that counts as watched). */
    fun resumable(positionMs: Long, durationMs: Long): Boolean =
        positionMs >= 5_000 && (durationMs <= 0 || positionMs < durationMs - 10_000)

    /** Where an A-B loop sends playback: back to [a] once [positionMs] reaches [b]; null when nothing to do. */
    fun abJump(positionMs: Long, a: Long?, b: Long?): Long? =
        if (a != null && b != null && b > a && positionMs >= b) a else null

    /** Points of an A-B loop after the A-B button: set A, then B (after A, else swap), then clear. */
    fun abNext(positionMs: Long, a: Long?, b: Long?): Pair<Long?, Long?> = when {
        a == null -> positionMs to null
        b == null -> if (positionMs > a) a to positionMs else if (positionMs < a) positionMs to a else a to null
        else -> null to null
    }

    /** Remaining time label in the iOS style: "-1:23". */
    fun remaining(positionMs: Long, durationMs: Long): String = "-" + ScrubMath.format(max(0, durationMs - positionMs))
}
