package com.choidev.choifile.util

/**
 * SAMI (.smi) subtitles, the format most older Korean subtitle files use. Media3 has no SAMI parser, so this reads
 * the common subset: `<SYNC Start=ms>` blocks with `<P Class=…>` text, `<br>` line breaks and HTML entities. A
 * file with several languages shows one: Korean if present, else the first class in the file. A line lasts until
 * the next SYNC of that class; an empty block (`&nbsp;`) only ends the line before it.
 */
object SmiParser {
    private val sync = Regex("<sync\\b[^>]*?start\\s*=\\s*[\"']?(-?\\d+)[^>]*>", RegexOption.IGNORE_CASE)
    private val paragraph = Regex("<p\\b[^>]*?class\\s*=\\s*[\"']?([A-Za-z0-9_-]+)[^>]*>", RegexOption.IGNORE_CASE)
    private val lineBreak = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val tag = Regex("<[^>]*>")
    private val numeric = Regex("&#(x?)([0-9a-fA-F]+);")
    private val body = Regex("<body", RegexOption.IGNORE_CASE)

    /** How long the very last line stays when nothing ends it. */
    private const val LAST_LINE_MS = 5_000L

    data class Line(val startMs: Long, val endMs: Long, val text: String)

    /** The lines of one language, in time order. */
    fun parse(text: String): List<Line> {
        val start = body.find(text)?.range?.last ?: -1
        val content = text.substring(start + 1)
        val syncs = sync.findAll(content).toList()
        if (syncs.isEmpty()) return emptyList()

        // (start, class, text) for every block; blocks without a P tag count for every class.
        data class Block(val start: Long, val cls: String?, val text: String)
        val blocks = syncs.mapIndexed { i, m ->
            val end = if (i + 1 < syncs.size) syncs[i + 1].range.first else content.length
            val raw = content.substring(m.range.last + 1, end)
            val p = paragraph.find(raw)
            val words = if (p != null) raw.substring(p.range.last + 1) else raw
            Block(m.groupValues[1].toLong(), p?.groupValues?.get(1)?.uppercase(), clean(words))
        }

        val classes = blocks.mapNotNull { it.cls }.distinct()
        val chosen = classes.firstOrNull { it.startsWith("KR") || it.startsWith("KO") } ?: classes.firstOrNull()
        val mine = blocks.filter { it.cls == null || chosen == null || it.cls == chosen }.sortedBy { it.start }

        return mine.mapIndexedNotNull { i, b ->
            if (b.text.isBlank()) return@mapIndexedNotNull null
            var j = i + 1
            while (j < mine.size && mine[j].start <= b.start) j++
            val end = if (j < mine.size) mine[j].start else b.start + LAST_LINE_MS
            Line(b.start, end, b.text)
        }
    }

    private fun clean(html: String): String {
        val withBreaks = lineBreak.replace(html, "\n")
        val noTags = tag.replace(withBreaks, "")
        val decoded = numeric.replace(noTags) { m ->
            val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isNotEmpty()) 16 else 10)
            if (code == null || code !in 0..0x10FFFF) "" else String(Character.toChars(code))
        }
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)
            .replace("&quot;", "\"", ignoreCase = true)
            .replace("&apos;", "'", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
        return decoded.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }
}
