package com.choidev.choifile.ebook.reader

/** 본문 검색 결과 하나. before·match·after는 목록에 보여 줄 앞뒤 문장 조각이다. */
data class SearchHit(val range: IntRange, val before: String, val match: String, val after: String)

object Search {

    /**
     * 대소문자를 가리지 않고 찾는다. 줄바꿈 정리로 들어간 폭 없는 공백은 건너뛰고 비교해서,
     * 정리로 이어 붙인 자리에 걸친 낱말도 찾는다.
     */
    fun find(text: String, query: String, limit: Int = 500, isActive: () -> Boolean = { true }): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        val hits = ArrayList<SearchHit>()
        var i = 0
        while (i < text.length && hits.size < limit) {
            if ((i and 0xFFFF) == 0 && !isActive()) break

            val end = matchAt(text, i, q)
            if (end > 0) {
                hits += SearchHit(
                    range = i until end,
                    before = clean(text.substring((i - 24).coerceAtLeast(0), i)).trimStart(),
                    match = clean(text.substring(i, end)),
                    after = clean(text.substring(end, (end + 40).coerceAtMost(text.length))).trimEnd(),
                )
                i = end
            } else {
                i++
            }
        }

        return hits
    }

    /** text의 start부터 q와 같으면 끝 위치(배타), 아니면 -1. */
    private fun matchAt(text: String, start: Int, q: String): Int {
        if (text[start].code == ZWSP) return -1

        var t = start
        var k = 0
        while (k < q.length) {
            if (t >= text.length) return -1
            if (text[t].code == ZWSP) {
                t++
                continue
            }
            if (!text[t].equals(q[k], ignoreCase = true)) return -1

            t++
            k++
        }

        return t
    }

    private fun clean(s: String): String = s.filterNot { it.code == ZWSP }.replace('\n', ' ')

    private const val ZWSP = 0x200B
}
