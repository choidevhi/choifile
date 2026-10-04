package com.choidev.choifile.util

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Seek-bar arithmetic for the video player, kept free of Android so it can be tested.
 *
 * Dragging along the bar moves the position proportionally (full bar width = whole video). Moving the finger
 * vertically away from the bar slows the scrub down, like the iOS player: the further away, the finer.
 * "Fine mode" forces the slowest speed regardless of the finger's height.
 */
object ScrubMath {
    /** Speed factor for a finger [awayDp] above or below the bar. */
    fun factor(awayDp: Float, fine: Boolean = false): Float {
        if (fine) return 0.05f
        val d = abs(awayDp)
        return when {
            d < 48f -> 1f
            d < 120f -> 0.5f
            d < 200f -> 0.25f
            else -> 0.1f
        }
    }

    /** Label for the current speed, shown while dragging ("1x", "1/2", "1/4", "1/10", "1/20"). */
    fun factorLabel(f: Float): String = if (f >= 1f) "1x" else "1/" + (1f / f).roundToLong()

    /** Moves [positionMs] by a horizontal finger movement of [dxPx] on a bar [widthPx] wide. */
    fun advance(positionMs: Long, dxPx: Float, widthPx: Float, durationMs: Long, factor: Float): Long {
        if (widthPx <= 0f || durationMs <= 0) return positionMs.coerceAtLeast(0)
        val delta = (dxPx / widthPx * durationMs * factor).roundToLong()
        return (positionMs + delta).coerceIn(0, durationMs)
    }

    /** Position under the finger when the bar is first touched (absolute, not relative). */
    fun positionAt(xPx: Float, widthPx: Float, durationMs: Long): Long {
        if (widthPx <= 0f || durationMs <= 0) return 0
        return ((xPx / widthPx).coerceIn(0f, 1f) * durationMs).roundToLong()
    }

    /** One video frame in ms for [fps] (falls back to 30 fps when the stream does not say). */
    fun frameMs(fps: Float): Long = if (fps.isNaN() || fps <= 1f || fps > 480f) 33L else (1000f / fps).roundToLong().coerceAtLeast(1)

    /** "1:05", "1:02:03", with optional tenths ("1:05.3") for fine seeking. */
    fun format(ms: Long, tenths: Boolean = false): String {
        val t = ms.coerceAtLeast(0)
        val totalSec = t / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        val base = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        return if (tenths) base + "." + (t % 1000) / 100 else base
    }
}
