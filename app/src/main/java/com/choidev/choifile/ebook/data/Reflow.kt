package com.choidev.choifile.ebook.data

/**
 * 텍본의 강제 줄바꿈 정리. 옛 변환본·연재본은 일정 글자 수마다 줄을 끊어 둔 경우가 많아서,
 * 화면에서 문장이 중간에 뚝뚝 끊겨 보인다. 그런 줄바꿈만 골라 이어 붙인다.
 *
 * 줄바꿈 한 글자를 공백이나 폭 없는 공백(U+200B) 한 글자로 바꾸기만 해서 글자 수가 그대로다.
 * 그래서 정리를 켜고 꺼도 읽던 위치·책갈피의 글자 위치가 어긋나지 않는다.
 */
object Reflow {

    private val ZERO_WIDTH_SPACE = 0x200B.toChar()
    private const val IDEOGRAPHIC_SPACE = 0x3000

    // 이 글자로 끝난 줄은 문장이 끝난 것으로 본다.
    private const val SENTENCE_ENDS = ".!?…\"'”’」』)]>~♡♥*=-:;"

    // 이 글자로 시작하는 줄은 새 문단(대사, 괄호, 구분선)으로 본다.
    private const val PARAGRAPH_STARTS = "\"'“‘「『([<-—*=#【〈《"

    // 한국어 어절 끝에 자주 오는 글자. 이 글자로 끝난 줄에 이어 붙일 때는 띄어 쓴다(어절 사이에서 끊긴 것으로 본다).
    private const val WORD_ENDINGS = "은는이가을를에의도로와과다요고며서만게지께랑여야해했던데면든라니까죠네며나요서한된될할"

    /** 강제 줄바꿈으로 보이는 책인지. 가져올 때 정리를 기본으로 켤지 정하는 데 쓴다. */
    fun looksHardWrapped(text: String): Boolean {
        val width = wrapWidth(text) ?: return false
        var lines = 0
        var cut = 0
        forEachLine(text) { start, end, nextStart, nextEnd ->
            val len = trimmedLength(text, start, end)
            if (len > 0) {
                lines++
                if (len >= width * 0.85f && !endsSentence(text, start, end) && nextStart < nextEnd) cut++
            }
        }

        return lines >= 50 && cut.toFloat() / lines > 0.25f
    }

    fun apply(text: String): String {
        val width = wrapWidth(text) ?: return text
        val chars = text.toCharArray()

        forEachLine(text) { start, end, nextStart, nextEnd ->
            if (end >= text.length || !shouldJoin(text, width, start, end, nextStart, nextEnd)) return@forEachLine

            val lastChar = text[end - 1]
            chars[end] = when {
                lastChar.isWhitespace() || text[nextStart].isWhitespace() -> ZERO_WIDTH_SPACE
                lastChar.isLetterOrDigit() && lastChar.code < 0x80 -> ' '
                lastChar in WORD_ENDINGS -> ' '
                // 어절 가운데서 끊긴 것으로 보고 붙여 쓴다. 폭 없는 공백이라 그 자리에서 줄을 바꿀 수는 있다.
                else -> ZERO_WIDTH_SPACE
            }
        }

        return String(chars)
    }

    private fun shouldJoin(text: String, width: Int, start: Int, end: Int, nextStart: Int, nextEnd: Int): Boolean {
        if (trimmedLength(text, start, end) < width * 0.75f) return false
        if (endsSentence(text, start, end)) return false
        if (nextStart >= nextEnd) return false

        // 들여쓰기(공백 두 칸 이상, 전각 공백, 탭에서 바뀐 공백)로 시작하면 새 문단이다.
        // 공백 한 칸은 기계적으로 자른 자리에서 흔히 생겨서 이어지는 줄로 본다.
        if (isIndented(text, nextStart, nextEnd)) return false

        var firstAt = nextStart
        while (firstAt < nextEnd && text[firstAt] == ' ') firstAt++
        if (firstAt < nextEnd && text[firstAt] in PARAGRAPH_STARTS) return false

        return !looksLikeHeading(text, nextStart, nextEnd)
    }

    private fun isIndented(text: String, start: Int, end: Int): Boolean {
        if (start >= end) return false

        val first = text[start]
        if (first.code == IDEOGRAPHIC_SPACE || first == '\t') return true

        return first == ' ' && start + 1 < end && text[start + 1].isWhitespace()
    }

    private fun looksLikeHeading(text: String, start: Int, end: Int): Boolean {
        if (end - start > 30) return false

        val line = text.substring(start, end).trim()
        return line.startsWith("제") && line.length <= 20 || line.endsWith("화") || line.endsWith("장")
    }

    private fun endsSentence(text: String, start: Int, end: Int): Boolean {
        var i = end - 1
        while (i >= start && text[i].isWhitespace()) i--

        return i < start || text[i] in SENTENCE_ENDS
    }

    private fun trimmedLength(text: String, start: Int, end: Int): Int {
        var a = start
        var b = end
        while (a < b && text[a].isWhitespace()) a++
        while (b > a && text[b - 1].isWhitespace()) b--

        return b - a
    }

    /** 끊어 둔 폭: 내용 있는 줄 길이의 상위 10% 지점. 너무 짧거나 줄이 적으면 null. */
    private fun wrapWidth(text: String): Int? {
        val lengths = ArrayList<Int>()
        forEachLine(text) { start, end, _, _ ->
            val len = trimmedLength(text, start, end)
            if (len > 0 && lengths.size < 20_000) lengths += len
        }
        if (lengths.size < 50) return null

        lengths.sort()
        val width = lengths[(lengths.size * 0.9f).toInt().coerceAtMost(lengths.size - 1)]
        return width.takeIf { it >= 20 }
    }

    /** 줄마다 (줄 시작, 줄 끝=줄바꿈 위치, 다음 줄 시작, 다음 줄 끝)을 넘긴다. */
    private inline fun forEachLine(text: String, block: (Int, Int, Int, Int) -> Unit) {
        val len = text.length
        var start = 0
        while (start < len) {
            val nl = text.indexOf('\n', start).let { if (it < 0) len else it }
            val nextStart = nl + 1
            val nextEnd = if (nextStart >= len) len else text.indexOf('\n', nextStart).let { if (it < 0) len else it }
            block(start, nl, nextStart.coerceAtMost(len), nextEnd)
            start = nl + 1
        }
    }
}
