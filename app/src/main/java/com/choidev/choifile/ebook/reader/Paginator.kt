package com.choidev.choifile.ebook.reader

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min

/** 한 쪽의 크기와 글자 모양. 쪽 나눔에 영향을 주는 값만 cacheKey에 넣는다(색은 빠진다). */
data class PageConfig(
    val width: Int,
    val height: Int,
    val paddingLeft: Int,
    val paddingRight: Int,
    val paddingTop: Int,
    val paddingBottom: Int,
    val textSizePx: Float,
    val lineSpacing: Float,
    val typeface: Typeface,
    val fontId: String,
    val justify: Boolean,
    val textColor: Int,
    val background: Int,
    val chromeTextSizePx: Float,
    val headerBaseline: Float,
    val footerBaseline: Float,
) {
    val contentWidth: Int get() = (width - paddingLeft - paddingRight).coerceAtLeast(1)
    val contentHeight: Int get() = (height - paddingTop - paddingBottom).coerceAtLeast(1)

    val cacheKey: String
        get() = listOf(width, height, paddingLeft, paddingRight, paddingTop, paddingBottom, textSizePx, lineSpacing, fontId, PAGINATOR_VERSION)
            .joinToString("_")
            .hashCode()
            .toUInt()
            .toString(16)

    fun textPaint() = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = textSizePx
        typeface = this@PageConfig.typeface
        color = textColor
    }

    private companion object {
        // 2: 줄바꿈 정리 전환 중에 잘못 저장된 쪽 나눔 캐시를 버린다.
        const val PAGINATOR_VERSION = 2
    }
}

object Paginator {

    private const val CHUNK = 24_000

    // Layout의 상수는 LineBreaker(API 29)의 상수와 값이 같다. minSdk 26이라 Layout 쪽을 쓴다.
    @SuppressLint("WrongConstant")
    fun layout(text: CharSequence, start: Int, end: Int, paint: TextPaint, cfg: PageConfig): StaticLayout =
        StaticLayout.Builder.obtain(text, start, end, paint, cfg.contentWidth)
            .setLineSpacing(0f, cfg.lineSpacing)
            .setIncludePad(false)
            // SIMPLE은 앞에서부터 욕심껏 줄을 채운다. 그래서 쪽 중간에서 다시 배치해도 줄이 똑같이 나뉜다.
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .apply { if (cfg.justify) setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD) }
            .build()

    /**
     * 각 쪽이 시작하는 글자 위치를 돌려준다.
     * 책 전체를 한 번에 배치하면 메모리가 크게 들어서 덩어리로 나눠 배치하고,
     * 덩어리 끝에서 덜 찬 마지막 쪽은 버리고 다음 덩어리를 그 쪽 첫 줄부터 다시 시작한다.
     */
    fun paginate(
        text: String,
        cfg: PageConfig,
        from: Int = 0,
        maxChars: Int = Int.MAX_VALUE,
        isActive: () -> Boolean = { true },
        onProgress: (Float) -> Unit = {},
    ): IntArray {
        val len = text.length
        val paint = cfg.textPaint()
        val limit = if (maxChars == Int.MAX_VALUE) len else min(len, from + maxChars)
        val starts = IntList()
        var pageStart = skipBlank(text, from)

        if (pageStart >= len) return intArrayOf(from.coerceAtMost(len))

        starts.add(pageStart)
        var chunk = CHUNK
        val maxHeight = cfg.contentHeight

        while (pageStart < limit) {
            if (!isActive()) throw CancellationException()

            var end = min(len, pageStart + chunk)
            if (end < len) {
                val nl = text.indexOf('\n', end)
                if (nl in end until end + chunk / 2) end = nl + 1
            }

            val layout = layout(text, pageStart, end, paint, cfg)
            // StaticLayout의 줄 위치가 원문 기준인지 덩어리 기준인지 기기마다 다를 수 있어 보정값을 둔다.
            val base = pageStart - layout.getLineStart(0)
            val lineCount = layout.lineCount
            var first = 0
            var top = layout.getLineTop(0)
            var lastStart = pageStart
            var line = 0
            var finished = false

            while (line < lineCount) {
                if (layout.getLineBottom(line) - top > maxHeight && line > first) {
                    val next = skipBlank(text, base + layout.getLineStart(line))
                    if (next >= len) {
                        finished = true
                        break
                    }

                    starts.add(next)
                    lastStart = next
                    if (next >= end || next >= limit) break

                    first = layout.getLineForOffset(next - base)
                    line = first
                    top = layout.getLineTop(first)
                    continue
                }
                line++
            }

            if (finished || end >= len || lastStart >= limit) break

            if (lastStart == pageStart) {
                // 덩어리 전체가 한 쪽에 들어갔다(아주 큰 화면). 덩어리를 키워 다시 한다.
                chunk *= 2
                continue
            }

            pageStart = lastStart
            onProgress(pageStart.toFloat() / len)
        }

        return starts.toIntArray()
    }

    /** 쪽 맨 위에 빈 줄이 오지 않게 공백뿐인 줄을 건너뛴다. */
    fun skipBlank(text: String, from: Int): Int {
        val len = text.length
        var i = from

        while (i < len) {
            var j = i
            while (j < len && text[j] != '\n' && text[j].isWhitespace()) j++

            if (j >= len) return len
            if (text[j] != '\n') return i

            i = j + 1
        }

        return i
    }

    /** offset이 들어 있는 쪽 번호. */
    fun indexFor(pages: IntArray, offset: Int): Int {
        var lo = 0
        var hi = pages.size - 1
        var ans = 0

        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (pages[mid] <= offset) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }

        return ans
    }

    fun readCache(file: File): IntArray? = runCatching {
        DataInputStream(file.inputStream().buffered()).use { input ->
            val n = input.readInt()
            IntArray(n) { input.readInt() }
        }
    }.getOrNull()

    fun writeCache(file: File, pages: IntArray) {
        runCatching {
            file.parentFile?.mkdirs()
            DataOutputStream(file.outputStream().buffered()).use { out ->
                out.writeInt(pages.size)
                pages.forEach { out.writeInt(it) }
            }
        }
    }

    private class IntList {
        private var data = IntArray(256)
        private var size = 0

        fun add(v: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }

        fun toIntArray(): IntArray = data.copyOf(size)
    }
}
