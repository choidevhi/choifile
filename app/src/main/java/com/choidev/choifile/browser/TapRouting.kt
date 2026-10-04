package com.choidev.choifile.browser

/** Where on a file row / grid cell a tap landed. */
enum class TapTarget {
    /** Anywhere on the row or cell that is not one of the targets below. */
    BODY,

    /** The 44dp icon/thumbnail at the start of a list row. */
    ICON,

    /** The small round check in the top-left corner of a grid cell. */
    CHECK,
}

enum class TapAction { OPEN, TOGGLE }

/**
 * What a single tap does. While anything is selected every tap toggles. Otherwise, in the list the icon starts
 * multi-select and the rest of the row opens; in the grid the thumbnail fills almost the whole cell, so the cell
 * opens and only the corner check (or a long press) starts selecting.
 */
object TapRouting {
    fun route(target: TapTarget, grid: Boolean, selecting: Boolean): TapAction = when {
        selecting -> TapAction.TOGGLE
        target == TapTarget.CHECK -> TapAction.TOGGLE
        target == TapTarget.ICON && !grid -> TapAction.TOGGLE
        else -> TapAction.OPEN
    }
}
