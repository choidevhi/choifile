package com.choidev.choifile.browser

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Bounds of one visible list/grid cell in the list's own pixel coordinates. */
data class CellBounds(val index: Int, val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Long-press-and-drag range selection (as in Google Photos). Items are addressed by their position in the
 * list; a grid is the same list laid out row by row, so a range in a grid runs in reading order.
 *
 * The drag starts at [anchor] on top of the selection that existed before ([base]). When the anchor was not
 * selected the range is added; when it was, the range is removed. Moving the finger back shrinks the range,
 * and items outside it return to how they were in [base].
 */
class DragSelection(private val keys: List<String>, private val base: Set<String>, val anchor: Int) {
    val adding: Boolean = keys.getOrNull(anchor)?.let { it !in base } ?: true

    fun selectionAt(current: Int): Set<String> {
        if (keys.isEmpty() || anchor !in keys.indices) return base
        val range = rangeOf(anchor, current.coerceIn(0, keys.size - 1))
        val picked = keys.subList(range.first, range.last + 1)
        return if (adding) base + picked else base - picked.toSet()
    }

    companion object {
        /** Inclusive index range between the anchor and the current item, in either direction. */
        fun rangeOf(anchor: Int, current: Int): IntRange = min(anchor, current)..max(anchor, current)

        /**
         * The cell under (x, y). Between cells (spacing, padding) the closest cell in the same row wins, then the
         * closest cell overall, so a finger in a gap never drops the range.
         */
        fun indexAt(cells: List<CellBounds>, x: Float, y: Float): Int? {
            if (cells.isEmpty()) return null
            cells.firstOrNull { x >= it.left && x < it.right && y >= it.top && y < it.bottom }?.let { return it.index }
            val row = cells.filter { y >= it.top && y < it.bottom }
            if (row.isNotEmpty()) return row.minBy { distance(it.left, it.right, x) }.index
            return cells.minBy { distance(it.top, it.bottom, y) * 4 + distance(it.left, it.right, x) }.index
        }

        /**
         * Auto-scroll speed in pixels per frame: negative near the top, positive near the bottom, zero elsewhere.
         * It grows as the finger gets closer to (or past) the edge, up to [maxStep].
         */
        fun autoScrollStep(y: Float, top: Float, bottom: Float, zone: Float, maxStep: Float): Float = when {
            zone <= 0f || bottom <= top -> 0f
            y < top + zone -> -maxStep * ((top + zone - y) / zone).coerceAtMost(1f)
            y > bottom - zone -> maxStep * ((y - (bottom - zone)) / zone).coerceAtMost(1f)
            else -> 0f
        }

        private fun distance(start: Float, end: Float, v: Float) = when {
            v < start -> start - v
            v > end -> v - end
            else -> 0f
        }.let(::abs)
    }
}
