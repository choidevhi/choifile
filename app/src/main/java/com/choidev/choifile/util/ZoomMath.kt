package com.choidev.choifile.util

/**
 * Pinch and pan arithmetic for the image viewer, kept free of Compose so it can be tested.
 *
 * The image fills a [width] x [height] box and is drawn scaled around the box centre, then translated by
 * (offsetX, offsetY). The translation is clamped so the scaled image never leaves the box.
 */
object ZoomMath {
    const val MAX_ZOOM = 8f

    data class View(val scale: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f)

    /**
     * Zooms [v] to [newScale] keeping the point under ([focusX], [focusY]) fixed, then pans by ([panX], [panY]).
     * Any NaN or infinite input (a lifted finger has no centroid) returns [v] unchanged, so a bad event can
     * never leave the view in a state that draws nothing.
     */
    fun zoomAround(
        v: View, width: Float, height: Float,
        focusX: Float, focusY: Float, newScale: Float, panX: Float = 0f, panY: Float = 0f,
    ): View {
        val inputs = floatArrayOf(width, height, focusX, focusY, newScale, panX, panY)
        if (inputs.any { !it.isFinite() } || width <= 0f || height <= 0f) return v

        val target = newScale.coerceIn(1f, MAX_ZOOM)
        val k = target / v.scale
        val relX = focusX - width / 2f
        val relY = focusY - height / 2f
        val mx = width * (target - 1) / 2
        val my = height * (target - 1) / 2

        return View(
            target,
            ((v.offsetX - relX) * k + relX + panX).coerceIn(-mx, mx),
            ((v.offsetY - relY) * k + relY + panY).coerceIn(-my, my),
        )
    }
}
