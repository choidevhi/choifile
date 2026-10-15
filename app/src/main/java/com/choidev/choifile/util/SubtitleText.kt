package com.choidev.choifile.util

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Subtitle file text: which encoding a file is in, and which lines are on screen at a moment. Kept free of
 * Android and Media3 so it can be tested; the player parses cues with Media3 and keeps them in a [CueTimeline].
 */
object SubtitleText {
    /** Encodings offered when the automatic choice is wrong, in menu order. */
    val CHOICES = listOf("UTF-8", "x-windows-949", "Shift_JIS", "GB18030", "Big5", "windows-1252", "UTF-16LE")

    /** Short name for [CHOICES] in a menu. */
    fun label(charset: String): String = when (charset) {
        "x-windows-949" -> "CP949 (한국어)"
        "Shift_JIS" -> "Shift_JIS (日本語)"
        "GB18030" -> "GB18030 (简体中文)"
        "Big5" -> "Big5 (繁體中文)"
        "windows-1252" -> "Windows-1252 (서유럽)"
        else -> charset
    }

    /**
     * Encoding of a subtitle file: a byte order mark when there is one, UTF-8 when the bytes are valid UTF-8,
     * otherwise CP949, since old Korean SMI/SRT files are almost always that.
     */
    fun detect(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return "UTF-8"
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return "UTF-16LE"
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return "UTF-16BE"
        return if (isUtf8(bytes)) "UTF-8" else "x-windows-949"
    }

    private fun isUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (_: CharacterCodingException) {
        false
    }

    /**
     * The file as UTF-8 bytes (what Media3's parsers read), decoded from [charset]; a byte order mark is dropped.
     * An encoding this device does not know falls back to UTF-8.
     */
    fun toUtf8(bytes: ByteArray, charset: String): ByteArray {
        val cs = runCatching { Charset.forName(charset) }.getOrDefault(Charsets.UTF_8)
        val text = String(bytes, cs).removePrefix("﻿")
        return text.toByteArray(Charsets.UTF_8)
    }
}

/**
 * Subtitle lines in time order, each shown from [Entry.startUs] for [Entry.endUs]. [at] answers which lines are on
 * screen at a playback position, shifted by a delay: a positive delay shows every line later.
 */
class CueTimeline<T>(entries: List<Entry<T>>) {
    data class Entry<T>(val startUs: Long, val endUs: Long, val value: T)

    private val sorted = entries.sortedBy { it.startUs }
    private val starts = LongArray(sorted.size) { sorted[it].startUs }

    /** The longest single line, so a lookup only has to look that far back for lines that are still showing. */
    private val longest = sorted.maxOfOrNull { it.endUs - it.startUs } ?: 0L

    val size: Int get() = sorted.size

    /** Lines visible at [positionUs] with [delayUs] applied, in start order (overlapping lines all show). */
    fun at(positionUs: Long, delayUs: Long = 0): List<T> {
        if (sorted.isEmpty()) return emptyList()
        val t = positionUs - delayUs
        // Last line starting at or before t; walk back while a line could still be on screen.
        var i = upperBound(t) - 1
        val out = ArrayList<T>(2)
        while (i >= 0 && t - sorted[i].startUs <= longest) {
            val e = sorted[i]
            if (t >= e.startUs && t < e.endUs) out.add(e.value)
            i--
        }
        out.reverse()
        return out
    }

    /** Index of the first start greater than [t]. */
    private fun upperBound(t: Long): Int {
        var lo = 0
        var hi = starts.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= t) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
