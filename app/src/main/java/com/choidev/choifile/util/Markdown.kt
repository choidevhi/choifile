package com.choidev.choifile.util

/** A run of inline text with formatting. [link] is only ever an http, https or mailto URL. */
data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val link: String? = null,
)

sealed interface MdBlock {
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    data class Paragraph(val spans: List<MdSpan>) : MdBlock
    data class Code(val language: String, val text: String) : MdBlock
    data class Quote(val spans: List<MdSpan>) : MdBlock
    /** [number] is null for bullets; [indent] counts nesting levels (two spaces each). */
    data class ListItem(val number: Int?, val indent: Int, val checked: Boolean?, val spans: List<MdSpan>) : MdBlock
    data class Table(val header: List<List<MdSpan>>, val rows: List<List<List<MdSpan>>>) : MdBlock
    data object Rule : MdBlock
}

/**
 * A small Markdown renderer model: headings, paragraphs, fenced code, block quotes, bullet / numbered / task lists,
 * pipe tables, horizontal rules, and inline bold, italic, strikethrough, code and links. Raw HTML is never
 * interpreted (it shows as text) and images show their alt text, so a document cannot load anything.
 */
object Markdown {
    private val heading = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val bullet = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val numbered = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
    private val rule = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val tableSep = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")
    private val fence = Regex("^\\s*(```|~~~)\\s*(\\S*)\\s*$")

