package com.choidev.choifile.ebook.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import androidx.core.graphics.ColorUtils
import kotlin.math.min

/**
 * 쪽을 그린다. 넘기는 동안 매 프레임 글자를 다시 그리면 끊기므로(특히 거울상 뒷면은 글자 캐시가 안 먹는다),
 * 쪽마다 한 번만 이미지로 그려 GPU에 올려 두고 화면에는 그 이미지만 옮겨 그린다.
 */
class PageRenderer(
    private val text: String,
    val cfg: PageConfig,
    val pages: IntArray,
    private val headerFor: (offset: Int) -> String,
    private val footerFor: (index: Int) -> String,
    /** 검색으로 찾은 글자 범위. 이 범위가 걸친 쪽에 노란 칠을 한다. */
    private val highlight: IntRange? = null,
) {
    private val paint = cfg.textPaint()
    private val chromePaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        textSize = cfg.chromeTextSizePx
        typeface = Typeface.DEFAULT
        color = ColorUtils.setAlphaComponent(cfg.textColor, 110)
    }
    private val bgPaint = Paint().apply { color = cfg.background }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFCC00 }
    private val highlightPath = Path()

    // 넘어가는 종이의 뒷면: 앞면이 거울처럼 희미하게 비친다.
    private val backPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 34 }
    private val layouts = LruCache<Int, PageLayout>(8)

    // 화면 크기 이미지라 메모리가 커서 지금 쪽 앞뒤 몇 장만 둔다.
    private val bitmaps = LruCache<Int, Bitmap>(5)
    private var scratch: Bitmap? = null

    // 설정이 바뀌어 새 렌더러로 바뀌면 true. 뒤늦게 끝난 미리 그리기가 버린 캐시를 다시 채우지 않게 한다.
    @Volatile
    private var released = false

    private class PageLayout(val layout: StaticLayout, val clipBottom: Float, val base: Int)

    val pageCount: Int get() = pages.size

    fun draw(canvas: Canvas, index: Int, back: Boolean = false) {
        if (index !in pages.indices) {
            if (!back) canvas.drawRect(0f, 0f, cfg.width.toFloat(), cfg.height.toFloat(), bgPaint)
            return
        }

        canvas.drawBitmap(bitmap(index), 0f, 0f, if (back) backPaint else bitmapPaint)
    }

    /** 곧 보일 쪽을 미리 그려 둔다. 백그라운드에서 부른다. */
    fun prefetch(index: Int) {
        for (i in intArrayOf(index, index + 1, index - 1, index + 2)) {
            if (released) return
            if (i in pages.indices) bitmap(i)
        }
    }

    @Synchronized
    fun release() {
        released = true
        bitmaps.evictAll()
        scratch = null
    }

    @Synchronized
    private fun bitmap(index: Int): Bitmap = bitmaps.get(index) ?: render(index).also { if (!released) bitmaps.put(index, it) }

    // 재사용하는 작업용 이미지에 그린 뒤 GPU 전용 이미지로 복사한다. 복사하면서 업로드가 끝나 첫 프레임에 멈칫하지 않는다.
    private fun render(index: Int): Bitmap {
        val work = scratch ?: Bitmap.createBitmap(cfg.width, cfg.height, Bitmap.Config.ARGB_8888).also { scratch = it }
        drawPage(Canvas(work), index)

        return work.copy(Bitmap.Config.HARDWARE, false) ?: work.copy(Bitmap.Config.ARGB_8888, false)
    }

    private fun drawPage(canvas: Canvas, index: Int) {
        canvas.drawRect(0f, 0f, cfg.width.toFloat(), cfg.height.toFloat(), bgPaint)

        val page = layoutFor(index)
        canvas.save()
        canvas.translate(cfg.paddingLeft.toFloat(), cfg.paddingTop.toFloat())
        canvas.clipRect(0f, 0f, cfg.contentWidth.toFloat(), page.clipBottom)
        val mark = highlight
        if (mark != null) {
            // 찾은 글자가 이 쪽 밖에 있거나 쪽 경계에 걸치면 이 쪽에 든 부분만 칠한다.
            val layout = page.layout
            val lo = maxOf(mark.first - page.base, layout.getLineStart(0))
            val hi = minOf(mark.last + 1 - page.base, layout.getLineEnd(layout.lineCount - 1))
            if (lo < hi) {
                highlightPath.reset()
                layout.getSelectionPath(lo, hi, highlightPath)
                canvas.drawPath(highlightPath, highlightPaint)
            }
        }
        page.layout.draw(canvas)
        canvas.restore()

        val header = TextUtils.ellipsize(headerFor(pages[index]), chromePaint, cfg.contentWidth.toFloat(), TextUtils.TruncateAt.END)
        canvas.drawText(header, 0, header.length, cfg.paddingLeft.toFloat(), cfg.headerBaseline, chromePaint)

        val footer = footerFor(index)
        val fw = chromePaint.measureText(footer)
        canvas.drawText(footer, (cfg.width - fw) / 2f, cfg.footerBaseline, chromePaint)
    }

    private fun layoutFor(index: Int): PageLayout = layouts.get(index) ?: buildLayout(index).also { layouts.put(index, it) }

    private fun buildLayout(index: Int): PageLayout {
        val start = pages[index]
        val end = if (index + 1 < pages.size) pages[index + 1] else min(text.length, start + 6000)

        // 쪽 끝 문장이 다음 쪽으로 이어지면 문단 끝까지 배치해야 마지막 줄도 양쪽 정렬된다. 보이는 줄만 잘라 그린다.
        var layoutEnd = end
        if (end > start && end < text.length && text[end - 1] != '\n') {
            val nl = text.indexOf('\n', end)
            layoutEnd = if (nl < 0) min(text.length, end + 2000) else min(nl + 1, end + 2000)
        }

        val layout = Paginator.layout(text, start, layoutEnd.coerceAtLeast(start), paint, cfg)
        val base = start - layout.getLineStart(0)
        val top = layout.getLineTop(0)
        var last = if (end > start) layout.getLineForOffset((end - 1 - base).coerceAtLeast(0)) else 0
        while (last > 0 && layout.getLineBottom(last) - top > cfg.contentHeight) last--

        return PageLayout(layout, (layout.getLineBottom(last) - top).toFloat(), base)
    }
}
