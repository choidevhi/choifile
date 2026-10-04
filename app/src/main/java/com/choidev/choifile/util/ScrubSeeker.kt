package com.choidev.choifile.util

/**
 * Decides which seeks the video player issues while the seek bar is dragged, so the picture itself follows the
 * finger (no thumbnail popup). Kept free of Android so it can be tested with a fake clock.
 *
 * - Only one seek is in flight at a time; while it decodes, newer finger positions just replace the target
 *   (coalescing), so a fast drag never queues up a backlog of stale seeks.
 * - While the finger moves, seeks go to the nearest key frame (fast). When it rests for [settleMs], when the
 *   drag is precise (fine scrubbing) or when it is released, the exact frame is decoded.
 * - A seek that never reports back (no video frame, decoder hiccup) is given up after [stallMs].
 *
 * The caller polls [next] once per frame, issues what it returns and calls [seekDone] when the player has shown
 * the new frame. After [release], [settled] turns true once the exact final frame is on screen.
 */
class ScrubSeeker(private val settleMs: Long = 180, private val stallMs: Long = 400) {
    data class Seek(val positionMs: Long, val exact: Boolean)

    private var target: Long? = null
    private var precise = false
    private var movedAt = 0L
    private var released = false
    private var inFlight: Seek? = null
    private var inFlightSince = 0L
    private var last: Seek? = null

    /** The finger is at [positionMs] (a new drag after a release starts over). */
    fun move(positionMs: Long, precise: Boolean, nowMs: Long) {
        if (target != positionMs) movedAt = nowMs
        target = positionMs
        this.precise = precise
        released = false
    }

    /** The finger left the bar at [positionMs]: the exact frame there is the last seek. */
    fun release(positionMs: Long, nowMs: Long) {
        move(positionMs, precise = true, nowMs = nowMs)
        released = true
    }

    /** The player finished the seek that is in flight. */
    fun seekDone() {
        inFlight = null
    }

    /** Seek to issue now, or null when one is still in flight or the target is already shown. */
    fun next(nowMs: Long): Seek? {
        val t = target ?: return null
        if (inFlight != null) {
            if (nowMs - inFlightSince < stallMs) return null
            inFlight = null
        }
        val exact = precise || released || nowMs - movedAt >= settleMs
        val l = last
        if (l != null && l.positionMs == t && (l.exact || !exact)) return null
        return Seek(t, exact).also {
            inFlight = it
            inFlightSince = nowMs
            last = it
        }
    }

    /** True after [release] once the exact final frame has been shown (or given up on). */
    fun settled(nowMs: Long): Boolean {
        if (!released) return false
        val t = target ?: return true
        val f = inFlight
        if (f != null && nowMs - inFlightSince < stallMs) return false
        return last == Seek(t, true)
    }

    /** Position to show in the time label while scrubbing or settling. */
    val targetMs: Long? get() = target

    fun reset() {
        target = null
        precise = false
        released = false
        inFlight = null
        last = null
    }
}
