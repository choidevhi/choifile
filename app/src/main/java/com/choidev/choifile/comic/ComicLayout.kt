package com.choidev.choifile.comic

import com.choidev.choifile.util.NaturalOrder

/** Image extensions the comic viewer shows (what BitmapFactory can decode on Android 8+). */
val COMIC_IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif")

fun isComicImage(name: String): Boolean {
    val base = name.substringAfterLast('/')
    if (base.startsWith(".")) return false
    return base.substringAfterLast('.', "").lowercase() in COMIC_IMAGE_EXT
}

/**
 * Picks the images of a comic from a folder listing or archive entry paths and orders them naturally
 * ("2.jpg" before "10.jpg"). Archive junk such as __MACOSX resource forks and dot files is skipped.
 */
fun comicPageOrder(paths: List<String>): List<String> =
    paths.filter { p -> isComicImage(p) && p.split('/').none { it == "__MACOSX" } }.sortedWith(NaturalOrder)

/** One strip of a page in source pixels: rows [top, bottom) of image [page], which is [width] wide. */
data class ComicSlice(val page: Int, val part: Int, val top: Int, val bottom: Int, val width: Int) {
    val height: Int get() = bottom - top
    /** width / height, for the placeholder before the bitmap is decoded. */
    val aspect: Float get() = if (height <= 0 || width <= 0) 1f else width.toFloat() / height
}

object ComicLayout {
    /** Pages taller than this many widths are split, so no single bitmap gets huge (webtoon strips can be 20x). */
    const val SPLIT_ABOVE = 2.5f
    /** Height of each strip, in widths. */
    const val STRIP = 1.5f

    /**
     * Splits one page into strips. Strips depend only on the image size (not the screen), so the item index of
     * a position stays valid across devices and restarts. A tiny tail is merged into the previous strip.
     */
    fun slices(page: Int, width: Int, height: Int): List<ComicSlice> {
        if (width <= 0 || height <= 0) return listOf(ComicSlice(page, 0, 0, 0, 0)) // undecodable: one placeholder
        if (height <= width * SPLIT_ABOVE) return listOf(ComicSlice(page, 0, 0, height, width))
        val strip = (width * STRIP).toInt().coerceAtLeast(1)
        val out = ArrayList<ComicSlice>()
        var top = 0
        while (top < height) {
            var bottom = (top + strip).coerceAtMost(height)
            if (height - bottom < strip / 4) bottom = height // fold a sliver into this strip
            out += ComicSlice(page, out.size, top, bottom, width)
            top = bottom
        }
        return out
    }

    /** Largest power-of-two sample size that still yields at least [targetWidth] pixels across. */
    fun sampleSize(sourceWidth: Int, targetWidth: Int): Int {
        if (sourceWidth <= 0 || targetWidth <= 0) return 1
        var s = 1
        while (sourceWidth / (s * 2) >= targetWidth) s *= 2
        return s
    }

    /** Stable anchor text for a strip, saved with the reading position: "<page name>#<part>". */
    fun anchor(pageName: String, slice: ComicSlice) = "$pageName#${slice.part}"

    /**
     * Where to resume: the saved index if the strip there still has the saved anchor, otherwise the strip with that
     * anchor anywhere in the list (pages were added or removed), otherwise the saved index clamped to the list.
     */
    fun resolve(savedIndex: Int, savedAnchor: String?, anchors: List<String>): Int {
        if (anchors.isEmpty()) return 0
        if (savedAnchor != null) {
            if (anchors.getOrNull(savedIndex) == savedAnchor) return savedIndex
            val found = anchors.indexOf(savedAnchor)
            if (found >= 0) return found
        }
        return savedIndex.coerceIn(0, anchors.size - 1)
    }
}
