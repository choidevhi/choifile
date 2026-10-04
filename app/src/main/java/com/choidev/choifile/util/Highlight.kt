package com.choidev.choifile.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Deliberately basic regex highlighter: comments, strings, numbers and a shared keyword set.
 * It is not language aware (e.g. '#' always starts a comment).
 */
private val keywords = listOf(
    "fun", "val", "var", "class", "object", "interface", "return", "if", "else", "for", "while", "when",
    "import", "package", "public", "private", "protected", "static", "final", "void", "new", "this",
    "def", "function", "const", "let", "true", "false", "null", "None", "try", "catch", "throw", "in", "is",
).joinToString("|")

private val token = Regex("""(//.*|#.*|/\*[\s\S]*?\*/)|("(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*')|(\b\d+(?:\.\d+)?\b)|(\b(?:$keywords)\b)""")

fun highlight(text: String, dark: Boolean): AnnotatedString = buildAnnotatedString {
    append(text)
    val comment = if (dark) Color(0xFF8A9A8A) else Color(0xFF6A7F6A)
    val string = if (dark) Color(0xFFE6B673) else Color(0xFFB45F06)
    val number = if (dark) Color(0xFF7FB8E6) else Color(0xFF1F6FB2)
    val keyword = if (dark) Color(0xFFC792EA) else Color(0xFF7B2FBE)
    token.findAll(text).forEach { m ->
        val color = when {
            m.groups[1] != null -> comment
            m.groups[2] != null -> string
            m.groups[3] != null -> number
            else -> keyword
        }
        addStyle(SpanStyle(color = color), m.range.first, m.range.last + 1)
    }
}

class HighlightTransformation(private val dark: Boolean) : VisualTransformation {
    override fun filter(text: AnnotatedString) =
        TransformedText(highlight(text.text, dark), OffsetMapping.Identity)
}
