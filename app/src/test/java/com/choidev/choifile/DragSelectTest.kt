package com.choidev.choifile

import com.choidev.choifile.browser.CellBounds
import com.choidev.choifile.browser.DragSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DragSelectTest {
    private val keys = (0 until 12).map { "f$it" }
    private fun set(vararg i: Int) = i.map { "f$it" }.toSet()

    @Test fun forwardRange() {
        val d = DragSelection(keys, emptySet(), anchor = 2)
        assertTrue(d.adding)
        assertEquals(set(2), d.selectionAt(2))
        assertEquals(set(2, 3, 4, 5), d.selectionAt(5))
    }

    @Test fun backwardRange() {
        val d = DragSelection(keys, emptySet(), anchor = 6)
        assertEquals(set(3, 4, 5, 6), d.selectionAt(3))
        assertEquals(3..6, DragSelection.rangeOf(6, 3))
    }

    @Test fun draggingBackShrinksAndCrossingTheAnchorFlips() {
        val d = DragSelection(keys, emptySet(), anchor = 4)
        assertEquals(set(4, 5, 6, 7, 8), d.selectionAt(8))
        assertEquals(set(4, 5, 6), d.selectionAt(6))
        assertEquals(set(4), d.selectionAt(4))
        assertEquals(set(2, 3, 4), d.selectionAt(2))
    }

    @Test fun existingSelectionIsKeptAndRestoredWhenShrinking() {
        // Already in selection mode with f0 and f10 selected; long-press f3 and drag adds a range on top.
        val base = set(0, 10)
        val d = DragSelection(keys, base, anchor = 3)
        assertEquals(set(0, 3, 4, 5, 10), d.selectionAt(5))
        assertEquals(set(0, 3, 10), d.selectionAt(3))
    }

    @Test fun startingOnASelectedItemDeselectsTheRange() {
        val d = DragSelection(keys, set(1, 2, 3, 4, 5, 9), anchor = 2)
        assertTrue(!d.adding)
        assertEquals(set(1, 5, 9), d.selectionAt(4))
        assertEquals("shrinking gives the items back", set(1, 3, 4, 5, 9), d.selectionAt(2))
    }

    @Test fun outOfRangeIndicesAreClamped() {
        val d = DragSelection(keys, emptySet(), anchor = 10)
        assertEquals(set(10, 11), d.selectionAt(50))
        assertEquals(keys.toSet(), DragSelection(keys, emptySet(), 11).selectionAt(-3))
        assertEquals(emptySet<String>(), DragSelection(emptyList(), emptySet(), 0).selectionAt(3))
    }

    /** A 3-column grid with 100px cells and 10px gaps, rows 100px apart. */
    private fun grid(count: Int, cols: Int = 3) = (0 until count).map { i ->
        val col = i % cols
        val row = i / cols
        CellBounds(i, col * 110f, row * 110f, col * 110f + 100f, row * 110f + 100f)
    }

    @Test fun gridHitTestingAndRowMajorRange() {
        val cells = grid(9)
        assertEquals(0, DragSelection.indexAt(cells, 50f, 50f))
        assertEquals(4, DragSelection.indexAt(cells, 160f, 160f))
        assertEquals(8, DragSelection.indexAt(cells, 300f, 300f))
        // In the 10px gap between columns of row 1: the nearest cell of that row.
        assertEquals(4, DragSelection.indexAt(cells, 212f, 150f))
        // Below the last row: the nearest cell overall, so the range keeps working while auto-scrolling.
        assertEquals(7, DragSelection.indexAt(cells, 160f, 400f))
        // Dragging from cell 1 (row 0, col 1) to cell 6 (row 2, col 0) selects in reading order: 1..6.
        val d = DragSelection(keys, emptySet(), anchor = DragSelection.indexAt(cells, 150f, 50f)!!)
        val end = DragSelection.indexAt(cells, 50f, 250f)!!
        assertEquals(6, end)
        assertEquals(set(1, 2, 3, 4, 5, 6), d.selectionAt(end))
        assertNull(DragSelection.indexAt(emptyList(), 1f, 1f))
    }

    @Test fun listHitTesting() {
        val rows = (0 until 5).map { CellBounds(it + 20, 0f, it * 60f, Float.MAX_VALUE, it * 60f + 60f) }
        assertEquals(22, DragSelection.indexAt(rows, 500f, 130f))
        assertEquals(20, DragSelection.indexAt(rows, 10f, -40f))
    }

    @Test fun autoScrollOnlyNearTheEdges() {
        val step = { y: Float -> DragSelection.autoScrollStep(y, top = 100f, bottom = 1000f, zone = 100f, maxStep = 20f) }
        assertEquals(0f, step(500f), 0f)
        assertEquals(-10f, step(150f), 0.01f)
        assertEquals(-20f, step(20f), 0.01f)
        assertEquals(10f, step(950f), 0.01f)
        assertEquals(20f, step(1200f), 0.01f)
    }
}
