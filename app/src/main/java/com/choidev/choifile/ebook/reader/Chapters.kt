package com.choidev.choifile.ebook.reader

data class Chapter(val title: String, val offset: Int)

/** 텍본에는 목차 정보가 없어서, "제1장", "12화", "프롤로그" 같은 짧은 줄을 장 제목으로 본다. */
object Chapters {

    private val pattern = Regex(
        "^(?:" +
            "제\\s*[0-9一二三四五六七八九十百千]+\\s*[장화권부편절막]" +
            "|[0-9]{1,4}\\s*[화장]" +
            "|(?:프롤로그|에필로그|서장|종장|외전|번외|작가의\\s*말|후기)" +
            "|(?:chapter|ch\\.|episode|ep\\.|part)\\s*[0-9ivxlc]+" +
            "|#\\s*[0-9]{1,4}" +
            "|[<\\[〈《【(]\\s*[0-9]{1,4}\\s*(?:화)?\\s*[>\\]〉》】)]" +
            ")",
        RegexOption.IGNORE_CASE,
    )

    fun detect(text: String): List<Chapter> {
        val out = ArrayList<Chapter>()
        var i = 0
        val len = text.length

        while (i < len && out.size < 5000) {
            val nl = text.indexOf('\n', i).let { if (it < 0) len else it }
            if (nl - i in 1..60) {
                val line = text.substring(i, nl).trim()
                if (line.isNotEmpty() && pattern.containsMatchIn(line)) out += Chapter(line, i)
            }
            i = nl + 1
        }

        return out
    }

    /** offset이 속한 장. 앞쪽 장이 없으면 null. */
    fun at(chapters: List<Chapter>, offset: Int): Int {
        var lo = 0
        var hi = chapters.size - 1
        var ans = -1

        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (chapters[mid].offset <= offset) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }

        return ans
    }
}