    fun parse(text: String): List<MdBlock> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = ArrayList<MdBlock>()
        val para = ArrayList<String>()
        fun flush() {
            if (para.isNotEmpty()) { out += MdBlock.Paragraph(inline(para.joinToString(" "))); para.clear() }
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val f = fence.matchEntire(line)
            if (f != null) {
                flush()
                val marker = f.groupValues[1]
                val body = ArrayList<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith(marker)) { body += lines[i]; i++ }
                out += MdBlock.Code(f.groupValues[2], body.joinToString("\n"))
                i++
                continue
            }
            if (line.isBlank()) { flush(); i++; continue }
            val h = heading.matchEntire(line)
            if (h != null) { flush(); out += MdBlock.Heading(h.groupValues[1].length, inline(h.groupValues[2])); i++; continue }
            if (rule.matches(line)) { flush(); out += MdBlock.Rule; i++; continue }
            if (line.trimStart().startsWith(">")) {
                flush()
                val q = ArrayList<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) { q += lines[i].trimStart().removePrefix(">").removePrefix(" "); i++ }
                out += MdBlock.Quote(inline(q.joinToString(" ")))
                continue
            }
            if (line.contains('|') && i + 1 < lines.size && tableSep.matches(lines[i + 1]) && lines[i + 1].contains('-')) {
                flush()
                val header = cells(line).map(::inline)
                i += 2
                val rows = ArrayList<List<List<MdSpan>>>()
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) { rows += cells(lines[i]).map(::inline); i++ }
                out += MdBlock.Table(header, rows)
                continue
            }
            val b = bullet.matchEntire(line)
            val n = numbered.matchEntire(line)
            if (b != null || n != null) {
                flush()
                val indent = ((b ?: n)!!.groupValues[1].replace("\t", "  ").length) / 2
                var body = if (b != null) b.groupValues[2] else n!!.groupValues[3]
                var checked: Boolean? = null
                if (b != null) {
                    if (body.startsWith("[ ] ")) { checked = false; body = body.substring(4) }
                    else if (body.startsWith("[x] ", ignoreCase = true)) { checked = true; body = body.substring(4) }
                }
                out += MdBlock.ListItem(n?.groupValues?.get(2)?.toIntOrNull(), indent, checked, inline(body))
                i++
                continue
            }
            para += line.trim()
            i++
        }
        flush()
        return out
    }

    private fun cells(line: String): List<String> =
        line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

    /** Inline formatting. Unclosed markers stay as literal text. */
    fun inline(src: String): List<MdSpan> {
        val spans = ArrayList<MdSpan>()
        parseInline(src, spans, false, false, false)
        return merge(spans)
    }

    private fun parseInline(s: String, out: MutableList<MdSpan>, bold: Boolean, italic: Boolean, strike: Boolean) {
        val buf = StringBuilder()
        fun flush() { if (buf.isNotEmpty()) { out += MdSpan(buf.toString(), bold, italic, false, strike); buf.clear() } }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && s[i + 1] in "\\`*_{}[]()#+-.!~|>") { buf.append(s[i + 1]); i += 2; continue }
            if (c == '`') {
                val end = s.indexOf('`', i + 1)
                if (end > i) { flush(); out += MdSpan(s.substring(i + 1, end), bold, italic, true, strike); i = end + 1; continue }
            }
            if (c == '!' && i + 1 < s.length && s[i + 1] == '[') {
                val m = linkAt(s, i + 1)
                if (m != null) { buf.append(m.text); i = m.end; continue } // image: alt text only
            }
            if (c == '[') {
                val m = linkAt(s, i)
                if (m != null) {
                    flush()
                    val inner = ArrayList<MdSpan>()
                    parseInline(m.text, inner, bold, italic, strike)
                    val safe = if (isSafeUrl(m.url)) m.url else null
                    inner.forEach { out += it.copy(link = safe) }
                    i = m.end
                    continue
                }
            }
            if (s.startsWith("***", i)) {
                val end3 = s.indexOf("***", i + 3)
                if (end3 > i + 3) {
                    flush()
                    parseInline(s.substring(i + 3, end3), out, true, true, strike)
                    i = end3 + 3
                    continue
                }
            }
            val two = if (i + 1 < s.length) s.substring(i, i + 2) else ""
            if (two == "**" || two == "__" || two == "~~") {
                val end = s.indexOf(two, i + 2)
                if (end > i + 2) {
                    flush()
                    val inner = s.substring(i + 2, end)
                    if (two == "~~") parseInline(inner, out, bold, italic, true) else parseInline(inner, out, true, italic, strike)
                    i = end + 2
                    continue
                }
            }
            if ((c == '*' || c == '_') && i + 1 < s.length && !s[i + 1].isWhitespace()) {
                // '_' inside a word (snake_case) is not emphasis.
                val intraWord = c == '_' && i > 0 && s[i - 1].isLetterOrDigit()
                val end = if (intraWord) -1 else findClose(s, c, i + 1)
                if (end > i + 1) {
                    flush()
                    parseInline(s.substring(i + 1, end), out, bold, true, strike)
                    i = end + 1
                    continue
                }
            }
            buf.append(c)
            i++
        }
        flush()
    }

    private fun findClose(s: String, marker: Char, from: Int): Int {
        var j = from
        while (j < s.length) {
            if (s[j] == '\\') { j += 2; continue }
            if (s[j] == marker && !s[j - 1].isWhitespace() && (j + 1 >= s.length || s.getOrNull(j + 1) != marker)) return j
            j++
        }
        return -1
    }

    private class LinkMatch(val text: String, val url: String, val end: Int)

    private fun linkAt(s: String, start: Int): LinkMatch? {
        if (s[start] != '[') return null
        var depth = 0
        var j = start
        while (j < s.length) {
            if (s[j] == '[') depth++
            if (s[j] == ']') { depth--; if (depth == 0) break }
            j++
        }
        if (j >= s.length || j + 1 >= s.length || s[j + 1] != '(') return null
        var close = -1
        var depth2 = 0
        for (k in j + 2 until s.length) {
            if (s[k] == '(') depth2++
            if (s[k] == ')') { if (depth2 == 0) { close = k; break }; depth2-- }
        }
        if (close < 0) return null
        val url = s.substring(j + 2, close).trim().substringBefore(' ')
        return LinkMatch(s.substring(start + 1, j), url, close + 1)
    }

    fun isSafeUrl(url: String): Boolean {
        val u = url.trim().lowercase()
        return u.startsWith("http://") || u.startsWith("https://") || u.startsWith("mailto:")
    }

    private fun merge(spans: List<MdSpan>): List<MdSpan> {
        val out = ArrayList<MdSpan>()
        for (s in spans) {
            val last = out.lastOrNull()
            if (last != null && last.copy(text = "") == s.copy(text = "")) out[out.size - 1] = last.copy(text = last.text + s.text) else out += s
        }
        return out
    }
}
