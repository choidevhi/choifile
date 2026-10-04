package com.choidev.choifile

import com.choidev.choifile.util.MdBlock
import com.choidev.choifile.util.MdSpan
import com.choidev.choifile.util.Markdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    @Test fun headingsParagraphsAndRules() {
        val b = Markdown.parse("# Title\n\nfirst line\nsecond line\n\n### Sub ###\n\n---\n")
        assertEquals(MdBlock.Heading(1, listOf(MdSpan("Title"))), b[0])
        assertEquals(MdBlock.Paragraph(listOf(MdSpan("first line second line"))), b[1])
        assertEquals(MdBlock.Heading(3, listOf(MdSpan("Sub"))), b[2])
        assertEquals(MdBlock.Rule, b[3])
    }

    @Test fun inlineFormatting() {
        val s = Markdown.inline("a **bold** and *it* and `code` and ~~gone~~ and ***both***")
        assertTrue(s.contains(MdSpan("bold", bold = true)))
        assertTrue(s.contains(MdSpan("it", italic = true)))
        assertTrue(s.contains(MdSpan("code", code = true)))
        assertTrue(s.contains(MdSpan("gone", strike = true)))
        assertTrue(s.contains(MdSpan("both", bold = true, italic = true)))
    }

    @Test fun unclosedMarkersAndSnakeCaseStayLiteral() {
        assertEquals(listOf(MdSpan("2 * 3 = 6 and **open")), Markdown.inline("2 * 3 = 6 and **open"))
        assertEquals(listOf(MdSpan("snake_case_name")), Markdown.inline("snake_case_name"))
        assertEquals(listOf(MdSpan("*not italic*")), Markdown.inline("\\*not italic\\*"))
    }

    @Test fun linksOnlyKeepSafeSchemes() {
        val ok = Markdown.inline("see [site](https://example.com/x) now")
        assertEquals("https://example.com/x", ok.single { it.text == "site" }.link)
        val bad = Markdown.inline("[click](javascript:alert(1)) and [file](file:///etc/passwd)")
        assertTrue(bad.all { it.link == null })
        assertEquals("click and file", bad.joinToString("") { it.text })
        val img = Markdown.inline("![alt text](http://tracker/x.png)")
        assertEquals(listOf(MdSpan("alt text")), img)
        assertEquals("mailto:a@b.c", Markdown.inline("[m](mailto:a@b.c)").single().link)
    }

    @Test fun rawHtmlIsShownAsText() {
        assertEquals("<script>alert(1)</script>", (Markdown.parse("<script>alert(1)</script>").single() as MdBlock.Paragraph).spans.joinToString("") { it.text })
    }

    @Test fun fencedCodeKeepsContentVerbatim() {
        val b = Markdown.parse("```kotlin\nval x = 1 * 2\n# not a heading\n```\nafter")
        assertEquals(MdBlock.Code("kotlin", "val x = 1 * 2\n# not a heading"), b[0])
        assertEquals(MdBlock.Paragraph(listOf(MdSpan("after"))), b[1])
        // an unterminated fence swallows the rest instead of failing
        assertEquals(MdBlock.Code("", "a\nb"), Markdown.parse("```\na\nb").single())
    }

    @Test fun listsQuotesAndTasks() {
        val b = Markdown.parse("- one\n  - nested\n* [x] done\n- [ ] todo\n1. first\n2) second\n> quoted\n> more")
        assertEquals(MdBlock.ListItem(null, 0, null, listOf(MdSpan("one"))), b[0])
        assertEquals(MdBlock.ListItem(null, 1, null, listOf(MdSpan("nested"))), b[1])
        assertEquals(MdBlock.ListItem(null, 0, true, listOf(MdSpan("done"))), b[2])
        assertEquals(MdBlock.ListItem(null, 0, false, listOf(MdSpan("todo"))), b[3])
        assertEquals(MdBlock.ListItem(1, 0, null, listOf(MdSpan("first"))), b[4])
        assertEquals(MdBlock.ListItem(2, 0, null, listOf(MdSpan("second"))), b[5])
        assertEquals(MdBlock.Quote(listOf(MdSpan("quoted more"))), b[6])
    }

    @Test fun tables() {
        val t = Markdown.parse("| a | b |\n|---|:-:|\n| 1 | **2** |\n| 3 | 4 |\n").single() as MdBlock.Table
        assertEquals(listOf(listOf(MdSpan("a")), listOf(MdSpan("b"))), t.header)
        assertEquals(2, t.rows.size)
        assertEquals(listOf(MdSpan("2", bold = true)), t.rows[0][1])
    }

    @Test fun windowsLineEndingsAndEmptyInput() {
        assertEquals(2, Markdown.parse("# a\r\n\r\ntext\r\n").size)
        assertTrue(Markdown.parse("").isEmpty())
    }
}
